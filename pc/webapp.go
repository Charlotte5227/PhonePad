package main

// ブラウザ版 (iPhone など、アプリを入れられない端末向け)
//
// PCが操作画面のWebページを配信し、ブラウザとは WebSocket でつなぐ。
// 通信は NaCl secretbox (XSalsa20-Poly1305) で暗号化する。鍵はQRコードのURLの
// 「#」より後ろにだけ載せるので、ネットワークには流れない。
//
//   1. PC → ブラウザ (暗号化なし): "PPW1" + ランダム24バイト(S)
//   2. 以降の鍵 = SHA-512(鍵 || S) の先頭32バイト (接続ごとに変わる)
//   3. 以降はすべて暗号化。nonce = 向き(1バイト) + 通し番号(8バイト) + 0埋め。
//      通し番号は1ずつ増えるので、録画した通信を流し直されても受け付けない
//   4. 中身の先頭1バイトが種類:
//        'L' 1行のコマンド (アプリと同じ。最初は HELLO)
//        'S' 画面: ブラウザ→PC "幅 画質"、PC→ブラウザ JPEG
//        'A' 音: ブラウザ→PC "1"(開始)/"0"(停止)、PC→ブラウザ 音のデータ
//        'a' 音の形式: PC→ブラウザ "サンプリング周波数"

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha512"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"hash/fnv"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"phonepad/internal/nacl/secretbox"
)

const webPort = 47806

//go:embed webapp
var webappFS embed.FS

func newWebKey() string {
	k := make([]byte, 32)
	_, _ = rand.Read(k)
	return base64.RawURLEncoding.EncodeToString(k)
}

func decodeWebKey(s string) []byte {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return nil
	}
	return b
}

// ブラウザ版を開くURL (QRコード用)。鍵と接続コードは「#」の後ろ
func (s *server) webURL(ips []string) string {
	if len(ips) == 0 {
		return ""
	}
	s.mu.Lock()
	key, code := s.cfg.WebKey, s.cfg.Code
	s.mu.Unlock()
	return fmt.Sprintf("http://%s:%d/#%s.%s", ips[0], webPort, key, code)
}

func (s *server) runWebApp() {
	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", webPort),
		Handler:           s.webMux(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	if err := srv.ListenAndServe(); err != nil {
		log.Println("ブラウザ版を開始できませんでした:", err)
	}
}

func (s *server) webMux() *http.ServeMux {
	sub, _ := fs.Sub(webappFS, "webapp")
	files := http.FileServer(http.FS(sub))
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		// 更新したらすぐ新しい画面になるように、毎回確認させる
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		files.ServeHTTP(w, r)
	})
	mux.HandleFunc("/ws", s.handleWS)
	return mux
}

// ---------------------------------------------------------------- WebSocket (RFC 6455) の最小実装

type wsConn struct {
	c   net.Conn
	br  *bufio.Reader
	wmu sync.Mutex
}

const (
	wsText   = 1
	wsBinary = 2
	wsClose  = 8
	wsPing   = 9
	wsPong   = 10

	wsMaxMessage = 1 << 20
)

func wsAccept(w http.ResponseWriter, r *http.Request) (*wsConn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") ||
		!strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade") {
		return nil, errors.New("not websocket")
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" || r.Header.Get("Sec-WebSocket-Version") != "13" {
		return nil, errors.New("bad websocket request")
	}
	// 他のサイトのページからつながれないようにする
	if o := r.Header.Get("Origin"); o != "" && o != "http://"+r.Host {
		return nil, errors.New("origin")
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		return nil, errors.New("hijack unsupported")
	}
	c, brw, err := hj.Hijack()
	if err != nil {
		return nil, err
	}
	h := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	resp := "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(h[:]) + "\r\n\r\n"
	_ = c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Write([]byte(resp)); err != nil {
		c.Close()
		return nil, err
	}
	return &wsConn{c: c, br: brw.Reader}, nil
}

func (ws *wsConn) writeFrame(op byte, data []byte) error {
	ws.wmu.Lock()
	defer ws.wmu.Unlock()
	hdr := make([]byte, 0, 10)
	hdr = append(hdr, 0x80|op)
	n := len(data)
	switch {
	case n < 126:
		hdr = append(hdr, byte(n))
	case n < 65536:
		hdr = append(hdr, 126, byte(n>>8), byte(n))
	default:
		hdr = append(hdr, 127)
		hdr = binary.BigEndian.AppendUint64(hdr, uint64(n))
	}
	_ = ws.c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := ws.c.Write(hdr); err != nil {
		return err
	}
	_, err := ws.c.Write(data)
	return err
}

// 1つのメッセージを読む (分割されたものはつなげる。ping には答える)
func (ws *wsConn) readMessage() (byte, []byte, error) {
	var msg []byte
	var msgOp byte
	for {
		var h [2]byte
		if _, err := io.ReadFull(ws.br, h[:]); err != nil {
			return 0, nil, err
		}
		fin := h[0]&0x80 != 0
		op := h[0] & 0x0F
		masked := h[1]&0x80 != 0
		n := uint64(h[1] & 0x7F)
		switch n {
		case 126:
			var b [2]byte
			if _, err := io.ReadFull(ws.br, b[:]); err != nil {
				return 0, nil, err
			}
			n = uint64(binary.BigEndian.Uint16(b[:]))
		case 127:
			var b [8]byte
			if _, err := io.ReadFull(ws.br, b[:]); err != nil {
				return 0, nil, err
			}
			n = binary.BigEndian.Uint64(b[:])
		}
		if !masked {
			return 0, nil, errors.New("unmasked frame")
		}
		if n > wsMaxMessage || uint64(len(msg))+n > wsMaxMessage {
			return 0, nil, errors.New("message too large")
		}
		var mask [4]byte
		if _, err := io.ReadFull(ws.br, mask[:]); err != nil {
			return 0, nil, err
		}
		payload := make([]byte, n)
		if _, err := io.ReadFull(ws.br, payload); err != nil {
			return 0, nil, err
		}
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
		switch op {
		case wsPing:
			_ = ws.writeFrame(wsPong, payload)
			continue
		case wsPong:
			continue
		case wsClose:
			_ = ws.writeFrame(wsClose, nil)
			return 0, nil, io.EOF
		case 0: // 続き
			msg = append(msg, payload...)
		default:
			msgOp = op
			msg = payload
		}
		if fin {
			return msgOp, msg, nil
		}
	}
}

// ---------------------------------------------------------------- 暗号化した通信路

type secureWS struct {
	ws     *wsConn
	key    [32]byte
	mu     sync.Mutex
	tx, rx uint64
}

const (
	dirFromBrowser = 1
	dirFromPC      = 2
)

func wsNonce(dir byte, n uint64) *[24]byte {
	var nn [24]byte
	nn[0] = dir
	binary.BigEndian.PutUint64(nn[1:9], n)
	return &nn
}

func deriveWebKey(webKey, salt []byte) [32]byte {
	h := sha512.New()
	h.Write(webKey)
	h.Write(salt)
	var k [32]byte
	copy(k[:], h.Sum(nil))
	return k
}

func (sw *secureWS) send(kind byte, data []byte) error {
	sw.mu.Lock()
	defer sw.mu.Unlock()
	sw.tx++
	plain := make([]byte, 0, len(data)+1)
	plain = append(plain, kind)
	plain = append(plain, data...)
	box := secretbox.Seal(nil, plain, wsNonce(dirFromPC, sw.tx), &sw.key)
	return sw.ws.writeFrame(wsBinary, box)
}

func (sw *secureWS) recv() (byte, []byte, error) {
	op, box, err := sw.ws.readMessage()
	if err != nil {
		return 0, nil, err
	}
	if op != wsBinary {
		return 0, nil, errors.New("unexpected message")
	}
	sw.rx++
	plain, ok := secretbox.Open(nil, box, wsNonce(dirFromBrowser, sw.rx), &sw.key)
	if !ok || len(plain) == 0 {
		return 0, nil, errBadBox
	}
	return plain[0], plain[1:], nil
}

var errBadBox = errors.New("復号できません (鍵が違うか、改ざんされています)")

// handleConn に渡すための接続 (相手のアドレスを本物にする)
type addrConn struct {
	net.Conn
	local, remote net.Addr
}

func (a addrConn) LocalAddr() net.Addr  { return a.local }
func (a addrConn) RemoteAddr() net.Addr { return a.remote }

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if s.blocked(ip) {
		http.Error(w, "blocked", http.StatusTooManyRequests)
		return
	}
	ws, err := wsAccept(w, r)
	if err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	defer guard("ブラウザ版")
	defer ws.c.Close()

	s.mu.Lock()
	key := decodeWebKey(s.cfg.WebKey)
	s.mu.Unlock()
	salt := make([]byte, 24)
	_, _ = rand.Read(salt)
	if err := ws.writeFrame(wsBinary, append([]byte("PPW1"), salt...)); err != nil {
		return
	}
	sw := &secureWS{ws: ws, key: deriveWebKey(key, salt)}

	// 最初のメッセージで鍵が正しいか確かめる
	_ = ws.c.SetReadDeadline(time.Now().Add(10 * time.Second))
	kind, first, err := sw.recv()
	if err != nil {
		if errors.Is(err, errBadBox) {
			s.addFail(ip)
			log.Printf("ブラウザ版: 鍵が違うため拒否しました (%s)", ip)
		}
		return
	}
	switch {
	case kind == 'L' && r.URL.Query().Get("media") == "":
		s.webMain(sw, ws, first)
	case kind == 'M' && r.URL.Query().Get("media") == "1":
		// 画面・音用の通信路 (操作とは別にして、大きなデータで操作が待たされないようにする)
		if subtle.ConstantTimeCompare(first, []byte(s.code())) != 1 {
			s.addFail(ip)
			_ = sw.send('L', []byte("NG code"))
			return
		}
		s.webMedia(sw, ws)
	}
}

// 操作用の通信路: 中身はアプリと同じ処理に任せる
func (s *server) webMain(sw *secureWS, ws *wsConn, first []byte) {
	a, b := net.Pipe()
	defer b.Close()
	go s.handleConn(addrConn{Conn: a, local: ws.c.LocalAddr(), remote: ws.c.RemoteAddr()})
	_ = b.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := b.Write(append(first, '\n')); err != nil {
		return
	}

	// PC → ブラウザ (コマンドの返事)
	go func() {
		defer ws.c.Close()
		sc := bufio.NewScanner(b)
		sc.Buffer(make([]byte, 64*1024), 4*1024*1024)
		for sc.Scan() {
			if err := sw.send('L', []byte(sc.Text())); err != nil {
				return
			}
		}
	}()

	for {
		// ブラウザは1秒ごとに PING を送ってくる
		_ = ws.c.SetReadDeadline(time.Now().Add(15 * time.Second))
		kind, data, err := sw.recv()
		if err != nil {
			return
		}
		if kind != 'L' || len(data) > 64*1024 {
			continue
		}
		_ = b.SetWriteDeadline(time.Now().Add(5 * time.Second))
		if _, err := b.Write(append(data, '\n')); err != nil {
			return
		}
	}
}

// 画面・音用の通信路
func (s *server) webMedia(sw *secureWS, ws *wsConn) {
	done := make(chan struct{})
	defer close(done)

	var screenBusy sync.Mutex
	var lastFrame uint64
	var audioStop chan struct{}
	stopAudio := func() {
		if audioStop != nil {
			close(audioStop)
			audioStop = nil
		}
	}
	defer stopAudio()

	for {
		_ = ws.c.SetReadDeadline(time.Now().Add(60 * time.Second))
		kind, data, err := sw.recv()
		if err != nil {
			return
		}
		switch kind {
		case 'S': // 画面 (前のが終わるまで次は撮らない)
			if !screenBusy.TryLock() {
				continue
			}
			var width, q int
			fmt.Sscanf(string(data), "%d %d", &width, &q)
			go func() {
				defer screenBusy.Unlock()
				defer guard("ブラウザ版の画面")
				jpg, err := screenJPEG(width, q)
				if err != nil {
					_ = sw.send('L', []byte("ER "+base64.StdEncoding.EncodeToString([]byte("画面を取得できません: "+err.Error()))))
					_ = sw.send('S', nil)
					return
				}
				// 前と同じ画面なら送らない (通信を減らして反応を速くする)
				h := fnv.New64a()
				h.Write(jpg)
				sum := h.Sum64()
				if sum == lastFrame {
					_ = sw.send('S', nil)
					return
				}
				lastFrame = sum
				_ = sw.send('S', jpg)
			}()

		case 'A': // PCの音: "1"=開始 "1m"=開始してPCのスピーカーは消す "0"=停止
			cmd := string(data)
			if strings.HasPrefix(cmd, "1") {
				stopAudio()
				ch, rate, err := hub.subscribe(cmd == "1m")
				if err != nil {
					_ = sw.send('L', []byte("ER "+base64.StdEncoding.EncodeToString([]byte("PCの音を取り込めません: "+err.Error()))))
					continue
				}
				stop := make(chan struct{})
				audioStop = stop
				_ = sw.send('a', []byte(fmt.Sprint(rate)))
				go func() {
					defer hub.unsubscribe(ch)
					// 細かく送るとブラウザ側の再生が途切れやすいので、約40ミリ秒ずつまとめる
					minBytes := rate * 4 * 40 / 1000
					var buf []byte
					for {
						select {
						case <-stop:
							return
						case <-done:
							return
						case pcm := <-ch:
							buf = append(buf, pcm...)
							if len(buf) < minBytes {
								continue
							}
							if err := sw.send('A', buf); err != nil {
								return
							}
							buf = nil
						}
					}
				}()
			} else {
				stopAudio()
			}
		}
	}
}
