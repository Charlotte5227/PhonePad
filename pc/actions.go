package main

// PCからスマホへ: ファイル・お知らせ・スマホで開く・スマホを鳴らす・クリップボードの自動共有
//
// やりとり:
//   ファイル (どちらの向きも同じ)  FS <id> <{n:名前, s:大きさ}>  FD <id> <中身>  FE <id>  (やめる: FX <id>)
//                                  受け取った側は FK <id> ok / FK <id> <エラー> を返す
//   お知らせ       PC → スマホ  NT <{title, text}>
//   スマホで開く   PC → スマホ  OU <URL>
//   スマホを鳴らす PC → スマホ  RING 1 / RING 0
//   アプリを閉じている間  スマホ → PC  BG 1 / BG 0  (BG 1 の間は PING の間隔が長い)
//   クリップボードの自動共有  スマホ → PC  CLA 1 / CLA 0,  PC → スマホ  CTA <文字>
// <> の中は Base64 (JSON または文字)。

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const fileChunk = 32 << 10 // 1行で送るファイルの量 (ブラウザ版の1行の上限より小さく)

type incoming struct {
	f    *os.File
	path string
	name string
	size int64
	got  int64
}

// スマホの設定: つないでいる間の状態 (session.xmu で守る)
type sessionExtra struct {
	xmu      sync.Mutex
	bg       bool
	clipAuto bool
	recv     map[string]*incoming
	acks     map[string]chan string
}

func (ss *session) readTimeout() time.Duration {
	ss.xmu.Lock()
	defer ss.xmu.Unlock()
	if ss.bg {
		return 45 * time.Second
	}
	return 4 * time.Second
}

func b64s(s string) string { return base64.StdEncoding.EncodeToString([]byte(s)) }
func unb64s(s string) string {
	b, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	return string(b)
}

// ---------------------------------------------------------------- 受け取ったファイルの置き場所

func (s *server) recvDir() string {
	if d := os.Getenv("PHONEPAD_RECV_DIR"); d != "" {
		return d
	}
	s.mu.Lock()
	d := s.cfg.RecvDir
	s.mu.Unlock()
	if d != "" {
		return d
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "."
	}
	if dl := downloadsDir(); dl != "" {
		return filepath.Join(dl, "PhonePad")
	}
	return filepath.Join(home, "Downloads", "PhonePad")
}

// ファイル名を安全にする (フォルダの区切りや使えない文字を除く)
func safeName(n string) string {
	n = filepath.Base(strings.ReplaceAll(n, "\\", "/"))
	n = strings.Map(func(r rune) rune {
		if r < 32 || strings.ContainsRune(`<>:"/\|?*`, r) {
			return '_'
		}
		return r
	}, n)
	n = strings.Trim(n, " .")
	if n == "" || n == "." || n == ".." {
		n = "file"
	}
	if len([]rune(n)) > 120 {
		ext := filepath.Ext(n)
		r := []rune(strings.TrimSuffix(n, ext))
		n = string(r[:100]) + ext
	}
	return n
}

// 同じ名前があれば「名前 (2).拡張子」にする
func uniquePath(dir, name string) string {
	p := filepath.Join(dir, name)
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 2; i < 1000; i++ {
		if _, err := os.Stat(p); os.IsNotExist(err) {
			return p
		}
		p = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", base, i, ext))
	}
	return p
}

// ---------------------------------------------------------------- スマホ → PC のファイル

func (ss *session) fileStart(id, meta string) {
	var m struct {
		N string `json:"n"`
		S int64  `json:"s"`
	}
	if json.Unmarshal([]byte(unb64s(meta)), &m) != nil || id == "" || len(id) > 40 {
		return
	}
	dir := ss.srv.recvDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		ss.send("FK " + id + " " + b64s("保存先のフォルダを作れません: "+err.Error()))
		return
	}
	name := safeName(m.N)
	path := uniquePath(dir, name)
	f, err := os.Create(path + ".part")
	if err != nil {
		ss.send("FK " + id + " " + b64s("保存できません: "+err.Error()))
		return
	}
	ss.xmu.Lock()
	if ss.recv == nil {
		ss.recv = map[string]*incoming{}
	}
	ss.recv[id] = &incoming{f: f, path: path, name: filepath.Base(path), size: m.S}
	ss.xmu.Unlock()
}

func (ss *session) fileData(id, data string) {
	ss.xmu.Lock()
	in := ss.recv[id]
	ss.xmu.Unlock()
	if in == nil {
		return
	}
	b, err := base64.StdEncoding.DecodeString(data)
	if err == nil {
		_, err = in.f.Write(b)
		in.got += int64(len(b))
	}
	if err != nil {
		ss.fileAbort(id)
		ss.send("FK " + id + " " + b64s("保存できません: "+err.Error()))
	}
}

func (ss *session) fileEnd(id string) {
	ss.xmu.Lock()
	in := ss.recv[id]
	delete(ss.recv, id)
	ss.xmu.Unlock()
	if in == nil {
		return
	}
	err := in.f.Close()
	if err == nil {
		err = os.Rename(in.path+".part", in.path)
	}
	if err != nil {
		_ = os.Remove(in.path + ".part")
		ss.send("FK " + id + " " + b64s("保存できません: "+err.Error()))
		return
	}
	ss.send("FK " + id + " ok")
	log.Printf("ファイルを受け取りました: %s (%s, %d バイト)", in.name, ss.deviceName, in.got)
	ss.srv.hist.add("file", ss.deviceName, ss.remoteIP, in.name)
	guiFileReceived(in.path, ss.deviceName)
}

func (ss *session) fileAbort(id string) {
	ss.xmu.Lock()
	in := ss.recv[id]
	delete(ss.recv, id)
	ss.xmu.Unlock()
	if in != nil {
		in.f.Close()
		_ = os.Remove(in.path + ".part")
	}
}

// 切断したときに、受け取り途中のファイルを消す
func (ss *session) cleanupTransfers() {
	ss.xmu.Lock()
	ids := make([]string, 0, len(ss.recv))
	for id := range ss.recv {
		ids = append(ids, id)
	}
	acks := ss.acks
	ss.acks = nil
	ss.xmu.Unlock()
	for _, id := range ids {
		ss.fileAbort(id)
	}
	for _, ch := range acks {
		select {
		case ch <- "切断されました":
		default:
		}
	}
}

// ---------------------------------------------------------------- PC → スマホ のファイル

var transferSeq struct {
	sync.Mutex
	n int
}

func newTransferID() string {
	transferSeq.Lock()
	defer transferSeq.Unlock()
	transferSeq.n++
	return fmt.Sprintf("p%d%d", time.Now().Unix()%100000, transferSeq.n)
}

// スマホへファイルを送り、スマホが保存し終わるのを待つ
func (ss *session) sendFile(name string, size int64, r io.Reader) error {
	id := newTransferID()
	ack := make(chan string, 1)
	ss.xmu.Lock()
	if ss.acks == nil {
		ss.acks = map[string]chan string{}
	}
	ss.acks[id] = ack
	ss.xmu.Unlock()
	defer func() {
		ss.xmu.Lock()
		delete(ss.acks, id)
		ss.xmu.Unlock()
	}()
	meta, _ := json.Marshal(map[string]any{"n": name, "s": size})
	ss.send("FS " + id + " " + base64.StdEncoding.EncodeToString(meta))
	buf := make([]byte, fileChunk)
	for {
		n, err := io.ReadFull(r, buf)
		if n > 0 {
			ss.send("FD " + id + " " + base64.StdEncoding.EncodeToString(buf[:n]))
		}
		if err == io.EOF || err == io.ErrUnexpectedEOF {
			break
		}
		if err != nil {
			ss.send("FX " + id)
			return err
		}
		select {
		case res := <-ack: // 途中で相手がやめた
			return errors.New(res)
		default:
		}
	}
	ss.send("FE " + id)
	select {
	case res := <-ack:
		if res == "ok" {
			return nil
		}
		return errors.New(res)
	case <-time.After(60 * time.Second):
		return errors.New("スマホから返事がありません")
	}
}

func (ss *session) fileAck(id, res string) {
	if res != "ok" {
		res = unb64s(res)
		if res == "" {
			res = "スマホで保存できませんでした"
		}
	}
	ss.xmu.Lock()
	ch := ss.acks[id]
	ss.xmu.Unlock()
	if ch != nil {
		select {
		case ch <- res:
		default:
		}
	}
}

// ---------------------------------------------------------------- お知らせ・スマホで開く・鳴らす

// 接続中の通信を番号で探す (0 = 全部)
func (s *server) sessionsByID(ids []int) []*session {
	s.mu.Lock()
	defer s.mu.Unlock()
	want := map[int]bool{}
	for _, id := range ids {
		want[id] = true
	}
	var out []*session
	for ss := range s.sessions {
		if len(ids) == 0 || want[ss.id] {
			out = append(out, ss)
		}
	}
	return out
}

func (s *server) notifyPhones(ids []int, title, text string) int {
	b, _ := json.Marshal(map[string]string{"title": truncate(title, 100), "text": truncate(text, 1000)})
	line := "NT " + base64.StdEncoding.EncodeToString(b)
	list := s.sessionsByID(ids)
	for _, ss := range list {
		ss.send(line)
	}
	return len(list)
}

func (s *server) openOnPhones(ids []int, url string) int {
	list := s.sessionsByID(ids)
	for _, ss := range list {
		ss.send("OU " + b64s(url))
	}
	return len(list)
}

func (s *server) ringPhones(ids []int, on bool) int {
	line := "RING 0"
	if on {
		line = "RING 1"
	}
	list := s.sessionsByID(ids)
	for _, ss := range list {
		ss.send(line)
	}
	return len(list)
}

// 時間をおいて知らせる (PhonePad を終了すると消える)
type timerItem struct {
	ID    int       `json:"id"`
	At    time.Time `json:"at"`
	Title string    `json:"title"`
	Text  string    `json:"text"`
	t     *time.Timer
}

type timerList struct {
	mu   sync.Mutex
	n    int
	list []*timerItem
}

func (s *server) addTimer(delay time.Duration, title, text string) *timerItem {
	tl := &s.timers
	tl.mu.Lock()
	defer tl.mu.Unlock()
	tl.n++
	it := &timerItem{ID: tl.n, At: time.Now().Add(delay), Title: title, Text: text}
	it.t = time.AfterFunc(delay, func() {
		s.notifyPhones(nil, title, text)
		s.removeTimer(it.ID)
	})
	tl.list = append(tl.list, it)
	return it
}

func (s *server) removeTimer(id int) {
	tl := &s.timers
	tl.mu.Lock()
	defer tl.mu.Unlock()
	for i, it := range tl.list {
		if it.ID == id {
			it.t.Stop()
			tl.list = append(tl.list[:i], tl.list[i+1:]...)
			return
		}
	}
}

func (s *server) timerSnapshot() []timerItem {
	tl := &s.timers
	tl.mu.Lock()
	defer tl.mu.Unlock()
	out := []timerItem{}
	for _, it := range tl.list {
		out = append(out, timerItem{ID: it.ID, At: it.At, Title: it.Title, Text: it.Text})
	}
	return out
}

// ---------------------------------------------------------------- クリップボードの自動共有

type clipState struct {
	mu       sync.Mutex
	fromPC   string // PCからスマホへ送った最後の文字
	fromHand string // スマホからPCに入れた最後の文字
}

// PCのクリップボードが変わった (Windows が知らせてくる)
func (s *server) clipboardChanged() {
	defer guard("クリップボード")
	t, err := getClipboardText()
	if err != nil || t == "" || len(t) > 64<<10 {
		return
	}
	s.clip.mu.Lock()
	skip := t == s.clip.fromHand || t == s.clip.fromPC
	s.clip.fromPC = t
	s.clip.mu.Unlock()
	if skip {
		return
	}
	s.mu.Lock()
	var list []*session
	for ss := range s.sessions {
		list = append(list, ss)
	}
	s.mu.Unlock()
	for _, ss := range list {
		ss.xmu.Lock()
		on := ss.clipAuto
		ss.xmu.Unlock()
		if on {
			ss.send("CTA " + b64s(t))
		}
	}
}

// スマホから受け取った文字をPCのクリップボードへ入れた (送り返さないように覚える)
func (s *server) clipFromPhone(t string) {
	s.clip.mu.Lock()
	s.clip.fromHand = t
	s.clip.mu.Unlock()
}

// ---------------------------------------------------------------- ダウンロードの見張り

// ダウンロードフォルダに新しいファイルができたら、スマホに知らせる
func (s *server) watchDownloads() {
	defer guard("ダウンロードの見張り")
	dir := downloadsDir()
	if dir == "" {
		return
	}
	partial := func(n string) bool {
		l := strings.ToLower(n)
		for _, ext := range []string{".crdownload", ".part", ".partial", ".tmp", ".download", ".opdownload"} {
			if strings.HasSuffix(l, ext) {
				return true
			}
		}
		return strings.HasPrefix(l, "~$") || strings.HasPrefix(l, ".")
	}
	known := map[string]bool{}
	sizes := map[string]int64{}
	first := true
	for {
		s.mu.Lock()
		on := s.cfg.NotifyDownloads
		s.mu.Unlock()
		ents, err := os.ReadDir(dir)
		if err == nil {
			seen := map[string]bool{}
			for _, e := range ents {
				if e.IsDir() || partial(e.Name()) {
					continue
				}
				n := e.Name()
				seen[n] = true
				if known[n] {
					continue
				}
				info, err := e.Info()
				if err != nil {
					continue
				}
				if first {
					known[n] = true
					continue
				}
				// 大きさが変わらなくなったら、できあがりとみなす
				if prev, ok := sizes[n]; ok && prev == info.Size() {
					known[n] = true
					delete(sizes, n)
					if on {
						s.notifyPhones(nil, "ダウンロードが終わりました", n)
					}
				} else {
					sizes[n] = info.Size()
				}
			}
			for n := range known {
				if !seen[n] {
					delete(known, n)
				}
			}
			first = false
		}
		time.Sleep(3 * time.Second)
	}
}
