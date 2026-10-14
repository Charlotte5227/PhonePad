package main

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 修飾キーのビット
const (
	modCtrl  = 1
	modShift = 2
	modAlt   = 4
	modWin   = 8
)

type session struct {
	srv        *server
	conn       net.Conn
	remoteIP   string
	deviceName string
	id         int       // 状態ページで端末を指定するための番号
	since      time.Time // 接続した時刻

	wmu     sync.Mutex
	fieldMu sync.Mutex    // 入力欄の問い合わせを1つずつ処理する
	pressed map[byte]bool // 押しっぱなしのマウスボタン
	pad     *gamepad

	// 設定のやりとり (srv.mu で守る)
	devID string
	cfgOK bool
}

func (s *server) handleConn(c net.Conn) {
	defer guard("通信")
	ip, _, _ := net.SplitHostPort(c.RemoteAddr().String())
	ss := &session{srv: s, conn: c, remoteIP: ip, pressed: map[byte]bool{}}
	defer c.Close()

	if s.blocked(ip) {
		ss.send("NG blocked")
		return
	}

	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)

	// 最初の1行は認証
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	if !sc.Scan() {
		return
	}
	f := strings.Fields(sc.Text())
	if len(f) < 2 || f[0] != "HELLO" {
		return
	}
	if f[1] != s.code() {
		s.addFail(ip)
		ss.send("NG code")
		log.Printf("接続拒否 (コード不一致): %s", ip)
		name := ""
		if len(f) >= 3 {
			if b, err := base64.StdEncoding.DecodeString(f[2]); err == nil {
				name = truncate(string(b), 60)
			}
		}
		s.hist.add("reject", name, ip, "")
		return
	}
	ss.deviceName = "スマホ"
	if len(f) >= 3 {
		if b, err := base64.StdEncoding.DecodeString(f[2]); err == nil && len(b) > 0 {
			ss.deviceName = string(b)
		}
	}
	ss.send("OK " + base64.StdEncoding.EncodeToString([]byte(s.pcName)) + " " + protoVersion + " " + appVersion)
	// スマホから電源を入れられるように、このPCのMACアドレスを伝える
	if mac := macForAddr(c.LocalAddr()); mac != "" {
		ss.send("MI " + mac)
	}

	s.mu.Lock()
	s.nextID++
	ss.id = s.nextID
	ss.since = time.Now()
	s.sessions[ss] = struct{}{}
	s.mu.Unlock()
	log.Printf("接続しました: %s (%s)", ss.deviceName, ip)
	s.hist.add("connect", ss.deviceName, ip, "")
	guiRefresh()

	defer func() {
		ss.releaseAll()
		if ss.pad != nil {
			ss.pad.close()
		}
		s.hist.add("disconnect", ss.deviceName, ip, durText(time.Since(ss.since)))
		s.mu.Lock()
		delete(s.sessions, ss)
		s.mu.Unlock()
		log.Printf("切断しました: %s (%s)", ss.deviceName, ip)
		guiRefresh()
	}()

	for {
		// スマホは1秒ごとにPINGを送る。4秒途絶えたら切断扱いにして入力を全部離す
		_ = c.SetReadDeadline(time.Now().Add(4 * time.Second))
		if !sc.Scan() {
			return
		}
		ss.handleLine(sc.Text())
	}
}

func (ss *session) send(line string) {
	ss.wmu.Lock()
	defer ss.wmu.Unlock()
	_ = ss.conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_, _ = ss.conn.Write([]byte(line + "\n"))
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

func hexu(s string) uint32 {
	n, _ := strconv.ParseUint(s, 16, 32)
	return uint32(n)
}

func (ss *session) handleLine(line string) {
	f := strings.Fields(line)
	if len(f) == 0 {
		return
	}
	switch f[0] {
	case "PING":
		ss.send("PONG")

	case "MM": // マウス移動 dx dy
		if len(f) >= 3 {
			in.mouseMove(atoi(f[1]), atoi(f[2]))
		}

	case "MA": // 画面上の位置へ移動 (0〜65535)
		if len(f) >= 3 {
			in.mouseAbs(clampInt(atoi(f[1]), 0, 65535), clampInt(atoi(f[2]), 0, 65535))
		}

	case "TQ": // PCで入力中の欄の内容を問い合わせ
		go ss.sendField(nil)

	case "CS": // 入力中の欄のカーソル位置を変える
		if len(f) >= 2 {
			n := atoi(f[1])
			go ss.sendField(func() error { return setFocusedCaret(n) })
		}

	case "CB": // スマホのクリップボードの内容をPCへ
		if len(f) >= 2 {
			if b, err := base64.StdEncoding.DecodeString(f[1]); err == nil {
				if err := setClipboardText(string(b)); err != nil {
					ss.send("ER " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
				} else {
					ss.send("CK")
				}
			}
		} else {
			_ = setClipboardText("")
			ss.send("CK")
		}

	case "CQ": // PCのクリップボードの内容をスマホへ
		go func() {
			defer guard("クリップボード")
			t, err := getClipboardText()
			if err != nil {
				ss.send("ER " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
				return
			}
			ss.send("CT " + base64.StdEncoding.EncodeToString([]byte(t)))
		}()

	case "PW": // 電源操作
		if len(f) >= 2 {
			log.Printf("電源操作: %s (%s)", f[1], ss.deviceName)
			ss.srv.hist.add("power", ss.deviceName, ss.remoteIP, f[1])
			if err := powerAction(f[1]); err != nil {
				ss.send("ER " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
			}
		}

	case "UP": // スマホから PC側ソフトの更新
		log.Printf("スマホから更新の指示 (%s)", ss.deviceName)
		if err := startSelfUpdate(); err != nil {
			ss.send("ER " + base64.StdEncoding.EncodeToString([]byte("PCを更新できません: "+err.Error())))
		}

	case "SP": // PCに接続用の画面 (QRコードなど) を表示する
		log.Printf("スマホからPCの画面を表示 (%s)", ss.deviceName)
		openManager()

	case "AQ": // 自動起動の状態
		ss.sendAutostart()

	case "AS": // 自動起動の切り替え
		if len(f) >= 2 {
			if err := setAutostart(f[1] == "1"); err != nil {
				ss.send("ER " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
			}
			ss.sendAutostart()
		}

	case "RUN": // アプリ・ファイル・URL を開く
		if len(f) >= 2 {
			if b, err := base64.StdEncoding.DecodeString(f[1]); err == nil {
				t := string(b)
				log.Printf("起動: %s (%s)", t, ss.deviceName)
				if err := launch(t); err != nil {
					ss.send("ER " + base64.StdEncoding.EncodeToString([]byte("開けませんでした: "+err.Error())))
				}
			}
		}

	case "AL": // スタートメニューのアプリ一覧
		go func() {
			defer guard("アプリ一覧")
			b, _ := json.Marshal(listApps())
			ss.send("AP " + base64.StdEncoding.EncodeToString(b))
		}()

	case "SQ": // PCの状態
		go func() {
			defer guard("PCの状態")
			b, _ := json.Marshal(getSysInfo())
			ss.send("SI " + base64.StdEncoding.EncodeToString(b))
		}()

	case "VQ": // 音量の状態を問い合わせ
		go ss.sendAudio(nil)

	case "VS": // 音量 0〜100
		if len(f) >= 2 {
			v := atoi(f[1])
			go ss.sendAudio(func() error { return setAudioVolume(v) })
		}

	case "VM": // ミュート
		if len(f) >= 2 {
			m := f[1] == "1"
			go ss.sendAudio(func() error { return setAudioMute(m) })
		}

	case "VD": // 出力先の切り替え
		if len(f) >= 2 {
			if b, err := base64.StdEncoding.DecodeString(f[1]); err == nil {
				id := string(b)
				go ss.sendAudio(func() error { return setAudioDefault(id) })
			}
		}

	case "MB": // マウスボタン 押す/離す
		if len(f) >= 3 && len(f[1]) == 1 {
			b := f[1][0]
			down := f[2] == "1"
			if ss.pressed[b] == down {
				return
			}
			ss.pressed[b] = down
			in.mouseButton(b, down)
		}

	case "MC": // クリック
		if len(f) >= 2 && len(f[1]) == 1 {
			b := f[1][0]
			if ss.pressed[b] {
				in.mouseButton(b, false)
				ss.pressed[b] = false
			}
			in.mouseButton(b, true)
			in.mouseButton(b, false)
		}

	case "MW": // ホイール 縦 横 (120で1ノッチ)
		if len(f) >= 3 {
			in.mouseWheel(atoi(f[1]), atoi(f[2]))
		}

	case "TX": // 文字入力 (UTF-8をBase64で)
		if len(f) >= 2 {
			if b, err := base64.StdEncoding.DecodeString(f[1]); err == nil {
				in.typeText(string(b))
			}
		}

	case "KY": // 特殊キー / 修飾付き文字
		if len(f) >= 2 {
			mods := 0
			if len(f) >= 3 {
				mods = atoi(f[2])
			}
			k := f[1]
			if strings.HasPrefix(k, "C") && len(k) > 1 {
				cp, err := strconv.ParseUint(k[1:], 16, 32)
				if err == nil {
					in.charCombo(rune(cp), mods)
				}
				return
			}
			if err := in.keyTap(k, mods); err != nil {
				log.Println("キー:", err)
			}
		}

	case "GP": // ゲームパッド held auto global rate lx ly rx ry
		if len(f) >= 9 {
			if ss.pad == nil {
				p, err := newGamepad(func(large, small byte) {
					// ゲームの振動をスマホへ
					ss.send(fmt.Sprintf("RU %d %d", large, small))
				})
				if err != nil {
					ss.send("GP ERR " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
					log.Println("コントローラー:", err)
					// 毎回エラーを出さないよう、空のパッドを置く
					ss.pad = &gamepad{dead: true}
					return
				}
				ss.pad = p
				ss.send("GP OK")
				log.Printf("仮想コントローラーを接続しました (%s)", ss.deviceName)
			}
			ss.pad.setState(padState{
				held:   hexu(f[1]),
				auto:   hexu(f[2]),
				global: f[3] == "1",
				rate:   atoi(f[4]),
				lx:     clamp16(atoi(f[5])),
				ly:     clamp16(atoi(f[6])),
				rx:     clamp16(atoi(f[7])),
				ry:     clamp16(atoi(f[8])),
			})
		}

	case "GX": // コントローラー画面を離れた: 全ボタンを離す
		if ss.pad != nil {
			ss.pad.setState(padState{})
		}
		if ss.pad != nil && ss.pad.dead {
			ss.pad = nil // 次回もう一度ドライバーを確認する
		}

	case "CFG": // スマホの設定 (つないだ直後)
		if len(f) >= 2 {
			ss.handleCFG(f[1])
		}

	case "CFK": // スマホで設定を変えた
		if len(f) >= 2 {
			ss.handleCFK(f[1])
		}

	case "CPK": // アプリごとの配置をスマホで変えた
		if len(f) >= 2 {
			ss.handleCPK(f[1])
		}

	case "RA": // 全部離す (アプリがバックグラウンドへ)
		ss.releaseAll()
		if ss.pad != nil {
			ss.pad.setState(padState{})
		}

	default:
		log.Println("不明なコマンド:", truncate(line, 40))
	}
}

func (ss *session) releaseAll() {
	for b, down := range ss.pressed {
		if down {
			in.mouseButton(b, false)
			ss.pressed[b] = false
		}
	}
	in.releaseModifiers()
}

// 入力中の欄の内容をスマホへ返す (op があれば先に実行)
func (ss *session) sendField(op func() error) {
	defer guard("入力欄")
	ss.fieldMu.Lock()
	defer ss.fieldMu.Unlock()
	if op != nil {
		if err := op(); err != nil {
			ss.send("ER " + base64.StdEncoding.EncodeToString([]byte(err.Error())))
		}
	}
	st := readFocusedField()
	b, _ := json.Marshal(st)
	ss.send("TF " + base64.StdEncoding.EncodeToString(b))
}

func (ss *session) sendAutostart() {
	if autostartEnabled() {
		ss.send("AI 1")
	} else {
		ss.send("AI 0")
	}
}

// 音量操作をしてから、最新の状態をスマホへ返す
func (ss *session) sendAudio(op func() error) {
	defer guard("音量")
	var opErr error
	if op != nil {
		opErr = op()
		if opErr != nil {
			log.Println("音量:", opErr)
		}
	}
	info := getAudioInfo()
	if opErr != nil && info.Error == "" {
		info.Error = opErr.Error()
	}
	b, _ := json.Marshal(info)
	ss.send("VI " + base64.StdEncoding.EncodeToString(b))
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func clamp16(v int) int16 {
	if v > 32767 {
		return 32767
	}
	if v < -32768 {
		return -32768
	}
	return int16(v)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return fmt.Sprintf("%s...", s[:n])
}
