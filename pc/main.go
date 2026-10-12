// PhonePad - PC側サーバー
// スマホアプリからの入力を受け取り、キーボード・マウス・ゲームパッド入力としてWindowsに送る。
package main

import (
	"crypto/rand"
	"crypto/tls"
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 公開版ではビルド時に -ldflags "-X main.appVersion=x.y.z" で上書きされる
var appVersion = "1.4.0"

const (
	protoVersion  = "1"
	defaultTCP    = 47800
	discoveryPort = 47801
	statusPort    = 47802
)

//go:embed web
var webFS embed.FS

type config struct {
	Code   string `json:"code"`
	Port   int    `json:"port"`
	WebKey string `json:"webKey"` // ブラウザ版の暗号化の鍵 (QRコードにだけ載せる)
}

type server struct {
	mu       sync.Mutex
	cfg      config
	cfgPath  string
	cert     tls.Certificate
	certFP   string // 証明書の指紋 (QRコードに入れる)
	pcName   string
	sessions map[*session]struct{}
	nextID   int
	fails    map[string][]time.Time
}

// --background: Windows起動時の自動起動用。ウィンドウもブラウザも開かずに動く
var background bool

func main() {
	afterUpdate := false
	for _, a := range os.Args[1:] {
		switch a {
		case "--background":
			background = true
		case "--after-update":
			afterUpdate = true
		}
	}
	hasConsole := setupConsole(background)
	setupDPI()
	log.SetFlags(log.Ldate | log.Ltime)
	setupLogFile(hasConsole)
	refreshAutostartPath()

	s := &server{
		sessions: map[*session]struct{}{},
		fails:    map[string][]time.Time{},
	}
	s.pcName, _ = os.Hostname()
	if s.pcName == "" {
		s.pcName = "PC"
	}
	s.loadConfig()

	fmt.Println("==============================================")
	fmt.Printf(" PhonePad PC v%s\n", appVersion)
	fmt.Println("==============================================")

	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", s.cfg.Port))
	// 更新直後は、古い方が終わるのを少し待つ
	for i := 0; err != nil && afterUpdate && i < 30; i++ {
		time.Sleep(500 * time.Millisecond)
		ln, err = net.Listen("tcp", fmt.Sprintf(":%d", s.cfg.Port))
	}
	if err != nil {
		// すでに起動している: そちらの画面を開いて終わる
		log.Println("すでに起動しています:", err)
		if !background {
			fmt.Println("PhonePad はすでに起動しています。ブラウザに画面を表示します。")
			openURL(fmt.Sprintf("http://127.0.0.1:%d/", statusPort))
			time.Sleep(2500 * time.Millisecond)
		}
		return
	}

	cleanupOldExe()
	go updateLoop()
	if afterUpdate {
		log.Printf("v%s に更新しました", appVersion)
	}
	go s.runDiscovery()
	go s.runStatusPage()
	go s.runScreenServer()
	go s.runAudioServer()
	go s.runWebApp()
	initGamepadSystem()

	fmt.Printf(" PC名      : %s\n", s.pcName)
	fmt.Printf(" 接続コード: %s\n", s.code())
	fmt.Printf(" IPアドレス: %s (ポート %d)\n", strings.Join(localIPv4s(), ", "), s.cfg.Port)
	fmt.Printf(" コントローラー: %s\n", gamepadStatusText())
	fmt.Println("----------------------------------------------")
	fmt.Println(" ブラウザにQRコードを表示しました。")
	fmt.Println(" スマホのPhonePadアプリで読み取ってください。")
	fmt.Println(" 終了するときはこのウィンドウを閉じてください。")
	fmt.Println("----------------------------------------------")

	if background {
		log.Println("自動起動しました (ウィンドウなし)")
	} else {
		time.AfterFunc(300*time.Millisecond, func() {
			openURL(fmt.Sprintf("http://127.0.0.1:%d/", statusPort))
		})
	}

	for {
		c, err := ln.Accept()
		if err != nil {
			log.Println("accept:", err)
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if tc, ok := c.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
			_ = tc.SetKeepAlive(true)
		}
		// 通信は暗号化する
		go s.handleConn(tls.Server(c, s.tlsConfig()))
	}
}

// 記録ファイル (exe と同じフォルダの PhonePad_log.txt)。
// 強制終了したときの原因もここに書かれる。
func setupLogFile(hasConsole bool) {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	p := filepath.Join(filepath.Dir(exe), "PhonePad_log.txt")
	flag := os.O_CREATE | os.O_WRONLY | os.O_APPEND
	if st, err := os.Stat(p); err == nil && st.Size() > 2<<20 {
		flag = os.O_CREATE | os.O_WRONLY | os.O_TRUNC
	}
	f, err := os.OpenFile(p, flag, 0o644)
	if err != nil {
		return
	}
	if hasConsole {
		log.SetOutput(io.MultiWriter(f, os.Stdout))
	} else {
		log.SetOutput(f)
	}
	_ = debug.SetCrashOutput(f, debug.CrashOptions{})
	log.Printf("---- PhonePad PC v%s 起動 ----", appVersion)
}

// ゴルーチン内の想定外のエラーでアプリ全体が落ちないようにする
func guard(where string) {
	if r := recover(); r != nil {
		log.Printf("エラー(%s): %v\n%s", where, r, debug.Stack())
	}
}

func (s *server) loadConfig() {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = "."
	}
	dir = filepath.Join(dir, "PhonePad")
	_ = os.MkdirAll(dir, 0o755)
	s.cfgPath = filepath.Join(dir, "config.json")
	if b, err := os.ReadFile(s.cfgPath); err == nil {
		_ = json.Unmarshal(b, &s.cfg)
	}
	if s.cfg.Port == 0 {
		s.cfg.Port = defaultTCP
	}
	if len(s.cfg.Code) != 6 {
		s.cfg.Code = randomCode()
	}
	if len(decodeWebKey(s.cfg.WebKey)) != 32 {
		s.cfg.WebKey = newWebKey()
	}
	s.saveConfig()
	if c, fp, err := loadOrCreateCert(dir); err == nil {
		s.cert, s.certFP = c, fp
	} else {
		log.Println("暗号化の準備に失敗しました:", err)
	}
}

func (s *server) saveConfig() {
	b, _ := json.MarshalIndent(s.cfg, "", "  ")
	_ = os.WriteFile(s.cfgPath, b, 0o600)
}

func (s *server) code() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cfg.Code
}

func randomCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "123456"
	}
	return fmt.Sprintf("%06d", n.Int64())
}

// 総当たり対策: 1分間に5回失敗したIPはしばらく拒否する
func (s *server) blocked(ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	cut := time.Now().Add(-time.Minute)
	var keep []time.Time
	for _, t := range s.fails[ip] {
		if t.After(cut) {
			keep = append(keep, t)
		}
	}
	s.fails[ip] = keep
	return len(keep) >= 5
}

func (s *server) addFail(ip string) {
	s.mu.Lock()
	s.fails[ip] = append(s.fails[ip], time.Now())
	s.mu.Unlock()
}

// ---- UDP自動検出 ----

func (s *server) runDiscovery() {
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		log.Println("自動検出を開始できませんでした:", err)
		return
	}
	buf := make([]byte, 512)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			continue
		}
		if strings.TrimSpace(string(buf[:n])) == "PHONEPAD?" {
			reply := fmt.Sprintf("PHONEPAD!|%d|%s|%s", s.cfg.Port, s.pcName, protoVersion)
			_, _ = pc.WriteTo([]byte(reply), addr)
		}
	}
}

// ---- 状態表示ページ (このPCからのみアクセス可) ----

func (s *server) runStatusPage() {
	srv := &http.Server{
		Addr:              fmt.Sprintf("127.0.0.1:%d", statusPort),
		Handler:           localOnly(s.statusMux()),
		ReadHeaderTimeout: 5 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Println("状態ページを開始できませんでした:", err)
	}
}

func (s *server) statusMux() *http.ServeMux {
	sub, _ := fs.Sub(webFS, "web")
	mux := http.NewServeMux()
	mux.Handle("/", http.FileServer(http.FS(sub)))
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		type devInfo struct {
			ID    int    `json:"id"`
			Name  string `json:"name"`
			IP    string `json:"ip"`
			Since string `json:"since"`
		}
		s.mu.Lock()
		var devs []string
		list := []devInfo{}
		for ss := range s.sessions {
			devs = append(devs, ss.deviceName+" ("+ss.remoteIP+")")
			list = append(list, devInfo{ss.id, ss.deviceName, ss.remoteIP, ss.since.Format("15:04")})
		}
		code := s.cfg.Code
		port := s.cfg.Port
		s.mu.Unlock()
		sort.Strings(devs)
		sort.Slice(list, func(i, j int) bool { return list[i].ID < list[j].ID })
		ips := localIPv4s()
		qr := fmt.Sprintf("PHONEPAD|%d|%s|%s|%s|%s", port, code, strings.Join(ips, ","), s.pcName, s.certFP)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"version":       appVersion,
			"pcName":        s.pcName,
			"code":          code,
			"port":          port,
			"ips":           ips,
			"qr":            qr,
			"devices":       devs,
			"deviceList":    list,
			"gamepad":       gamepadStatusText(),
			"gamepadReady":  gamepadReady(),
			"driverPresent": driverInstallerPath() != "",
			"autostart":     autostartEnabled(),
			"update":        updateStatus(),
			"siteURL":       siteURL,
			"webURL":        s.webURL(ips),
			"background":    background,
		})
	})
	mux.HandleFunc("/api/newcode", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		s.mu.Lock()
		s.cfg.Code = randomCode()
		s.cfg.WebKey = newWebKey() // ブラウザ版の古いQRコードも使えなくする
		s.saveConfig()
		// 古いコードでつながっている端末は切断する
		for ss := range s.sessions {
			ss.conn.Close()
		}
		s.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	})
	// 接続中のスマホを切断する (スマホは自動でつなぎ直さない)
	mux.HandleFunc("/api/kick", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		id, _ := strconv.Atoi(r.URL.Query().Get("id"))
		s.mu.Lock()
		var target *session
		for ss := range s.sessions {
			if ss.id == id {
				target = ss
			}
		}
		s.mu.Unlock()
		if target == nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		log.Printf("PCの画面から切断: %s (%s)", target.deviceName, target.remoteIP)
		target.send("BYE")
		time.AfterFunc(300*time.Millisecond, func() { target.conn.Close() })
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/install-driver", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		p := driverInstallerPath()
		if p == "" {
			http.Error(w, "installer not found", http.StatusNotFound)
			return
		}
		if err := shellOpen(p); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/autostart", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if err := setAutostart(r.URL.Query().Get("on") == "1"); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/update", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if err := startSelfUpdate(); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/check-update", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		checkUpdate()
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/api/quit", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		log.Println("画面から終了しました")
		w.WriteHeader(http.StatusNoContent)
		go func() {
			time.Sleep(200 * time.Millisecond)
			os.Exit(0)
		}()
	})
	mux.HandleFunc("/api/retry-gamepad", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		initGamepadSystem()
		w.WriteHeader(http.StatusNoContent)
	})
	return mux
}

// 他サイトからのリクエスト(DNSリバインディング等)を拒否する
func localOnly(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host := r.Host
		if hh, _, err := net.SplitHostPort(host); err == nil {
			host = hh
		}
		if host != "127.0.0.1" && host != "localhost" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if o := r.Header.Get("Origin"); o != "" && !strings.HasPrefix(o, "http://127.0.0.1") && !strings.HasPrefix(o, "http://localhost") {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		h.ServeHTTP(w, r)
	})
}

func driverInstallerPath() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	matches, _ := filepath.Glob(filepath.Join(filepath.Dir(exe), "ViGEmBus*setup*.exe"))
	if len(matches) == 0 {
		return ""
	}
	return matches[0]
}

// 実際にLANで使われていそうなIPv4アドレスを優先順に返す
func localIPv4s() []string {
	type cand struct {
		ip    string
		score int
	}
	var cs []cand
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(ifc.Name)
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipn.IP.To4()
			if ip4 == nil || ip4.IsLinkLocalUnicast() {
				continue
			}
			sc := 0
			if ip4[0] == 192 && ip4[1] == 168 {
				sc += 3
			} else if ip4[0] == 10 || (ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31) {
				sc += 2
			}
			if strings.Contains(name, "wi-fi") || strings.Contains(name, "wlan") || strings.Contains(name, "wireless") {
				sc += 2
			}
			if strings.Contains(name, "vethernet") || strings.Contains(name, "virtual") || strings.Contains(name, "vmware") || strings.Contains(name, "vbox") || strings.Contains(name, "wsl") {
				sc -= 5
			}
			cs = append(cs, cand{ip4.String(), sc})
		}
	}
	sort.SliceStable(cs, func(i, j int) bool { return cs[i].score > cs[j].score })
	var out []string
	for _, c := range cs {
		out = append(out, c.ip)
	}
	if len(out) == 0 {
		out = []string{"127.0.0.1"}
	}
	return out
}

func waitEnter() {
	fmt.Println("Enterキーで終了します。")
	var b [1]byte
	_, _ = os.Stdin.Read(b[:])
}
