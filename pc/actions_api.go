package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

// PCの画面「スマホに送る」のための窓口
func (s *server) actionsAPI(mux *http.ServeMux) {
	writeJSON := func(w http.ResponseWriter, v any) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(v)
	}
	post := func(h func(w http.ResponseWriter, r *http.Request)) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost {
				http.Error(w, "POST only", http.StatusMethodNotAllowed)
				return
			}
			h(w, r)
		}
	}
	// ?to=1,2 (空なら接続中の全部)
	targets := func(r *http.Request) []int {
		var ids []int
		for _, p := range strings.Split(r.URL.Query().Get("to"), ",") {
			if n, err := strconv.Atoi(strings.TrimSpace(p)); err == nil {
				ids = append(ids, n)
			}
		}
		return ids
	}
	type msg struct {
		Title string `json:"title"`
		Text  string `json:"text"`
		URL   string `json:"url"`
		Delay int    `json:"delay"` // 秒
	}
	read := func(r *http.Request, v any) error {
		b, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		if err != nil {
			return err
		}
		return json.Unmarshal(b, v)
	}

	mux.HandleFunc("/api/send/notify", post(func(w http.ResponseWriter, r *http.Request) {
		var m msg
		if read(r, &m) != nil || strings.TrimSpace(m.Text+m.Title) == "" {
			http.Error(w, "お知らせの文を入れてください", http.StatusBadRequest)
			return
		}
		if m.Title == "" {
			m.Title = "PCからのお知らせ"
		}
		if m.Delay > 0 {
			it := s.addTimer(time.Duration(m.Delay)*time.Second, m.Title, m.Text)
			writeJSON(w, map[string]any{"timer": it.ID})
			return
		}
		n := s.notifyPhones(targets(r), m.Title, m.Text)
		writeJSON(w, map[string]int{"sent": n})
	}))
	mux.HandleFunc("/api/send/timers", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			id, _ := strconv.Atoi(r.URL.Query().Get("cancel"))
			s.removeTimer(id)
			w.WriteHeader(http.StatusNoContent)
			return
		}
		writeJSON(w, s.timerSnapshot())
	})
	mux.HandleFunc("/api/send/open", post(func(w http.ResponseWriter, r *http.Request) {
		var m msg
		_ = read(r, &m)
		u := strings.TrimSpace(m.URL)
		if !strings.Contains(u, "://") && strings.Contains(u, ".") && !strings.Contains(u, " ") {
			u = "https://" + u
		}
		if pu, err := url.Parse(u); err != nil || (pu.Scheme != "http" && pu.Scheme != "https") {
			http.Error(w, "https:// で始まるアドレスを入れてください", http.StatusBadRequest)
			return
		}
		writeJSON(w, map[string]int{"sent": s.openOnPhones(targets(r), u)})
	}))
	mux.HandleFunc("/api/send/ring", post(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]int{"sent": s.ringPhones(targets(r), r.URL.Query().Get("on") != "0")})
	}))
	// ファイルを送る (中身はそのまま) ?to=番号&name=名前
	mux.HandleFunc("/api/send/file", post(func(w http.ResponseWriter, r *http.Request) {
		list := s.sessionsByID(targets(r))
		if len(list) != 1 {
			http.Error(w, "送り先のスマホを1台選んでください", http.StatusBadRequest)
			return
		}
		name := safeName(r.URL.Query().Get("name"))
		if err := list[0].sendFile(name, r.ContentLength, r.Body); err != nil {
			http.Error(w, "送れませんでした: "+err.Error(), http.StatusBadGateway)
			return
		}
		s.hist.add("sendfile", list[0].deviceName, list[0].remoteIP, name)
		w.WriteHeader(http.StatusNoContent)
	}))
	// 受け取ったファイルの置き場所・ダウンロードの見張り
	mux.HandleFunc("/api/send/settings", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			var m struct {
				NotifyDownloads *bool `json:"notifyDownloads"`
			}
			_ = read(r, &m)
			s.mu.Lock()
			if m.NotifyDownloads != nil {
				s.cfg.NotifyDownloads = *m.NotifyDownloads
			}
			s.saveConfig()
			s.mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
			return
		}
		s.mu.Lock()
		nd := s.cfg.NotifyDownloads
		s.mu.Unlock()
		writeJSON(w, map[string]any{"recvDir": s.recvDir(), "notifyDownloads": nd, "downloads": downloadsDir()})
	})
	mux.HandleFunc("/api/send/open-folder", post(func(w http.ResponseWriter, r *http.Request) {
		d := s.recvDir()
		_ = os.MkdirAll(d, 0o755)
		_ = shellOpen(d)
		w.WriteHeader(http.StatusNoContent)
	}))
}

// コマンドから使う: PhonePad.exe --notify "文" など。起動中の PhonePad に頼んで終わる
func runCLI(args []string) bool {
	if len(args) == 0 {
		return false
	}
	base := fmt.Sprintf("http://127.0.0.1:%d", statusPort)
	postJSON := func(path string, v any) error {
		b, _ := json.Marshal(v)
		r, err := http.Post(base+path, "application/json", strings.NewReader(string(b)))
		if err != nil {
			return fmt.Errorf("PhonePad が起動していません")
		}
		defer r.Body.Close()
		if r.StatusCode >= 300 {
			t, _ := io.ReadAll(r.Body)
			return fmt.Errorf("%s", strings.TrimSpace(string(t)))
		}
		return nil
	}
	var err error
	switch args[0] {
	case "--notify":
		title, text := "", ""
		rest := args[1:]
		if len(rest) >= 2 && rest[0] == "--title" {
			title, rest = rest[1], rest[2:]
		}
		text = strings.Join(rest, " ")
		err = postJSON("/api/send/notify", map[string]string{"title": title, "text": text})
	case "--open-on-phone":
		if len(args) < 2 {
			return true
		}
		err = postJSON("/api/send/open", map[string]string{"url": args[1]})
	case "--ring-phone":
		err = postJSON("/api/send/ring", nil)
	case "--send-to-phone":
		for _, p := range args[1:] {
			f, e := os.Open(p)
			if e != nil {
				err = e
				break
			}
			st, _ := f.Stat()
			req, _ := http.NewRequest(http.MethodPost, base+"/api/send/file?name="+url.QueryEscape(st.Name()), f)
			req.ContentLength = st.Size()
			r, e := http.DefaultClient.Do(req)
			f.Close()
			if e != nil {
				err = fmt.Errorf("PhonePad が起動していません")
				break
			}
			r.Body.Close()
			if r.StatusCode >= 300 {
				err = fmt.Errorf("送れませんでした (接続中のスマホが1台のときだけ使えます)")
				break
			}
		}
	default:
		return false
	}
	if err != nil {
		cliError(err.Error())
	}
	return true
}
