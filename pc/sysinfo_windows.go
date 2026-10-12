//go:build windows

package main

import (
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var (
	procGetSystemTimes       = kernel32Mem.NewProc("GetSystemTimes")
	procGlobalMemoryStatusEx = kernel32Mem.NewProc("GlobalMemoryStatusEx")
	procGetSystemPowerStatus = kernel32Mem.NewProc("GetSystemPowerStatus")
	procGetTickCount64       = kernel32Mem.NewProc("GetTickCount64")
	procGetDiskFreeSpaceExW  = kernel32Mem.NewProc("GetDiskFreeSpaceExW")

	cpuMu               sync.Mutex
	lastIdle, lastTotal uint64
	lastCPU             float64
	cpuNameOnce         sync.Once
	cpuNameCached       string
)

type fileTime struct{ Low, High uint32 }

func (f fileTime) u64() uint64 { return uint64(f.High)<<32 | uint64(f.Low) }

type memoryStatusEx struct {
	Length               uint32
	MemoryLoad           uint32
	TotalPhys            uint64
	AvailPhys            uint64
	TotalPageFile        uint64
	AvailPageFile        uint64
	TotalVirtual         uint64
	AvailVirtual         uint64
	AvailExtendedVirtual uint64
}

type systemPowerStatus struct {
	ACLineStatus        byte
	BatteryFlag         byte
	BatteryLifePercent  byte
	SystemStatusFlag    byte
	BatteryLifeTime     uint32
	BatteryFullLifeTime uint32
}

func cpuTimes() (idle, total uint64, ok bool) {
	var i, k, u fileTime
	r, _, _ := procGetSystemTimes.Call(uintptr(unsafe.Pointer(&i)), uintptr(unsafe.Pointer(&k)), uintptr(unsafe.Pointer(&u)))
	if r == 0 {
		return 0, 0, false
	}
	// カーネル時間にはアイドル時間が含まれる
	return i.u64(), k.u64() + u.u64(), true
}

// 前回の問い合わせからの CPU 使用率 (初回は少し待って測る)
func cpuUsage() float64 {
	cpuMu.Lock()
	defer cpuMu.Unlock()
	idle, total, ok := cpuTimes()
	if !ok {
		return -1
	}
	if lastTotal == 0 {
		lastIdle, lastTotal = idle, total
		time.Sleep(250 * time.Millisecond)
		idle, total, _ = cpuTimes()
	}
	dt := total - lastTotal
	di := idle - lastIdle
	lastIdle, lastTotal = idle, total
	if dt == 0 {
		return lastCPU
	}
	lastCPU = 100 * (1 - float64(di)/float64(dt))
	if lastCPU < 0 {
		lastCPU = 0
	}
	return lastCPU
}

func cpuName() string {
	cpuNameOnce.Do(func() {
		path, _ := syscall.UTF16PtrFromString(`HARDWARE\DESCRIPTION\System\CentralProcessor\0`)
		var h uintptr
		if r, _, _ := procRegOpenKeyExW.Call(0x80000002 /* HKLM */, uintptr(unsafe.Pointer(path)), 0, 0x0001|0x0100 /* QUERY|WOW64_64KEY */, uintptr(unsafe.Pointer(&h))); r != 0 {
			return
		}
		defer procRegCloseKey.Call(h)
		name, _ := syscall.UTF16PtrFromString("ProcessorNameString")
		buf := make([]uint16, 256)
		size := uint32(len(buf) * 2)
		var typ uint32
		if r, _, _ := procRegQueryValueExW.Call(h, uintptr(unsafe.Pointer(name)), 0, uintptr(unsafe.Pointer(&typ)), uintptr(unsafe.Pointer(&buf[0])), uintptr(unsafe.Pointer(&size))); r == 0 {
			cpuNameCached = strings.TrimSpace(syscall.UTF16ToString(buf))
		}
	})
	return cpuNameCached
}

func getSysInfo() sysInfo {
	si := sysInfo{CPUName: cpuName(), CPU: cpuUsage(), Battery: -1}
	ms := memoryStatusEx{Length: uint32(unsafe.Sizeof(memoryStatusEx{}))}
	if r, _, _ := procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&ms))); r != 0 {
		si.MemTotal = ms.TotalPhys
		si.MemUsed = ms.TotalPhys - ms.AvailPhys
	}
	var ps systemPowerStatus
	if r, _, _ := procGetSystemPowerStatus.Call(uintptr(unsafe.Pointer(&ps))); r != 0 {
		if ps.BatteryFlag != 128 && ps.BatteryFlag != 255 && ps.BatteryLifePercent <= 100 {
			si.Battery = int(ps.BatteryLifePercent)
		}
		si.Charging = ps.ACLineStatus == 1
	}
	up, _, _ := procGetTickCount64.Call()
	si.Uptime = uint64(up) / 1000
	root, _ := syscall.UTF16PtrFromString(`C:\`)
	var free, total, totalFree uint64
	if r, _, _ := procGetDiskFreeSpaceExW.Call(uintptr(unsafe.Pointer(root)), uintptr(unsafe.Pointer(&free)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&totalFree))); r != 0 {
		si.DiskFree, si.DiskTotal = free, total
	}
	return si
}

// スタートメニューにあるアプリの一覧
func listApps() []appEntry {
	var dirs []string
	if p := os.Getenv("ProgramData"); p != "" {
		dirs = append(dirs, filepath.Join(p, `Microsoft\Windows\Start Menu\Programs`))
	}
	if p := os.Getenv("APPDATA"); p != "" {
		dirs = append(dirs, filepath.Join(p, `Microsoft\Windows\Start Menu\Programs`))
	}
	seen := map[string]bool{}
	var out []appEntry
	for _, d := range dirs {
		_ = filepath.WalkDir(d, func(p string, e fs.DirEntry, err error) error {
			if err != nil || e.IsDir() {
				return nil
			}
			ext := strings.ToLower(filepath.Ext(p))
			if ext != ".lnk" && ext != ".url" {
				return nil
			}
			name := strings.TrimSuffix(e.Name(), filepath.Ext(e.Name()))
			low := strings.ToLower(name)
			if strings.Contains(low, "uninstall") || strings.Contains(name, "アンインストール") {
				return nil
			}
			if seen[low] || len(out) >= 800 {
				return nil
			}
			seen[low] = true
			out = append(out, appEntry{Name: name, Path: p})
			return nil
		})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

// アプリ・ファイル・URL を開く
func launch(target string) error { return shellOpen(target) }
