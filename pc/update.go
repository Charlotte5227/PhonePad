package main

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 新しいバージョンの確認 (GitHub の公開リリース)

const (
	repoOwner     = "Charlotte5227"
	repoName      = "PhonePad"
	latestAPI     = "https://api.github.com/repos/" + repoOwner + "/" + repoName + "/releases/latest"
	siteURL       = "https://charlotte5227.github.io/PhonePad/"
	pcAssetName   = "PhonePad.exe"
	checkInterval = 6 * time.Hour
)

type ghAsset struct {
	Name string `json:"name"`
	URL  string `json:"browser_download_url"`
	Size int64  `json:"size"`
}

type ghRelease struct {
	TagName string    `json:"tag_name"`
	Name    string    `json:"name"`
	Body    string    `json:"body"`
	HTMLURL string    `json:"html_url"`
	Assets  []ghAsset `json:"assets"`
}

var (
	updMu      sync.Mutex
	updLatest  *ghRelease
	updErr     string
	updRunning bool
)

// "v1.2.3" → [1 2 3]
func parseVersion(v string) []int {
	v = strings.TrimPrefix(strings.TrimSpace(v), "v")
	var out []int
	for _, p := range strings.Split(v, ".") {
		n, err := strconv.Atoi(strings.TrimFunc(p, func(r rune) bool { return r < '0' || r > '9' }))
		if err != nil {
			n = 0
		}
		out = append(out, n)
	}
	return out
}

// a が b より新しいか
func isNewer(a, b string) bool {
	pa, pb := parseVersion(a), parseVersion(b)
	for i := 0; i < len(pa) || i < len(pb); i++ {
		x, y := 0, 0
		if i < len(pa) {
			x = pa[i]
		}
		if i < len(pb) {
			y = pb[i]
		}
		if x != y {
			return x > y
		}
	}
	return false
}

func fetchLatest() (*ghRelease, error) {
	req, _ := http.NewRequest("GET", latestAPI, nil)
	req.Header.Set("User-Agent", "PhonePad/"+appVersion)
	req.Header.Set("Accept", "application/vnd.github+json")
	c := &http.Client{Timeout: 15 * time.Second}
	r, err := c.Do(req)
	if err != nil {
		return nil, err
	}
	defer r.Body.Close()
	if r.StatusCode == 404 {
		return nil, errors.New("まだ公開されたバージョンがありません")
	}
	if r.StatusCode != 200 {
		return nil, errors.New("更新の確認に失敗しました (" + r.Status + ")")
	}
	var rel ghRelease
	if err := json.NewDecoder(r.Body).Decode(&rel); err != nil {
		return nil, err
	}
	return &rel, nil
}

func checkUpdate() {
	rel, err := fetchLatest()
	updMu.Lock()
	defer updMu.Unlock()
	if err != nil {
		updErr = err.Error()
		return
	}
	updErr = ""
	updLatest = rel
	if isNewer(rel.TagName, appVersion) {
		log.Printf("新しいバージョン %s があります", rel.TagName)
	}
}

func updateLoop() {
	defer guard("更新の確認")
	time.Sleep(5 * time.Second)
	for {
		checkUpdate()
		time.Sleep(checkInterval)
	}
}

// 状態ページ用
func updateStatus() map[string]any {
	updMu.Lock()
	defer updMu.Unlock()
	m := map[string]any{"current": appVersion, "error": updErr, "running": updRunning}
	if updLatest != nil && isNewer(updLatest.TagName, appVersion) {
		m["available"] = true
		m["version"] = strings.TrimPrefix(updLatest.TagName, "v")
		notes := updLatest.Body
		if len([]rune(notes)) > 1500 {
			notes = string([]rune(notes)[:1500]) + "…"
		}
		m["notes"] = notes
	}
	return m
}

func pcAssetURL() (string, string, error) {
	updMu.Lock()
	rel := updLatest
	updMu.Unlock()
	if rel == nil || !isNewer(rel.TagName, appVersion) {
		return "", "", errors.New("新しいバージョンはありません")
	}
	for _, a := range rel.Assets {
		if a.Name == pcAssetName {
			return a.URL, strings.TrimPrefix(rel.TagName, "v"), nil
		}
	}
	return "", "", errors.New("更新用のファイルが見つかりません")
}

// 更新を始める (ダウンロード → 入れ替え → 再起動)
func startSelfUpdate() error {
	url, ver, err := pcAssetURL()
	if err != nil {
		checkUpdate()
		if url, ver, err = pcAssetURL(); err != nil {
			return err
		}
	}
	updMu.Lock()
	if updRunning {
		updMu.Unlock()
		return errors.New("すでに更新しています")
	}
	updRunning = true
	updMu.Unlock()
	go func() {
		defer guard("更新")
		log.Printf("v%s に更新します", ver)
		if err := selfUpdate(url); err != nil {
			log.Println("更新に失敗しました:", err)
			updMu.Lock()
			updRunning = false
			updErr = "更新に失敗しました: " + err.Error()
			updMu.Unlock()
		}
	}()
	return nil
}
