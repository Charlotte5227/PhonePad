package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

// 接続の履歴 (設定フォルダの history.json に、新しい順で最大 historyMax 件)

const historyMax = 300

type histEntry struct {
	Time   time.Time `json:"t"`
	Kind   string    `json:"k"` // connect / disconnect / reject / power / kick / newcode
	Device string    `json:"d,omitempty"`
	IP     string    `json:"ip,omitempty"`
	Detail string    `json:"x,omitempty"`
}

type history struct {
	mu   sync.Mutex
	path string
	list []histEntry // 新しい順
}

func newHistory(dir string) *history {
	h := &history{path: filepath.Join(dir, "history.json")}
	if b, err := os.ReadFile(h.path); err == nil {
		_ = json.Unmarshal(b, &h.list)
	}
	return h
}

func (h *history) add(kind, device, ip, detail string) {
	if h == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	e := histEntry{Time: time.Now(), Kind: kind, Device: device, IP: ip, Detail: detail}
	h.list = append([]histEntry{e}, h.list...)
	if len(h.list) > historyMax {
		h.list = h.list[:historyMax]
	}
	h.saveLocked()
}

func (h *history) all() []histEntry {
	if h == nil {
		return []histEntry{}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]histEntry{}, h.list...)
}

func (h *history) clear() {
	if h == nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.list = nil
	h.saveLocked()
}

func (h *history) saveLocked() {
	if h.path == "" {
		return
	}
	b, _ := json.Marshal(h.list)
	tmp := h.path + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		_ = os.Rename(tmp, h.path)
	}
}

// 「3分」「1時間5分」のような長さの表示
func durText(d time.Duration) string {
	m := int(d.Round(time.Minute) / time.Minute)
	switch {
	case d < time.Minute:
		return "1分未満"
	case m < 60:
		return strconv.Itoa(m) + "分"
	case m%60 == 0:
		return strconv.Itoa(m/60) + "時間"
	default:
		return strconv.Itoa(m/60) + "時間" + strconv.Itoa(m%60) + "分"
	}
}
