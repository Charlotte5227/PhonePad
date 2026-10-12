//go:build windows

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

var (
	kernel32             = syscall.NewLazyDLL("kernel32.dll")
	procSetConsoleOutCP  = kernel32.NewProc("SetConsoleOutputCP")
	procSetConsoleCP     = kernel32.NewProc("SetConsoleCP")
	procSetConsoleTitleW = kernel32.NewProc("SetConsoleTitleW")
	procAllocConsole     = kernel32.NewProc("AllocConsole")
	shell32              = syscall.NewLazyDLL("shell32.dll")
	procShellExecuteW    = shell32.NewProc("ShellExecuteW")
)

// exe はウィンドウなしで作ってあるので、普通に起動したときだけ黒いウィンドウを出す
func setupConsole(background bool) bool {
	if background {
		return false
	}
	if r, _, _ := procAllocConsole.Call(); r == 0 {
		return false
	}
	if out, err := os.OpenFile("CONOUT$", os.O_RDWR, 0); err == nil {
		os.Stdout = out
		os.Stderr = out
	}
	if in, err := os.OpenFile("CONIN$", os.O_RDWR, 0); err == nil {
		os.Stdin = in
	}
	procSetConsoleOutCP.Call(65001)
	procSetConsoleCP.Call(65001)
	t, _ := syscall.UTF16PtrFromString("PhonePad PC")
	procSetConsoleTitleW.Call(uintptr(unsafe.Pointer(t)))
	return true
}

// ShellExecute で開く (管理者権限が必要なインストーラーはUAC確認が出る)
func shellOpen(target string) error {
	verb, _ := syscall.UTF16PtrFromString("open")
	file, err := syscall.UTF16PtrFromString(target)
	if err != nil {
		return err
	}
	r, _, _ := procShellExecuteW.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(file)), 0, 0, 1)
	if r <= 32 {
		return fmt.Errorf("ShellExecute failed (%d)", r)
	}
	return nil
}

func openURL(u string) {
	_ = shellOpen(u)
}
