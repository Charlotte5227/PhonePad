//go:build windows

package main

import (
	"runtime"
	"syscall"
	"unsafe"
)

// COM を使う処理は、COMを初期化した専用スレッドで順番に実行する

var (
	kernel32Mem       = syscall.NewLazyDLL("kernel32.dll")
	procRtlMoveMemory = kernel32Mem.NewProc("RtlMoveMemory")
	oleaut32          = syscall.NewLazyDLL("oleaut32.dll")
	procSysStringLen  = oleaut32.NewProc("SysStringLen")
	procSysFreeString = oleaut32.NewProc("SysFreeString")
)

type comWorker struct {
	ch chan func()
}

func newComWorker(name string) *comWorker {
	w := &comWorker{ch: make(chan func(), 16)}
	go func() {
		runtime.LockOSThread()
		procCoInitializeEx.Call(0, 0) // COINIT_MULTITHREADED
		for f := range w.ch {
			func() {
				defer guard(name)
				f()
			}()
		}
	}()
	return w
}

func (w *comWorker) run(f func() error) error {
	done := make(chan error, 1)
	w.ch <- func() {
		var err error = errAudio
		defer func() { done <- err }()
		err = f()
	}
	return <-done
}

// BSTR (COMの文字列) を Go の文字列にして解放する
func takeBSTR(b uintptr) string {
	if b == 0 {
		return ""
	}
	defer procSysFreeString.Call(b)
	n, _, _ := procSysStringLen.Call(b)
	if n == 0 {
		return ""
	}
	buf := make([]uint16, n)
	procRtlMoveMemory.Call(uintptr(unsafe.Pointer(&buf[0])), b, n*2)
	return syscall.UTF16ToString(buf)
}
