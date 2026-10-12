//go:build windows

package main

import (
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"unsafe"
)

const (
	vigemOK          = 0x20000000
	vigemBusNotFound = 0xE0000001
	vigemNoFreeSlot  = 0xE0000002
	vigemBusVersion  = 0xE0000009
)

var (
	vgMu     sync.Mutex
	vgDLL    *syscall.LazyDLL
	vgClient uintptr
	vgStatus = "未確認"
	vgReady  bool

	pAlloc, pConnect, pTAlloc, pTAdd, pTRemove, pTFree, pUpdate *syscall.LazyProc
	pRegNotify, pUnregNotify                                    *syscall.LazyProc

	// ゲームからの振動の通知 (どのコントローラー宛てか → 送り先)
	rumbleMu       sync.Mutex
	rumbleHandlers = map[uintptr]func(large, small byte){}
	rumbleCallback = syscall.NewCallback(func(client, target, large, small, led, user uintptr) uintptr {
		rumbleMu.Lock()
		h := rumbleHandlers[target]
		rumbleMu.Unlock()
		if h != nil {
			h(byte(large), byte(small))
		}
		return 0
	})
)

func initGamepadSystem() {
	vgMu.Lock()
	defer vgMu.Unlock()
	if vgReady {
		return
	}
	exe, _ := os.Executable()
	dllPath := filepath.Join(filepath.Dir(exe), "ViGEmClient.dll")
	if _, err := os.Stat(dllPath); err != nil {
		vgStatus = "ViGEmClient.dll が exe と同じフォルダにありません"
		return
	}
	if vgDLL == nil {
		vgDLL = syscall.NewLazyDLL(dllPath)
		pAlloc = vgDLL.NewProc("vigem_alloc")
		pConnect = vgDLL.NewProc("vigem_connect")
		pTAlloc = vgDLL.NewProc("vigem_target_x360_alloc")
		pTAdd = vgDLL.NewProc("vigem_target_add")
		pTRemove = vgDLL.NewProc("vigem_target_remove")
		pTFree = vgDLL.NewProc("vigem_target_free")
		pUpdate = vgDLL.NewProc("vigem_target_x360_update")
		pRegNotify = vgDLL.NewProc("vigem_target_x360_register_notification")
		pUnregNotify = vgDLL.NewProc("vigem_target_x360_unregister_notification")
	}
	if err := vgDLL.Load(); err != nil {
		vgStatus = "ViGEmClient.dll を読み込めません: " + err.Error()
		return
	}
	if vgClient == 0 {
		c, _, _ := pAlloc.Call()
		if c == 0 {
			vgStatus = "ViGEm の初期化に失敗しました"
			return
		}
		vgClient = c
	}
	r, _, _ := pConnect.Call(vgClient)
	switch uint32(r) {
	case vigemOK:
		vgReady = true
		vgStatus = "使用できます (Xbox 360 コントローラーとして認識)"
	case vigemBusNotFound:
		vgStatus = "ドライバー(ViGEmBus)が入っていません"
	case vigemBusVersion:
		vgStatus = "ドライバー(ViGEmBus)のバージョンが合いません"
	default:
		vgStatus = fmt.Sprintf("ドライバーに接続できません (0x%08X)", uint32(r))
	}
}

func gamepadReady() bool {
	vgMu.Lock()
	defer vgMu.Unlock()
	return vgReady
}

func gamepadStatusText() string {
	vgMu.Lock()
	defer vgMu.Unlock()
	return vgStatus
}

type vigemPad struct {
	target uintptr
}

func openPadSink(onRumble func(large, small byte)) (padSink, error) {
	initGamepadSystem()
	vgMu.Lock()
	defer vgMu.Unlock()
	if !vgReady {
		return nil, errors.New(vgStatus)
	}
	t, _, _ := pTAlloc.Call()
	if t == 0 {
		return nil, errors.New("仮想コントローラーを作成できません")
	}
	r, _, _ := pTAdd.Call(vgClient, t)
	if uint32(r) != vigemOK {
		pTFree.Call(t)
		if uint32(r) == vigemNoFreeSlot {
			return nil, errors.New("コントローラーの空きがありません (最大4台)")
		}
		return nil, fmt.Errorf("仮想コントローラーを接続できません (0x%08X)", uint32(r))
	}
	if onRumble != nil && pRegNotify.Find() == nil {
		rumbleMu.Lock()
		rumbleHandlers[t] = onRumble
		rumbleMu.Unlock()
		if r, _, _ := pRegNotify.Call(vgClient, t, rumbleCallback, 0); uint32(r) != vigemOK {
			log.Printf("振動の通知を受け取れません (0x%08X)", uint32(r))
		}
	}
	return &vigemPad{target: t}, nil
}

func (p *vigemPad) update(rep xusbReport) error {
	// XUSB_REPORT(12バイト)は値渡しだが、x64の呼び出し規約ではポインタで渡される
	r, _, _ := pUpdate.Call(vgClient, p.target, uintptr(unsafe.Pointer(&rep)))
	if uint32(r) != vigemOK {
		return fmt.Errorf("update 0x%08X", uint32(r))
	}
	return nil
}

func (p *vigemPad) close() {
	vgMu.Lock()
	defer vgMu.Unlock()
	if p.target != 0 {
		rumbleMu.Lock()
		delete(rumbleHandlers, p.target)
		rumbleMu.Unlock()
		if pUnregNotify.Find() == nil {
			pUnregNotify.Call(p.target)
		}
		pTRemove.Call(vgClient, p.target)
		pTFree.Call(p.target)
		p.target = 0
	}
}
