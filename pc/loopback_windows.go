//go:build windows

package main

import (
	"encoding/binary"
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

// WASAPI のループバック録音で、スピーカーに出ている音を取り込む

var (
	iidIAudioClient        = guid{0x1CB9AD4C, 0xDBFA, 0x4C32, [8]byte{0xB1, 0x78, 0xC2, 0xF5, 0x68, 0xA7, 0x03, 0xB2}}
	iidIAudioCaptureClient = guid{0xC8ADBD64, 0xE71E, 0x48A0, [8]byte{0xA4, 0xDE, 0x18, 0x5C, 0x39, 0x5C, 0xD3, 0x17}}
	// KSDATAFORMAT_SUBTYPE_IEEE_FLOAT の先頭 (00000003-0000-0010-8000-00AA00389B71)
	subtypeFloat = [16]byte{0x03, 0, 0, 0, 0, 0, 0x10, 0, 0x80, 0, 0, 0xAA, 0, 0x38, 0x9B, 0x71}
)

const (
	acInitialize       = 3 // IAudioClient
	acGetMixFormat     = 8
	acStart            = 10
	acStop             = 11
	acGetService       = 14
	ccGetBuffer        = 3 // IAudioCaptureClient
	ccReleaseBuffer    = 4
	ccGetNextPacket    = 5
	streamFlagLoopback = 0x00020000
	bufferFlagSilent   = 0x2
)

type loopResult struct {
	rate int
	err  error
}

func startLoopback(onData func([]byte)) (int, func(), error) {
	ready := make(chan loopResult, 1)
	stop := make(chan struct{})
	go func() {
		defer guard("音の取り込み")
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		procCoInitializeEx.Call(0, 0)

		fail := func(err error) { ready <- loopResult{err: err} }
		en, err := newEnumerator()
		if err != nil {
			fail(err)
			return
		}
		defer en.release()
		var dev *comObj
		if !hresultOK(hrOf(syscall.SyscallN(en.vtbl[4], en.ptr(), eRender, eConsole, uintptr(unsafe.Pointer(&dev))))) || dev == nil {
			fail(errors.New("出力先の音声デバイスがありません"))
			return
		}
		defer dev.release()
		var ac *comObj
		if !hresultOK(hrOf(syscall.SyscallN(dev.vtbl[3], dev.ptr(), uintptr(unsafe.Pointer(&iidIAudioClient)), clsctxAll, 0, uintptr(unsafe.Pointer(&ac))))) || ac == nil {
			fail(errors.New("音声デバイスを開けません"))
			return
		}
		defer ac.release()
		var pwfx uintptr
		if !hresultOK(hrOf(syscall.SyscallN(ac.vtbl[acGetMixFormat], ac.ptr(), uintptr(unsafe.Pointer(&pwfx))))) || pwfx == 0 {
			fail(errors.New("音の形式を取得できません"))
			return
		}
		defer procCoTaskMemFree.Call(pwfx)
		// WAVEFORMATEX(18バイト) + 拡張部分(22バイト)
		hdr := make([]byte, 40)
		procRtlMoveMemory.Call(uintptr(unsafe.Pointer(&hdr[0])), pwfx, 18)
		tag := binary.LittleEndian.Uint16(hdr[0:])
		channels := int(binary.LittleEndian.Uint16(hdr[2:]))
		rate := int(binary.LittleEndian.Uint32(hdr[4:]))
		block := int(binary.LittleEndian.Uint16(hdr[12:]))
		bits := int(binary.LittleEndian.Uint16(hdr[14:]))
		cb := binary.LittleEndian.Uint16(hdr[16:])
		isFloat := tag == 3
		if tag == 0xFFFE && cb >= 22 {
			procRtlMoveMemory.Call(uintptr(unsafe.Pointer(&hdr[0])), pwfx, 40)
			var sub [16]byte
			copy(sub[:], hdr[24:40])
			isFloat = sub == subtypeFloat
		}
		if channels < 1 || block < 1 || rate < 8000 {
			fail(fmt.Errorf("対応していない音の形式です (%d ch, %d Hz)", channels, rate))
			return
		}
		// 200ms のバッファで共有モード・ループバック
		if r := hrOf(syscall.SyscallN(ac.vtbl[acInitialize], ac.ptr(), 0, streamFlagLoopback, 2000000, 0, pwfx, 0)); !hresultOK(r) {
			fail(fmt.Errorf("音の取り込みを開始できません (0x%08X)", r))
			return
		}
		var cc *comObj
		if !hresultOK(hrOf(syscall.SyscallN(ac.vtbl[acGetService], ac.ptr(), uintptr(unsafe.Pointer(&iidIAudioCaptureClient)), uintptr(unsafe.Pointer(&cc))))) || cc == nil {
			fail(errors.New("音の取り込みを開始できません"))
			return
		}
		defer cc.release()
		if !hresultOK(hrOf(syscall.SyscallN(ac.vtbl[acStart], ac.ptr()))) {
			fail(errors.New("音の取り込みを開始できません"))
			return
		}
		defer syscall.SyscallN(ac.vtbl[acStop], ac.ptr())
		decim := decimation(rate)
		ready <- loopResult{rate: rate / decim}

		t := time.NewTicker(10 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
			}
			for {
				var n uint32
				if !hresultOK(hrOf(syscall.SyscallN(cc.vtbl[ccGetNextPacket], cc.ptr(), uintptr(unsafe.Pointer(&n))))) || n == 0 {
					break
				}
				var data uintptr
				var frames, flags uint32
				if !hresultOK(hrOf(syscall.SyscallN(cc.vtbl[ccGetBuffer], cc.ptr(), uintptr(unsafe.Pointer(&data)), uintptr(unsafe.Pointer(&frames)), uintptr(unsafe.Pointer(&flags)), 0, 0))) {
					break
				}
				raw := make([]byte, int(frames)*block)
				if flags&bufferFlagSilent == 0 && data != 0 && len(raw) > 0 {
					procRtlMoveMemory.Call(uintptr(unsafe.Pointer(&raw[0])), data, uintptr(len(raw)))
				}
				syscall.SyscallN(cc.vtbl[ccReleaseBuffer], cc.ptr(), uintptr(frames))
				if out := toS16Stereo(raw, int(frames), channels, bits, isFloat, decim); len(out) > 0 {
					onData(out)
				}
			}
		}
	}()
	select {
	case r := <-ready:
		if r.err != nil {
			return 0, nil, r.err
		}
		return r.rate, func() { close(stop) }, nil
	case <-time.After(5 * time.Second):
		close(stop)
		return 0, nil, errors.New("音の取り込みが始まりません")
	}
}
