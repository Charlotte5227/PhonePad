//go:build !windows

// Windows以外ではテスト用に入力内容をログへ出すだけ
package main

import (
	"fmt"
	"image"
	"image/color"
	"log"
	"os"
	"sync"
	"time"
)

type logInput struct {
	mu  sync.Mutex
	out []string
}

var in = &logInput{}

func (l *logInput) rec(s string) {
	l.mu.Lock()
	l.out = append(l.out, s)
	l.mu.Unlock()
	if os.Getenv("PHONEPAD_VERBOSE") != "" {
		log.Println("[input]", s)
	}
}

func (l *logInput) mouseMove(dx, dy int)         { l.rec(fmt.Sprintf("move %d %d", dx, dy)) }
func (l *logInput) mouseButton(b byte, d bool)   { l.rec(fmt.Sprintf("button %c %v", b, d)) }
func (l *logInput) mouseWheel(v, h int)          { l.rec(fmt.Sprintf("wheel %d %d", v, h)) }
func (l *logInput) typeText(s string)            { l.rec("text " + s) }
func (l *logInput) charCombo(r rune, mods int)   { l.rec(fmt.Sprintf("combo %c %d", r, mods)) }
func (l *logInput) releaseModifiers()            {}
func (l *logInput) keyTap(n string, m int) error { l.rec(fmt.Sprintf("key %s %d", n, m)); return nil }

func setupConsole(bool) bool { return true }
func openURL(string)         {}
func shellOpen(string) error { return nil }

type logPad struct{}

var padLog = make(chan xusbReport, 4096)

func (logPad) update(r xusbReport) error {
	select {
	case padLog <- r:
	default:
	}
	return nil
}
func (logPad) close() {}

func initGamepadSystem()        {}
func gamepadReady() bool        { return true }
func gamepadStatusText() string { return "テスト用" }
func openPadSink(onRumble func(large, small byte)) (padSink, error) {
	testRumble = onRumble
	return logPad{}, nil
}

var testRumble func(large, small byte)

func (l *logInput) mouseAbs(x, y int) { l.rec(fmt.Sprintf("abs %d %d", x, y)) }

func setupDPI() {}

func captureScreen(maxW int) (*image.RGBA, error) {
	if maxW <= 0 {
		maxW = 960
	}
	img := image.NewRGBA(image.Rect(0, 0, maxW, maxW*9/16))
	for i := range img.Pix {
		img.Pix[i] = 128
	}
	img.SetRGBA(0, 0, color.RGBA{255, 0, 0, 255})
	drawCursorMark(img, maxW/2, maxW*9/32)
	return img, nil
}

var testAudio = audioInfo{Volume: 40, Devices: []audioDevice{{ID: "{spk}", Name: "スピーカー", Default: true}, {ID: "{hp}", Name: "ヘッドホン"}}}

var testAudioMu sync.Mutex

func getAudioInfo() audioInfo {
	testAudioMu.Lock()
	defer testAudioMu.Unlock()
	a := testAudio
	a.Devices = append([]audioDevice(nil), testAudio.Devices...)
	return a
}
func setAudioVolume(p int) error {
	testAudioMu.Lock()
	defer testAudioMu.Unlock()
	testAudio.Volume = p
	return nil
}
func setAudioMute(m bool) error {
	testAudioMu.Lock()
	defer testAudioMu.Unlock()
	testAudio.Mute = m
	return nil
}
func setAudioDefault(id string) error {
	testAudioMu.Lock()
	defer testAudioMu.Unlock()
	for i := range testAudio.Devices {
		testAudio.Devices[i].Default = testAudio.Devices[i].ID == id
	}
	return nil
}

func (l *logInput) moveCaretByKeys(n int) { l.rec(fmt.Sprintf("caretkeys %d", n)) }

var testField = fieldState{OK: true, Text: "一行目\r\n二行目", Caret: 3, SelEnd: 3}

func readFocusedField() fieldState { return testField }
func setFocusedCaret(n int) error {
	testField.Caret = n
	testField.SelEnd = n
	return nil
}

var testClip = ""

func getClipboardText() (string, error) { return testClip, nil }
func setClipboardText(s string) error {
	testClip = s
	return nil
}

var testPower []string

func powerAction(a string) error {
	testPower = append(testPower, a)
	return nil
}

var testAutostart bool

func autostartEnabled() bool { return testAutostart }
func setAutostart(on bool) error {
	testAutostart = on
	return nil
}
func refreshAutostartPath() {}

func getSysInfo() sysInfo {
	return sysInfo{CPUName: "Test CPU", CPU: 12.5, MemUsed: 4 << 30, MemTotal: 16 << 30, Battery: -1, Uptime: 3600}
}

func listApps() []appEntry {
	return []appEntry{{Name: "メモ帳", Path: "C:/notepad.lnk"}, {Name: "Steam", Path: "C:/steam.lnk"}}
}

var testLaunched []string

func launch(t string) error {
	testLaunched = append(testLaunched, t)
	return nil
}

// テスト用: 440Hz の音を出し続ける
func startLoopback(onData func([]byte)) (int, func(), error) {
	stop := make(chan struct{})
	go func() {
		raw := make([]byte, 960*4*2) // float32 ステレオ 20ms
		for {
			select {
			case <-stop:
				return
			case <-time.After(20 * time.Millisecond):
			}
			onData(toS16Stereo(raw, 960, 2, 32, true, 1))
		}
	}()
	return 48000, func() { close(stop) }, nil
}

func cleanupOldExe() {}

var testUpdated []string

func selfUpdate(url string) error {
	testUpdated = append(testUpdated, url)
	return nil
}
