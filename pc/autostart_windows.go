//go:build windows

package main

import (
	"os"
	"syscall"
	"unsafe"
)

// Windows の起動時に PhonePad を自動で起動する (ログインしているユーザーだけ)

var (
	procRegOpenKeyExW    = advapi32.NewProc("RegOpenKeyExW")
	procRegSetValueExW   = advapi32.NewProc("RegSetValueExW")
	procRegDeleteValueW  = advapi32.NewProc("RegDeleteValueW")
	procRegQueryValueExW = advapi32.NewProc("RegQueryValueExW")
	procRegCloseKey      = advapi32.NewProc("RegCloseKey")
)

const (
	hkeyCurrentUser = 0x80000001
	runKeyPath      = `Software\Microsoft\Windows\CurrentVersion\Run`
	runValueName    = "PhonePad"
)

func openRunKey() (uintptr, bool) {
	path, _ := syscall.UTF16PtrFromString(runKeyPath)
	var h uintptr
	r, _, _ := procRegOpenKeyExW.Call(hkeyCurrentUser, uintptr(unsafe.Pointer(path)), 0, 0x0001|0x0002 /* QUERY|SET */, uintptr(unsafe.Pointer(&h)))
	return h, r == 0
}

func autostartCommand() string {
	exe, _ := os.Executable()
	return `"` + exe + `" --background`
}

// 登録されている起動コマンド ("" = 未登録)
func autostartValue() string {
	h, ok := openRunKey()
	if !ok {
		return ""
	}
	defer procRegCloseKey.Call(h)
	name, _ := syscall.UTF16PtrFromString(runValueName)
	var typ, size uint32
	if r, _, _ := procRegQueryValueExW.Call(h, uintptr(unsafe.Pointer(name)), 0, uintptr(unsafe.Pointer(&typ)), 0, uintptr(unsafe.Pointer(&size))); r != 0 || size < 2 {
		return ""
	}
	buf := make([]uint16, size/2+1)
	if r, _, _ := procRegQueryValueExW.Call(h, uintptr(unsafe.Pointer(name)), 0, uintptr(unsafe.Pointer(&typ)), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size))); r != 0 {
		return ""
	}
	return syscall.UTF16ToString(buf)
}

func autostartEnabled() bool { return autostartValue() != "" }

func setAutostart(on bool) error {
	h, ok := openRunKey()
	if !ok {
		return errAutostart
	}
	defer procRegCloseKey.Call(h)
	name, _ := syscall.UTF16PtrFromString(runValueName)
	if !on {
		procRegDeleteValueW.Call(h, uintptr(unsafe.Pointer(name)))
		return nil
	}
	data, _ := syscall.UTF16FromString(autostartCommand())
	r, _, _ := procRegSetValueExW.Call(h, uintptr(unsafe.Pointer(name)), 0, 1 /* REG_SZ */, uintptr(unsafe.Pointer(&data[0])), uintptr(len(data)*2))
	if r != 0 {
		return errAutostart
	}
	return nil
}

// exe を移動したときは、登録されている場所を今の場所に直す
func refreshAutostartPath() {
	if v := autostartValue(); v != "" && v != autostartCommand() {
		_ = setAutostart(true)
	}
}
