package main

import (
	"encoding/json"
	"io"
	"net/http"
	"time"
)

// PCの画面 (スマホの設定・アプリごとの配置・バックアップ) のための窓口
func (s *server) syncAPI(mux *http.ServeMux) {
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
	readBody := func(r *http.Request, v any) error {
		b, err := io.ReadAll(io.LimitReader(r.Body, 8<<20))
		if err != nil {
			return err
		}
		return json.Unmarshal(b, v)
	}

	mux.HandleFunc("/api/phones", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, s.phoneList())
	})
	// スマホの設定を変える {id, items}
	mux.HandleFunc("/api/phones/set", post(func(w http.ResponseWriter, r *http.Request) {
		var m struct {
			ID    string            `json:"id"`
			Items map[string]string `json:"items"`
		}
		if err := readBody(r, &m); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := s.setPhoneItems(m.ID, m.Items); err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	mux.HandleFunc("/api/phones/rename", post(func(w http.ResponseWriter, r *http.Request) {
		s.renamePhone(r.URL.Query().Get("id"), r.URL.Query().Get("name"))
		w.WriteHeader(http.StatusNoContent)
	}))
	mux.HandleFunc("/api/phones/forget", post(func(w http.ResponseWriter, r *http.Request) {
		s.forgetPhone(r.URL.Query().Get("id"))
		w.WriteHeader(http.StatusNoContent)
	}))
	// 設定を別のスマホへ写す {from, to, parts}
	mux.HandleFunc("/api/phones/copy", post(func(w http.ResponseWriter, r *http.Request) {
		var m struct {
			From  string   `json:"from"`
			To    string   `json:"to"`
			Parts []string `json:"parts"`
		}
		if err := readBody(r, &m); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := s.copyPhone(m.From, m.To, m.Parts); err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	// PCのアプリ一覧 (ショートカットで開くアプリを選ぶ)
	mux.HandleFunc("/api/apps", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, listApps())
	})

	// アプリごとの配置
	mux.HandleFunc("/api/profiles", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			var m struct {
				AutoOff  bool          `json:"autoOff"`
				Profiles []*appProfile `json:"profiles"`
			}
			if err := readBody(r, &m); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			s.setProfiles(m.Profiles, m.AutoOff)
			w.WriteHeader(http.StatusNoContent)
			return
		}
		writeJSON(w, s.profileState())
	})

	// バックアップ
	mux.HandleFunc("/api/backup", func(w http.ResponseWriter, r *http.Request) {
		name := "PhonePad-backup-" + time.Now().Format("20060102-1504") + ".json"
		w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		enc := json.NewEncoder(w)
		enc.SetIndent("", " ")
		_ = enc.Encode(s.backup())
	})
	mux.HandleFunc("/api/restore", post(func(w http.ResponseWriter, r *http.Request) {
		var b backupFile
		if err := readBody(r, &b); err != nil {
			http.Error(w, "ファイルを読めませんでした", http.StatusBadRequest)
			return
		}
		n, err := s.restore(b)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, map[string]int{"phones": n, "profiles": len(b.Profiles)})
	}))
}
