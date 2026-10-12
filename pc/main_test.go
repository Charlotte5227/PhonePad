//go:build !windows

package main

import (
	"bufio"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"io"
	"math"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestTurbo(t *testing.T) {
	g := &gamepad{}
	// 設定が届く前(全部0)でも落ちないこと
	if r := g.compute(time.Unix(0, 0)); r != (xusbReport{}) {
		t.Fatalf("zero state: %+v", r)
	}
	base := time.Unix(0, 0)
	// 通常押し
	g.st = padState{held: xA, rate: 10}
	if r := g.compute(base); r.Buttons != xA {
		t.Fatalf("held A: %x", r.Buttons)
	}
	// 全体ターボ: 押した瞬間はON, 50ms後OFF, 100ms後ON (10回/秒)
	g.st = padState{held: xA | xStart, global: true, rate: 10}
	r0 := g.compute(base)
	r1 := g.compute(base.Add(60 * time.Millisecond))
	r2 := g.compute(base.Add(105 * time.Millisecond))
	if r0.Buttons != xA|xStart || r1.Buttons != xStart || r2.Buttons != xA|xStart {
		t.Fatalf("global turbo: %x %x %x", r0.Buttons, r1.Buttons, r2.Buttons)
	}
	// 個別自動連打: 押していなくても連打, トリガーも
	g.st = padState{auto: xRT, rate: 10}
	a0 := g.compute(base.Add(200 * time.Millisecond))
	a1 := g.compute(base.Add(260 * time.Millisecond))
	if a0.RTrigger != 255 || a1.RTrigger != 0 {
		t.Fatalf("auto RT: %d %d", a0.RTrigger, a1.RTrigger)
	}
}

func TestProtocol(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "TESTPC"}
	s.loadConfig()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handleConn(c)
		}
	}()

	// 間違ったコード
	c, _ := net.Dial("tcp", ln.Addr().String())
	c.Write([]byte("HELLO 000000x abc 1\n"))
	l, _ := bufio.NewReader(c).ReadString('\n')
	if !strings.HasPrefix(l, "NG") {
		t.Fatalf("expected NG got %q", l)
	}
	c.Close()

	c, _ = net.Dial("tcp", ln.Addr().String())
	br := bufio.NewReader(c)
	name := base64.StdEncoding.EncodeToString([]byte("テスト端末"))
	c.Write([]byte("HELLO " + s.code() + " " + name + " 1\n"))
	l, _ = br.ReadString('\n')
	if !strings.HasPrefix(l, "OK ") {
		t.Fatalf("expected OK got %q", l)
	}
	txt := base64.StdEncoding.EncodeToString([]byte("こんにちは😀"))
	cmds := []string{"MM 5 -3", "MB L 1", "MB L 1", "MB L 0", "MC R", "MW 120 0", "TX " + txt, "KY ENTER 2", "KY C63 1", "MB M 1"}
	for _, x := range cmds {
		c.Write([]byte(x + "\n"))
	}
	c.Write([]byte("PING\n"))
	l, _ = br.ReadString('\n')
	if strings.TrimSpace(l) != "PONG" {
		t.Fatalf("pong %q", l)
	}
	c.Write([]byte("GP 1000 0 0 10 32767 -32768 0 0\n"))
	l, _ = br.ReadString('\n')
	if strings.TrimSpace(l) != "GP OK" {
		t.Fatalf("gp %q", l)
	}
	time.Sleep(50 * time.Millisecond)
	c.Close() // 切断で中ボタンが離されるはず
	time.Sleep(100 * time.Millisecond)

	in.mu.Lock()
	got := strings.Join(in.out, "\n")
	in.mu.Unlock()
	want := []string{"move 5 -3", "button L true", "button L false", "button R true", "button R false",
		"wheel 120 0", "text こんにちは😀", "key ENTER 2", "combo c 1", "button M true", "button M false"}
	for _, w := range want {
		if !strings.Contains(got, w) {
			t.Errorf("missing %q in\n%s", w, got)
		}
	}
	if strings.Count(got, "button L true") != 1 {
		t.Errorf("duplicate L down should be ignored:\n%s", got)
	}
	// ゲームパッド: Aボタンとスティック、切断時にニュートラル
	var last xusbReport
	sawA := false
	for len(padLog) > 0 {
		last = <-padLog
		if last.Buttons == xA && last.LX == 32767 && last.LY == -32768 {
			sawA = true
		}
	}
	if !sawA || last != (xusbReport{}) {
		t.Errorf("gamepad sawA=%v last=%+v", sawA, last)
	}
}

func TestAudioAndAbs(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s.loadConfig()
	a, b := net.Pipe()
	go s.handleConn(a)
	br := bufio.NewReader(b)
	b.Write([]byte("HELLO " + s.code() + " eA== 1\n"))
	if l, _ := br.ReadString('\n'); !strings.HasPrefix(l, "OK") {
		t.Fatal(l)
	}
	b.Write([]byte("MA 70000 -5\nVS 75\n"))
	l, _ := br.ReadString('\n')
	if !strings.HasPrefix(l, "VI ") {
		t.Fatalf("VI expected: %q", l)
	}
	raw, _ := base64.StdEncoding.DecodeString(strings.TrimSpace(l[3:]))
	if !strings.Contains(string(raw), `"vol":75`) {
		t.Fatalf("volume not applied: %s", raw)
	}
	b.Write([]byte("VD " + base64.StdEncoding.EncodeToString([]byte("{hp}")) + "\n"))
	l, _ = br.ReadString('\n')
	raw, _ = base64.StdEncoding.DecodeString(strings.TrimSpace(l[3:]))
	if !strings.Contains(string(raw), `"id":"{hp}","name":"ヘッドホン","default":true`) {
		t.Fatalf("default not switched: %s", raw)
	}
	b.Close()
	time.Sleep(50 * time.Millisecond)
	in.mu.Lock()
	got := strings.Join(in.out, "\n")
	in.mu.Unlock()
	if !strings.Contains(got, "abs 65535 0") {
		t.Fatalf("abs not clamped:\n%s", got)
	}
}

func TestScreenAuth(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s.loadConfig()
	ts := httptest.NewServer(s.screenMux())
	defer ts.Close()
	get := func(code string) (int, string, int) {
		req, _ := http.NewRequest("GET", ts.URL+"/frame?w=640&q=50", nil)
		req.Header.Set("X-Code", code)
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer r.Body.Close()
		body, _ := io.ReadAll(r.Body)
		return r.StatusCode, r.Header.Get("Content-Type"), len(body)
	}
	if st, _, _ := get("000000x"); st != 403 {
		t.Fatalf("wrong code should be 403, got %d", st)
	}
	st, ct, n := get(s.code())
	if st != 200 || ct != "image/jpeg" || n < 500 {
		t.Fatalf("frame: %d %s %d", st, ct, n)
	}
}

// 暗号化した接続で、証明書の指紋がQRコードのものと一致すること
func TestTLSFingerprint(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s.loadConfig()
	if len(s.certFP) != 64 {
		t.Fatalf("fingerprint: %q", s.certFP)
	}
	// 2回目の起動でも同じ証明書を使う
	s2 := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s2.loadConfig()
	if s2.certFP != s.certFP {
		t.Fatal("certificate changed between runs")
	}
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			s.handleConn(tls.Server(c, s.tlsConfig()))
		}
	}()
	c, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{InsecureSkipVerify: true})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if fp := fingerprint(c.ConnectionState().PeerCertificates[0].Raw); fp != s.certFP {
		t.Fatalf("peer fp %s != %s", fp, s.certFP)
	}
	c.Write([]byte("HELLO " + s.code() + " eA== 1\nSQ\nAL\nRUN " + base64.StdEncoding.EncodeToString([]byte("https://example.com")) + "\n"))
	br := bufio.NewReader(c)
	got := map[string]string{}
	for i := 0; i < 5 && len(got) < 3; i++ {
		l, err := br.ReadString('\n')
		if err != nil {
			break
		}
		f := strings.SplitN(strings.TrimSpace(l), " ", 2)
		got[f[0]] = f[len(f)-1]
	}
	if _, ok := got["OK"]; !ok {
		t.Fatalf("no OK: %v", got)
	}
	si, _ := base64.StdEncoding.DecodeString(got["SI"])
	ap, _ := base64.StdEncoding.DecodeString(got["AP"])
	if !strings.Contains(string(si), `"cpuName":"Test CPU"`) || !strings.Contains(string(ap), "メモ帳") {
		t.Fatalf("SI=%s AP=%s", si, ap)
	}
	time.Sleep(50 * time.Millisecond)
	if len(testLaunched) != 1 || testLaunched[0] != "https://example.com" {
		t.Fatalf("launch: %v", testLaunched)
	}
}

func TestCaretSteps(t *testing.T) {
	if n := caretSteps("ab\r\ncd😀e", 4); n != 3 { // a b (改行) → 3回
		t.Fatalf("crlf: %d", n)
	}
	if n := caretSteps("ab\r\ncd😀e", 8); n != 6 { // a b 改行 c d 😀
		t.Fatalf("surrogate: %d", n)
	}
	if n := caretSteps("abc", 99); n != 3 {
		t.Fatalf("clamp: %d", n)
	}
}

func TestFieldClipPowerAutostartRumble(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s.loadConfig()
	a, b := net.Pipe()
	go s.handleConn(a)
	br := bufio.NewReader(b)
	b.Write([]byte("HELLO " + s.code() + " eA== 1\n"))
	br.ReadString('\n')
	read := func(prefix string) string {
		for i := 0; i < 5; i++ {
			l, _ := br.ReadString('\n')
			l = strings.TrimSpace(l)
			if strings.HasPrefix(l, prefix) {
				return strings.TrimSpace(strings.TrimPrefix(l, prefix))
			}
		}
		t.Fatalf("no %s", prefix)
		return ""
	}
	dec := func(x string) string { r, _ := base64.StdEncoding.DecodeString(x); return string(r) }

	b.Write([]byte("CS 5\n"))
	if f := dec(read("TF ")); !strings.Contains(f, `"caret":5`) || !strings.Contains(f, `一行目`) {
		t.Fatalf("field: %s", f)
	}
	b.Write([]byte("CB " + base64.StdEncoding.EncodeToString([]byte("コピー文字")) + "\n"))
	read("CK")
	b.Write([]byte("CQ\n"))
	if c := dec(read("CT ")); c != "コピー文字" {
		t.Fatalf("clip: %q", c)
	}
	b.Write([]byte("PW LOCK\nAS 1\n"))
	if v := read("AI "); v != "1" {
		t.Fatalf("autostart: %s", v)
	}
	if len(testPower) != 1 || testPower[0] != "LOCK" {
		t.Fatalf("power: %v", testPower)
	}
	b.Write([]byte("GP 0 0 0 10 0 0 0 0\n"))
	read("GP OK")
	go testRumble(200, 30)
	if v := read("RU "); v != "200 30" {
		t.Fatalf("rumble: %s", v)
	}
	b.Close()
}

func TestAudioConvert(t *testing.T) {
	// float32 ステレオ 2フレーム: (0.5,-0.5) (1.5,-1.5→クリップ)
	raw := make([]byte, 16)
	put := func(i int, v float32) { binary.LittleEndian.PutUint32(raw[i*4:], math.Float32bits(v)) }
	put(0, 0.5)
	put(1, -0.5)
	put(2, 1.5)
	put(3, -1.5)
	out := toS16Stereo(raw, 2, 2, 32, true, 1)
	got := []int16{}
	for i := 0; i+1 < len(out); i += 2 {
		got = append(got, int16(binary.LittleEndian.Uint16(out[i:])))
	}
	if len(got) != 4 || got[0] != 16383 || got[1] != -16383 || got[2] != 32767 || got[3] != -32767 {
		t.Fatalf("convert: %v", got)
	}
	// モノラル16bit → 左右同じ
	mono := []byte{0x00, 0x40}
	o2 := toS16Stereo(mono, 1, 1, 16, false, 1)
	if len(o2) != 4 || o2[0] != o2[2] || o2[1] != o2[3] {
		t.Fatalf("mono: %v", o2)
	}
	if decimation(192000) != 4 || decimation(96000) != 2 || decimation(48000) != 1 || decimation(44100) != 1 {
		t.Fatal("decimation")
	}
}

func TestAudioStream(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s := &server{sessions: map[*session]struct{}{}, fails: map[string][]time.Time{}, pcName: "T"}
	s.loadConfig()
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handleAudio(tls.Server(c, s.tlsConfig()))
		}
	}()
	c, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{InsecureSkipVerify: true})
	if err != nil {
		t.Fatal(err)
	}
	c.Write([]byte("AUDIO " + s.code() + "\n"))
	br := bufio.NewReader(c)
	l, _ := br.ReadString('\n')
	if strings.TrimSpace(l) != "OK 48000 2" {
		t.Fatalf("header %q", l)
	}
	buf := make([]byte, 3840*2)
	n, err := io.ReadFull(br, buf)
	if err != nil || n != len(buf) {
		t.Fatalf("pcm: %d %v", n, err)
	}
	c.Close()
	time.Sleep(100 * time.Millisecond)
	hub.mu.Lock()
	running := hub.stopCap != nil
	hub.mu.Unlock()
	if running {
		t.Fatal("capture should stop when nobody listens")
	}
}
