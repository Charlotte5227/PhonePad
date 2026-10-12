//go:build windows

package main

import (
	"errors"
	"os/exec"
	"syscall"
	"unsafe"
)

var (
	procLockWorkStation   = user32.NewProc("LockWorkStation")
	procPostMessageW      = user32.NewProc("PostMessageW")
	powrprof              = syscall.NewLazyDLL("powrprof.dll")
	procSetSuspendState   = powrprof.NewProc("SetSuspendState")
	advapi32              = syscall.NewLazyDLL("advapi32.dll")
	procOpenProcessToken  = advapi32.NewProc("OpenProcessToken")
	procLookupPrivValue   = advapi32.NewProc("LookupPrivilegeValueW")
	procAdjustTokenPrivs  = advapi32.NewProc("AdjustTokenPrivileges")
	procGetCurrentProcess = kernel32Mem.NewProc("GetCurrentProcess")
	procCloseHandle       = kernel32Mem.NewProc("CloseHandle")
)

type luid struct {
	Low  uint32
	High int32
}

type tokenPrivileges struct {
	Count uint32
	Luid  luid
	Attr  uint32
}

// スリープに必要な権限を有効にする
func enableShutdownPrivilege() {
	proc, _, _ := procGetCurrentProcess.Call()
	var tok uintptr
	if r, _, _ := procOpenProcessToken.Call(proc, 0x0020|0x0008 /* ADJUST_PRIVILEGES|QUERY */, uintptr(unsafe.Pointer(&tok))); r == 0 {
		return
	}
	defer procCloseHandle.Call(tok)
	name, _ := syscall.UTF16PtrFromString("SeShutdownPrivilege")
	tp := tokenPrivileges{Count: 1, Attr: 0x2 /* SE_PRIVILEGE_ENABLED */}
	if r, _, _ := procLookupPrivValue.Call(0, uintptr(unsafe.Pointer(name)), uintptr(unsafe.Pointer(&tp.Luid))); r == 0 {
		return
	}
	procAdjustTokenPrivs.Call(tok, 0, uintptr(unsafe.Pointer(&tp)), 0, 0, 0)
}

func runHidden(name string, args ...string) error {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return c.Start()
}

func powerAction(a string) error {
	switch a {
	case "LOCK":
		if r, _, _ := procLockWorkStation.Call(); r == 0 {
			return errors.New("画面をロックできません")
		}
	case "MONOFF":
		// WM_SYSCOMMAND / SC_MONITORPOWER / 2 = 画面を消す
		procPostMessageW.Call(0xFFFF, 0x0112, 0xF170, 2)
	case "SLEEP":
		enableShutdownPrivilege()
		if r, _, _ := procSetSuspendState.Call(0, 0, 0); r == 0 {
			return errors.New("スリープできません (このPCがスリープに対応していない可能性があります)")
		}
	case "RESTART":
		return runHidden("shutdown.exe", "/r", "/t", "0")
	case "SHUTDOWN":
		return runHidden("shutdown.exe", "/s", "/t", "0")
	default:
		return errors.New("不明な操作です")
	}
	return nil
}
