//go:build windows

package main

import (
	"path/filepath"
	"syscall"
	"unsafe"
)

var (
	procOpenProcess          = kernel32.NewProc("OpenProcess")
	procQueryFullProcessName = kernel32.NewProc("QueryFullProcessImageNameW")
	procGetWindowTextW       = user32.NewProc("GetWindowTextW")
)

const foregroundSupported = true

// 前に出ているウィンドウのアプリ (実行ファイル名・ウィンドウの題名)
func foregroundApp() (string, string) {
	hwnd, _, _ := procGetForegroundWindow.Call()
	if hwnd == 0 {
		return "", ""
	}
	var title [256]uint16
	procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&title[0])), uintptr(len(title)))
	var pid uint32
	procGetWindowThreadPID.Call(hwnd, uintptr(unsafe.Pointer(&pid)))
	if pid == 0 {
		return "", ""
	}
	h, _, _ := procOpenProcess.Call(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, 0, uintptr(pid))
	if h == 0 {
		return "", syscall.UTF16ToString(title[:])
	}
	defer procCloseHandle.Call(h)
	var buf [1024]uint16
	n := uint32(len(buf))
	if r, _, _ := procQueryFullProcessName.Call(h, 0, uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&n))); r == 0 {
		return "", syscall.UTF16ToString(title[:])
	}
	return filepath.Base(syscall.UTF16ToString(buf[:n])), syscall.UTF16ToString(title[:])
}
