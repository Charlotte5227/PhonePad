//go:build windows

package main

import (
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// PC側の日本語入力(IME)がオンのままだと、送ったひらがながIMEに取り込まれて
// 漢字だけ先に入るなど順番が崩れる。文字を送る間だけIMEをオフにし、
// 入力が途切れたら元に戻す。

var (
	imm32                   = syscall.NewLazyDLL("imm32.dll")
	procImmGetDefaultIMEWnd = imm32.NewProc("ImmGetDefaultIMEWnd")
	procGetForegroundWindow = user32.NewProc("GetForegroundWindow")
	procGetWindowThreadPID  = user32.NewProc("GetWindowThreadProcessId")
	procGetGUIThreadInfo    = user32.NewProc("GetGUIThreadInfo")
	procSendMessageTimeoutW = user32.NewProc("SendMessageTimeoutW")
)

const (
	wmImeControl      = 0x0283
	imcGetOpenStatus  = 0x0005
	imcSetOpenStatus  = 0x0006
	smtoAbortIfHung   = 0x0002
	imeRestoreAfterMs = 600
)

type guiThreadInfo struct {
	cbSize        uint32
	flags         uint32
	hwndActive    uintptr
	hwndFocus     uintptr
	hwndCapture   uintptr
	hwndMenuOwner uintptr
	hwndMoveSize  uintptr
	hwndCaret     uintptr
	rcCaret       [4]int32
}

func focusedWindow() uintptr {
	fg, _, _ := procGetForegroundWindow.Call()
	if fg == 0 {
		return 0
	}
	tid, _, _ := procGetWindowThreadPID.Call(fg, 0)
	gti := guiThreadInfo{cbSize: uint32(unsafe.Sizeof(guiThreadInfo{}))}
	r, _, _ := procGetGUIThreadInfo.Call(tid, uintptr(unsafe.Pointer(&gti)))
	if r != 0 && gti.hwndFocus != 0 {
		return gti.hwndFocus
	}
	return fg
}

func imeSend(imeWnd, cmd, val uintptr) (uintptr, bool) {
	var res uintptr
	r, _, _ := procSendMessageTimeoutW.Call(imeWnd, wmImeControl, cmd, val, smtoAbortIfHung, 200, uintptr(unsafe.Pointer(&res)))
	return res, r != 0
}

type imeGuard struct {
	mu     sync.Mutex
	closed uintptr // IMEを一時的にオフにしたIMEウィンドウ (0=なし)
	timer  *time.Timer
}

var ime = &imeGuard{}

// 文字を送る直前に呼ぶ: 必要ならIMEをオフにし、少し後で元に戻す予約をする
func (g *imeGuard) suspend() {
	g.mu.Lock()
	defer g.mu.Unlock()

	h := focusedWindow()
	imeWnd := uintptr(0)
	if h != 0 {
		imeWnd, _, _ = procImmGetDefaultIMEWnd.Call(h)
	}
	// 別のウィンドウに移っていたら、前のウィンドウのIMEを先に戻す
	if g.closed != 0 && g.closed != imeWnd {
		imeSend(g.closed, imcSetOpenStatus, 1)
		g.closed = 0
	}
	if imeWnd != 0 && g.closed == 0 {
		if open, ok := imeSend(imeWnd, imcGetOpenStatus, 0); ok && open != 0 {
			imeSend(imeWnd, imcSetOpenStatus, 0)
			g.closed = imeWnd
		}
	}
	if g.closed != 0 {
		if g.timer != nil {
			g.timer.Stop()
		}
		g.timer = time.AfterFunc(imeRestoreAfterMs*time.Millisecond, g.restore)
	}
}

func (g *imeGuard) restore() {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.closed != 0 {
		imeSend(g.closed, imcSetOpenStatus, 1)
		g.closed = 0
	}
}
