//go:build windows

package main

import (
	"fmt"
	"strings"
	"sync"
	"syscall"
	"unicode/utf16"
	"unsafe"
)

var (
	user32           = syscall.NewLazyDLL("user32.dll")
	procSendInput    = user32.NewProc("SendInput")
	procMapVirtualKy = user32.NewProc("MapVirtualKeyW")
	procVkKeyScanW   = user32.NewProc("VkKeyScanW")
)

const (
	inputMouse    = 0
	inputKeyboard = 1

	mouseMove       = 0x0001
	mouseLeftDown   = 0x0002
	mouseLeftUp     = 0x0004
	mouseRightDown  = 0x0008
	mouseRightUp    = 0x0010
	mouseMiddleDown = 0x0020
	mouseMiddleUp   = 0x0040
	mouseWheel      = 0x0800
	mouseHWheel     = 0x1000

	keyExtended = 0x0001
	keyUp       = 0x0002
	keyUnicode  = 0x0004
)

type mouseInputT struct {
	Dx, Dy    int32
	MouseData uint32
	Flags     uint32
	Time      uint32
	Extra     uintptr
}

type keyInputT struct {
	Vk    uint16
	Scan  uint16
	Flags uint32
	Time  uint32
	Extra uintptr
	_     [8]byte // MOUSEINPUTと同じ大きさにそろえる
}

type inputM struct {
	Type uint32
	Mi   mouseInputT
}

type inputK struct {
	Type uint32
	Ki   keyInputT
}

func init() {
	// 構造体の大きさがWindowsのINPUT(40バイト)と一致しているか確認
	if unsafe.Sizeof(inputM{}) != 40 || unsafe.Sizeof(inputK{}) != 40 {
		panic(fmt.Sprintf("INPUT size mismatch: %d %d", unsafe.Sizeof(inputM{}), unsafe.Sizeof(inputK{})))
	}
}

type winInput struct {
	mu sync.Mutex
}

var in = &winInput{}

func sendMouse(ms ...mouseInputT) {
	if len(ms) == 0 {
		return
	}
	arr := make([]inputM, len(ms))
	for i, m := range ms {
		arr[i] = inputM{Type: inputMouse, Mi: m}
	}
	procSendInput.Call(uintptr(len(arr)), uintptr(unsafe.Pointer(&arr[0])), unsafe.Sizeof(arr[0]))
}

func sendKeys(ks []keyInputT) {
	if len(ks) == 0 {
		return
	}
	arr := make([]inputK, len(ks))
	for i, k := range ks {
		arr[i] = inputK{Type: inputKeyboard, Ki: k}
	}
	procSendInput.Call(uintptr(len(arr)), uintptr(unsafe.Pointer(&arr[0])), unsafe.Sizeof(arr[0]))
}

func (w *winInput) mouseMove(dx, dy int) {
	if dx == 0 && dy == 0 {
		return
	}
	sendMouse(mouseInputT{Dx: int32(dx), Dy: int32(dy), Flags: mouseMove})
}

// 画面上の位置へ移動 (0〜65535 で正規化したメインモニター上の座標)
func (w *winInput) mouseAbs(x, y int) {
	sendMouse(mouseInputT{Dx: int32(x), Dy: int32(y), Flags: mouseMove | 0x8000 /* ABSOLUTE */})
}

func (w *winInput) mouseButton(b byte, down bool) {
	var f uint32
	switch b {
	case 'L':
		f = mouseLeftUp
		if down {
			f = mouseLeftDown
		}
	case 'R':
		f = mouseRightUp
		if down {
			f = mouseRightDown
		}
	case 'M':
		f = mouseMiddleUp
		if down {
			f = mouseMiddleDown
		}
	default:
		return
	}
	sendMouse(mouseInputT{Flags: f})
}

func (w *winInput) mouseWheel(v, h int) {
	var ms []mouseInputT
	if v != 0 {
		ms = append(ms, mouseInputT{MouseData: uint32(int32(v)), Flags: mouseWheel})
	}
	if h != 0 {
		ms = append(ms, mouseInputT{MouseData: uint32(int32(h)), Flags: mouseHWheel})
	}
	sendMouse(ms...)
}

// ---- キーボード ----

var keyNames = map[string]uint16{
	"ENTER": 0x0D, "BACK": 0x08, "TAB": 0x09, "ESC": 0x1B, "SPACE": 0x20,
	"PGUP": 0x21, "PGDN": 0x22, "END": 0x23, "HOME": 0x24,
	"LEFT": 0x25, "UP": 0x26, "RIGHT": 0x27, "DOWN": 0x28,
	"PRTSC": 0x2C, "INS": 0x2D, "DEL": 0x2E, "WIN": 0x5B, "MENU": 0x5D,
	"F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73, "F5": 0x74, "F6": 0x75,
	"F7": 0x76, "F8": 0x77, "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
	"KANJI": 0x19, // 半角/全角
	// メディアキー
	"VOLUP": 0xAF, "VOLDOWN": 0xAE, "VOLMUTE": 0xAD,
	"MNEXT": 0xB0, "MPREV": 0xB1, "MSTOP": 0xB2, "MPLAY": 0xB3,
	"CTRL": 0xA2, "SHIFT": 0xA0, "ALT": 0xA4,
}

var extendedVK = map[uint16]bool{
	0x21: true, 0x22: true, 0x23: true, 0x24: true, 0x25: true, 0x26: true, 0x27: true, 0x28: true,
	0x2C: true, 0x2D: true, 0x2E: true, 0x5B: true, 0x5D: true,
	0xAD: true, 0xAE: true, 0xAF: true, 0xB0: true, 0xB1: true, 0xB2: true, 0xB3: true,
}

func vkEvent(vk uint16, up bool) keyInputT {
	sc, _, _ := procMapVirtualKy.Call(uintptr(vk), 0)
	k := keyInputT{Vk: vk, Scan: uint16(sc)}
	if extendedVK[vk] {
		k.Flags |= keyExtended
	}
	if up {
		k.Flags |= keyUp
	}
	return k
}

func modVKs(mods int) []uint16 {
	var v []uint16
	if mods&modCtrl != 0 {
		v = append(v, 0xA2)
	}
	if mods&modShift != 0 {
		v = append(v, 0xA0)
	}
	if mods&modAlt != 0 {
		v = append(v, 0xA4)
	}
	if mods&modWin != 0 {
		v = append(v, 0x5B)
	}
	return v
}

// 修飾キーを押した状態で vk を1回押す
func comboEvents(vk uint16, mods int) []keyInputT {
	var ks []keyInputT
	mv := modVKs(mods)
	for _, m := range mv {
		ks = append(ks, vkEvent(m, false))
	}
	ks = append(ks, vkEvent(vk, false), vkEvent(vk, true))
	for i := len(mv) - 1; i >= 0; i-- {
		ks = append(ks, vkEvent(mv[i], true))
	}
	return ks
}

func (w *winInput) keyTap(name string, mods int) error {
	vk, ok := keyNames[strings.ToUpper(name)]
	if !ok {
		return fmt.Errorf("不明なキー %q", name)
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	sendKeys(comboEvents(vk, mods))
	return nil
}

// Ctrl+C のように修飾キー付きで文字キーを押す
func (w *winInput) charCombo(r rune, mods int) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if r < 0x10000 {
		res, _, _ := procVkKeyScanW.Call(uintptr(uint16(r)))
		v := int16(res)
		if v != -1 {
			vk := uint16(v & 0xFF)
			sh := (v >> 8) & 0xFF
			if sh&1 != 0 {
				mods |= modShift
			}
			if sh&2 != 0 {
				mods |= modCtrl
			}
			if sh&4 != 0 {
				mods |= modAlt
			}
			sendKeys(comboEvents(vk, mods))
			return
		}
	}
	// キーボード配列に無い文字は修飾なしでそのまま入力
	ime.suspend()
	sendKeys(unicodeEvents(string(r)))
}

func unicodeEvents(s string) []keyInputT {
	var ks []keyInputT
	for _, r := range s {
		switch r {
		case '\n':
			ks = append(ks, vkEvent(0x0D, false), vkEvent(0x0D, true))
			continue
		case '\r':
			continue
		case '\t':
			ks = append(ks, vkEvent(0x09, false), vkEvent(0x09, true))
			continue
		}
		var units []uint16
		if r >= 0x10000 {
			a, b := utf16.EncodeRune(r)
			units = []uint16{uint16(a), uint16(b)}
		} else {
			units = []uint16{uint16(r)}
		}
		for _, u := range units {
			ks = append(ks, keyInputT{Scan: u, Flags: keyUnicode})
		}
		for _, u := range units {
			ks = append(ks, keyInputT{Scan: u, Flags: keyUnicode | keyUp})
		}
	}
	return ks
}

func (w *winInput) typeText(s string) {
	w.mu.Lock()
	defer w.mu.Unlock()
	ime.suspend()
	ks := unicodeEvents(s)
	// 長い文字列はまとめすぎるとアプリが取りこぼすことがあるので分割
	for len(ks) > 0 {
		n := len(ks)
		if n > 64 {
			n = 64
		}
		sendKeys(ks[:n])
		ks = ks[n:]
	}
}

// 修飾キーは1回の送信の中で押して離すため、押しっぱなしにはならない。
// (使っていない修飾キーにKEYUPを送るとAltメニューが開くなどの副作用があるので何もしない)
func (w *winInput) releaseModifiers() {}

// Ctrl+Home で先頭へ行き、右矢印を n 回押す
func (w *winInput) moveCaretByKeys(n int) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if n > 5000 {
		n = 5000
	}
	sendKeys(comboEvents(0x24 /* HOME */, modCtrl))
	var ks []keyInputT
	for i := 0; i < n; i++ {
		ks = append(ks, vkEvent(0x27, false), vkEvent(0x27, true))
		if len(ks) >= 64 {
			sendKeys(ks)
			ks = ks[:0]
		}
	}
	sendKeys(ks)
}
