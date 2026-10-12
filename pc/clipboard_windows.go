//go:build windows

package main

import (
	"errors"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

var (
	procOpenClipboard    = user32.NewProc("OpenClipboard")
	procCloseClipboard   = user32.NewProc("CloseClipboard")
	procEmptyClipboard   = user32.NewProc("EmptyClipboard")
	procGetClipboardData = user32.NewProc("GetClipboardData")
	procSetClipboardData = user32.NewProc("SetClipboardData")
	procGlobalAlloc      = kernel32Mem.NewProc("GlobalAlloc")
	procGlobalFree       = kernel32Mem.NewProc("GlobalFree")
	procGlobalLock       = kernel32Mem.NewProc("GlobalLock")
	procGlobalUnlock     = kernel32Mem.NewProc("GlobalUnlock")
	procLstrlenW         = kernel32Mem.NewProc("lstrlenW")
)

const cfUnicodeText = 13

// 他のアプリが使っていることがあるので、少し待ちながら開く
func openClipboard() error {
	for i := 0; i < 10; i++ {
		if r, _, _ := procOpenClipboard.Call(0); r != 0 {
			return nil
		}
		time.Sleep(30 * time.Millisecond)
	}
	return errors.New("クリップボードを開けません")
}

func getClipboardText() (string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := openClipboard(); err != nil {
		return "", err
	}
	defer procCloseClipboard.Call()
	h, _, _ := procGetClipboardData.Call(cfUnicodeText)
	if h == 0 {
		return "", nil // 文字以外 (画像など)
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		return "", errors.New("クリップボードを読めません")
	}
	defer procGlobalUnlock.Call(h)
	n, _, _ := procLstrlenW.Call(p)
	if n == 0 {
		return "", nil
	}
	if n > 1<<20 {
		n = 1 << 20
	}
	buf := make([]uint16, n)
	procRtlMoveMemory.Call(uintptr(unsafe.Pointer(&buf[0])), p, n*2)
	return syscall.UTF16ToString(buf), nil
}

func setClipboardText(s string) error {
	u, err := syscall.UTF16FromString(s)
	if err != nil {
		return errors.New("この文字はコピーできません")
	}
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := openClipboard(); err != nil {
		return err
	}
	defer procCloseClipboard.Call()
	procEmptyClipboard.Call()
	size := uintptr(len(u) * 2)
	h, _, _ := procGlobalAlloc.Call(0x0002 /* GMEM_MOVEABLE */, size)
	if h == 0 {
		return errors.New("クリップボードに書けません")
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		procGlobalFree.Call(h)
		return errors.New("クリップボードに書けません")
	}
	procRtlMoveMemory.Call(p, uintptr(unsafe.Pointer(&u[0])), size)
	procGlobalUnlock.Call(h)
	if r, _, _ := procSetClipboardData.Call(cfUnicodeText, h); r == 0 {
		procGlobalFree.Call(h)
		return errors.New("クリップボードに書けません")
	}
	return nil
}
