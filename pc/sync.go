package main

// スマホの設定 (配置・ショートカット) をPCでも持っておき、PCの画面から変えられるようにする。
//
// やりとり (1行ずつ、中身は JSON を Base64 にしたもの):
//   スマホ → PC  CFG {id, kind, name, items:{キー:値}, dirty:[PCに伝えていない変更のキー]}  つないだ直後に全部
//   スマホ → PC  CFK {items}            つないでいる間にスマホで変えたとき
//   スマホ → PC  CPK {id, items}        アプリごとの配置を使っている間にスマホで変えたとき
//   PC → スマホ  CU  {items}            PCの画面で変えた内容 (スマホは保存して画面を作り直す)
//   PC → スマホ  CA  [キー]             スマホの変更を受け取った (スマホは「伝えていない」印を消す)
//   PC → スマホ  CP  {id, name, items}  前に出ているアプリ用の配置を一時的に使う / "CP" だけなら元に戻す
// 値は文字列 (多くは JSON)。"" は「最初の状態」。

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxItemLen = 256 << 10

var kbLayoutKey = regexp.MustCompile(`^kbLayout\.[A-Za-z]{2,8}(\.land)?$`)

// 同期する設定のキー
func isSyncKey(k string) bool {
	switch k {
	case "shortcuts", "kbCustom", "padLayout", "padAreas", "padPresets", "menuOrder":
		return true
	}
	return kbLayoutKey.MatchString(k)
}

// 設定の分類 (コピー・アプリごとの配置で使う)
func keyPart(k string) string {
	switch {
	case k == "shortcuts":
		return "sc"
	case k == "kbCustom" || strings.HasPrefix(k, "kbLayout."):
		return "kb"
	case strings.HasPrefix(k, "pad"):
		return "pad"
	}
	return ""
}

type phoneProfile struct {
	ID       string            `json:"id"`
	Name     string            `json:"name"`
	Alias    string            `json:"alias,omitempty"` // PCの画面で付けた名前
	Kind     string            `json:"kind"`            // android / web
	LastSeen time.Time         `json:"lastSeen"`
	Items    map[string]string `json:"items"`
	Pending  map[string]bool   `json:"pending,omitempty"` // PCで変えて、まだスマホに届けていない
}

// アプリごとの配置
type appProfile struct {
	ID    string            `json:"id"`
	Name  string            `json:"name"`
	Apps  []string          `json:"apps"` // 実行ファイル名 (小文字)
	Items map[string]string `json:"items"`
	Off   bool              `json:"off,omitempty"`
	Kind  string            `json:"kind,omitempty"` // 元にしたスマホの種類 (PCの画面で初期配置を出すため)
}

type recentApp struct {
	Exe   string    `json:"exe"`
	Title string    `json:"title"`
	Time  time.Time `json:"t"`
}

type syncStore struct {
	mu       sync.Mutex
	path     string
	Phones   map[string]*phoneProfile `json:"phones"`
	Profiles []*appProfile            `json:"profiles"`
	AutoOff  bool                     `json:"autoOff,omitempty"` // アプリごとの切り替えを止める

	// 保存しないもの
	fgExe   string
	fgTitle string
	active  string // 使っているアプリごとの配置の ID
	recent  []recentApp
}

func newSyncStore(dir string) *syncStore {
	st := &syncStore{path: filepath.Join(dir, "phones.json"), Phones: map[string]*phoneProfile{}}
	if b, err := os.ReadFile(st.path); err == nil {
		_ = json.Unmarshal(b, st)
	}
	if st.Phones == nil {
		st.Phones = map[string]*phoneProfile{}
	}
	for _, p := range st.Phones {
		if p.Items == nil {
			p.Items = map[string]string{}
		}
	}
	return st
}

func (st *syncStore) saveLocked() {
	if st == nil || st.path == "" {
		return
	}
	b, _ := json.MarshalIndent(st, "", " ")
	tmp := st.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, st.path)
	}
}

func b64json(v any) string {
	b, _ := json.Marshal(v)
	return base64.StdEncoding.EncodeToString(b)
}

func unb64json(s string, v any) error {
	b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func cleanItems(in map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range in {
		if isSyncKey(k) && len(v) <= maxItemLen {
			out[k] = v
		}
	}
	return out
}

// ---------------------------------------------------------------- スマホからの知らせ

type cfgMsg struct {
	ID    string            `json:"id"`
	Kind  string            `json:"kind"`
	Name  string            `json:"name"`
	Items map[string]string `json:"items"`
	Dirty []string          `json:"dirty"`
}

func validID(id string) bool {
	if len(id) < 8 || len(id) > 64 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

// つないだ直後: PCで変えた分を届け、スマホの分を受け取る
func (ss *session) handleCFG(arg string) {
	var m cfgMsg
	if unb64json(arg, &m) != nil || !validID(m.ID) {
		return
	}
	s := ss.srv
	st := s.sync
	items := cleanItems(m.Items)
	// 先に「やりとりできる」印をつける (この後にPCの画面で変えた分は、すぐ届く)
	s.mu.Lock()
	ss.devID = m.ID
	ss.cfgOK = true
	s.mu.Unlock()
	dirty := map[string]bool{}
	for _, k := range m.Dirty {
		dirty[k] = true
	}

	st.mu.Lock()
	p := st.Phones[m.ID]
	if p == nil {
		p = &phoneProfile{ID: m.ID, Items: map[string]string{}}
		st.Phones[m.ID] = p
	}
	p.Name = truncate(strings.TrimSpace(m.Name), 60)
	if p.Name == "" {
		p.Name = ss.deviceName
	}
	if m.Kind == "web" {
		p.Kind = "web"
	} else {
		p.Kind = "android"
	}
	p.LastSeen = time.Now()
	send := map[string]string{}
	for k := range p.Pending {
		if !dirty[k] {
			send[k] = p.Items[k]
		}
	}
	for k, v := range items {
		if _, ok := send[k]; ok {
			continue
		}
		p.Items[k] = v
	}
	p.Pending = nil
	st.saveLocked()
	prof := st.activeProfileLocked()
	st.mu.Unlock()

	if len(send) > 0 {
		ss.send("CU " + b64json(map[string]any{"items": send}))
	}
	if len(m.Dirty) > 0 {
		ss.send("CA " + b64json(m.Dirty))
	}
	if prof != nil {
		ss.send("CP " + b64json(prof))
	}
	log.Printf("スマホの設定を受け取りました: %s (%d 項目)", p.Name, len(items))
}

// つないでいる間にスマホで変えた
func (ss *session) handleCFK(arg string) {
	var m struct {
		Items map[string]string `json:"items"`
	}
	if unb64json(arg, &m) != nil {
		return
	}
	s := ss.srv
	s.mu.Lock()
	id, ok := ss.devID, ss.cfgOK
	s.mu.Unlock()
	if !ok {
		return
	}
	items := cleanItems(m.Items)
	st := s.sync
	st.mu.Lock()
	if p := st.Phones[id]; p != nil {
		var keys []string
		for k, v := range items {
			p.Items[k] = v
			delete(p.Pending, k)
			keys = append(keys, k)
		}
		st.saveLocked()
		st.mu.Unlock()
		sort.Strings(keys)
		ss.send("CA " + b64json(keys))
		return
	}
	st.mu.Unlock()
}

// アプリごとの配置を使っている間に、スマホでその配置を変えた
func (ss *session) handleCPK(arg string) {
	var m struct {
		ID    string            `json:"id"`
		Items map[string]string `json:"items"`
	}
	if unb64json(arg, &m) != nil {
		return
	}
	st := ss.srv.sync
	st.mu.Lock()
	defer st.mu.Unlock()
	for _, pr := range st.Profiles {
		if pr.ID == m.ID {
			for k, v := range cleanItems(m.Items) {
				if _, ok := pr.Items[k]; ok {
					pr.Items[k] = v
				}
			}
			st.saveLocked()
			return
		}
	}
}

// ---------------------------------------------------------------- PCの画面から変える

// 接続中で設定をやりとりできるそのスマホの通信
func (s *server) sessionsFor(id string) []*session {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []*session
	for ss := range s.sessions {
		if ss.cfgOK && (id == "" || ss.devID == id) {
			out = append(out, ss)
		}
	}
	return out
}

var errNoPhone = errors.New("そのスマホは見つかりません")

// スマホの設定を変える (つながっていればすぐ届け、いなければ次につないだときに届ける)
func (s *server) setPhoneItems(id string, items map[string]string) error {
	items = cleanItems(items)
	st := s.sync
	st.mu.Lock()
	p := st.Phones[id]
	if p == nil {
		st.mu.Unlock()
		return errNoPhone
	}
	live := s.sessionsFor(id)
	for k, v := range items {
		p.Items[k] = v
		if len(live) == 0 {
			if p.Pending == nil {
				p.Pending = map[string]bool{}
			}
			p.Pending[k] = true
		} else {
			delete(p.Pending, k)
		}
	}
	st.saveLocked()
	st.mu.Unlock()
	for _, ss := range live {
		ss.send("CU " + b64json(map[string]any{"items": items}))
	}
	return nil
}

func (s *server) renamePhone(id, name string) {
	st := s.sync
	st.mu.Lock()
	if p := st.Phones[id]; p != nil {
		p.Alias = truncate(strings.TrimSpace(name), 40)
		st.saveLocked()
	}
	st.mu.Unlock()
}

func (s *server) forgetPhone(id string) {
	st := s.sync
	st.mu.Lock()
	delete(st.Phones, id)
	st.saveLocked()
	st.mu.Unlock()
}

// 1台のスマホの設定を、別のスマホへ写す (parts: kb / pad / sc)
func (s *server) copyPhone(from, to string, parts []string) error {
	want := map[string]bool{}
	for _, p := range parts {
		want[p] = true
	}
	st := s.sync
	st.mu.Lock()
	src, dst := st.Phones[from], st.Phones[to]
	if src == nil || dst == nil {
		st.mu.Unlock()
		return errNoPhone
	}
	items := map[string]string{}
	for k, v := range src.Items {
		if want[keyPart(k)] {
			items[k] = v
		}
	}
	st.mu.Unlock()
	return s.setPhoneItems(to, items)
}

// ---------------------------------------------------------------- アプリごとの配置

// 使っているアプリごとの配置 (写しを返す。ロックの外で読んでよい)
func (st *syncStore) activeProfileLocked() *appProfile {
	if st.active == "" || st.AutoOff {
		return nil
	}
	for _, p := range st.Profiles {
		if p.ID == st.active && !p.Off {
			return p.clone()
		}
	}
	return nil
}

func (p *appProfile) clone() *appProfile {
	c := *p
	c.Apps = append([]string{}, p.Apps...)
	c.Items = map[string]string{}
	for k, v := range p.Items {
		c.Items[k] = v
	}
	return &c
}

func (st *syncStore) profilesCopyLocked() []*appProfile {
	out := []*appProfile{}
	for _, p := range st.Profiles {
		out = append(out, p.clone())
	}
	return out
}

func (st *syncStore) profileForLocked(exe string) string {
	if st.AutoOff || exe == "" {
		return ""
	}
	for _, p := range st.Profiles {
		if p.Off {
			continue
		}
		for _, a := range p.Apps {
			if strings.EqualFold(a, exe) {
				return p.ID
			}
		}
	}
	return ""
}

// 前に出ているアプリが変わった
func (s *server) foregroundChanged(exe, title string) {
	st := s.sync
	st.mu.Lock()
	st.fgExe, st.fgTitle = exe, title
	if exe != "" {
		// 最近使ったアプリ (アプリごとの配置を作るときの候補)
		var keep []recentApp
		for _, r := range st.recent {
			if !strings.EqualFold(r.Exe, exe) {
				keep = append(keep, r)
			}
		}
		st.recent = append([]recentApp{{Exe: exe, Title: truncate(title, 80), Time: time.Now()}}, keep...)
		if len(st.recent) > 20 {
			st.recent = st.recent[:20]
		}
	}
	st.mu.Unlock()
	s.applyActiveProfile()
}

// 使うべき配置が変わったら、スマホに知らせる
func (s *server) applyActiveProfile() {
	st := s.sync
	st.mu.Lock()
	id := st.profileForLocked(st.fgExe)
	changed := id != st.active
	st.active = id
	prof := st.activeProfileLocked()
	st.mu.Unlock()
	if !changed {
		return
	}
	line := "CP"
	if prof != nil {
		line = "CP " + b64json(prof)
		log.Printf("アプリごとの配置: 「%s」に切り替え", prof.Name)
	}
	for _, ss := range s.sessionsFor("") {
		ss.send(line)
	}
}

// アプリごとの配置の一覧を入れ替える (PCの画面から)
func (s *server) setProfiles(list []*appProfile, autoOff bool) {
	st := s.sync
	st.mu.Lock()
	clean := []*appProfile{}
	for _, p := range list {
		if p == nil || p.ID == "" {
			continue
		}
		p.Name = truncate(strings.TrimSpace(p.Name), 40)
		var apps []string
		for _, a := range p.Apps {
			a = strings.ToLower(strings.TrimSpace(a))
			if a != "" && len(a) < 200 {
				apps = append(apps, a)
			}
		}
		p.Apps = apps
		p.Items = cleanItems(p.Items)
		clean = append(clean, p)
	}
	st.Profiles = clean
	st.AutoOff = autoOff
	st.saveLocked()
	prevActive := st.active
	prof := st.activeProfileLocked()
	st.mu.Unlock()
	// 中身が変わったかもしれないので、使っている配置を送り直す
	s.applyActiveProfile()
	st.mu.Lock()
	still := st.active == prevActive && prevActive != ""
	st.mu.Unlock()
	if still && prof != nil {
		for _, ss := range s.sessionsFor("") {
			ss.send("CP " + b64json(prof))
		}
	}
}

// ---------------------------------------------------------------- バックアップ

type backupFile struct {
	App      string          `json:"app"`
	Version  string          `json:"version"`
	Saved    time.Time       `json:"saved"`
	Phones   []*phoneProfile `json:"phones"`
	Profiles []*appProfile   `json:"profiles"`
	AutoOff  bool            `json:"autoOff,omitempty"`
}

func (s *server) backup() backupFile {
	st := s.sync
	st.mu.Lock()
	defer st.mu.Unlock()
	b := backupFile{App: "PhonePad", Version: appVersion, Saved: time.Now(), Profiles: st.profilesCopyLocked(), AutoOff: st.AutoOff}
	for _, p := range st.Phones {
		c := *p
		c.Pending = nil
		c.Items = map[string]string{}
		for k, v := range p.Items {
			c.Items[k] = v
		}
		b.Phones = append(b.Phones, &c)
	}
	sort.Slice(b.Phones, func(i, j int) bool { return b.Phones[i].LastSeen.After(b.Phones[j].LastSeen) })
	return b
}

// バックアップから戻す: スマホの設定は次につないだとき (つながっていればすぐ) に届ける
func (s *server) restore(b backupFile) (int, error) {
	if b.App != "PhonePad" {
		return 0, errors.New("PhonePad のバックアップではありません")
	}
	st := s.sync
	n := 0
	st.mu.Lock()
	for _, p := range b.Phones {
		if p == nil || !validID(p.ID) {
			continue
		}
		cur := st.Phones[p.ID]
		if cur == nil {
			cur = &phoneProfile{ID: p.ID, Name: p.Name, Kind: p.Kind, LastSeen: p.LastSeen, Items: map[string]string{}}
			st.Phones[p.ID] = cur
		}
		n++
	}
	st.mu.Unlock()
	for _, p := range b.Phones {
		if p == nil || !validID(p.ID) {
			continue
		}
		_ = s.setPhoneItems(p.ID, p.Items)
	}
	if b.Profiles != nil {
		// 同じ ID のものは置き換え、それ以外は足す
		st.mu.Lock()
		merged := append([]*appProfile{}, st.Profiles...)
		autoOff := st.AutoOff
		st.mu.Unlock()
		for _, np := range b.Profiles {
			if np == nil {
				continue
			}
			replaced := false
			for i, op := range merged {
				if op.ID == np.ID {
					merged[i] = np
					replaced = true
				}
			}
			if !replaced {
				merged = append(merged, np)
			}
		}
		s.setProfiles(merged, autoOff)
	}
	return n, nil
}

// PCの画面に出す一覧
type phoneView struct {
	*phoneProfile
	Connected bool `json:"connected"`
}

func (s *server) phoneList() []phoneView {
	live := map[string]bool{}
	for _, ss := range s.sessionsFor("") {
		live[ss.devID] = true
	}
	st := s.sync
	st.mu.Lock()
	defer st.mu.Unlock()
	out := []phoneView{}
	for _, p := range st.Phones {
		c := *p
		c.Items = map[string]string{}
		for k, v := range p.Items {
			c.Items[k] = v
		}
		c.Pending = map[string]bool{}
		for k, v := range p.Pending {
			c.Pending[k] = v
		}
		out = append(out, phoneView{&c, live[p.ID]})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Connected != out[j].Connected {
			return out[i].Connected
		}
		return out[i].LastSeen.After(out[j].LastSeen)
	})
	return out
}

func (s *server) profileState() map[string]any {
	st := s.sync
	st.mu.Lock()
	defer st.mu.Unlock()
	profiles := st.profilesCopyLocked()
	recent := st.recent
	if recent == nil {
		recent = []recentApp{}
	}
	return map[string]any{
		"autoOff":   st.AutoOff,
		"profiles":  profiles,
		"active":    st.active,
		"fgExe":     st.fgExe,
		"fgTitle":   st.fgTitle,
		"recent":    recent,
		"supported": foregroundSupported,
	}
}

// 前に出ているアプリを見張る
func (s *server) watchForeground() {
	defer guard("アプリの見張り")
	last := "\x00"
	for {
		exe, title := foregroundApp()
		if strings.EqualFold(exe, selfExeName()) {
			// PhonePad 自身 (通知領域のメニューなど) は数えない
			time.Sleep(700 * time.Millisecond)
			continue
		}
		key := strings.ToLower(exe)
		if key != last {
			last = key
			s.foregroundChanged(key, title)
		} else {
			st := s.sync
			st.mu.Lock()
			st.fgTitle = title
			st.mu.Unlock()
		}
		time.Sleep(700 * time.Millisecond)
	}
}

func selfExeName() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	return filepath.Base(exe)
}
