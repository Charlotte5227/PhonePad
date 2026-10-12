//go:build windows

package main

import (
	"errors"
	"fmt"
	"math"
	"runtime"
	"syscall"
	"unsafe"
)

// Windows の音量・ミュート・出力先 (Core Audio API を COM で直接呼ぶ)

type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

var (
	clsidMMDeviceEnumerator = guid{0xBCDE0395, 0xE52F, 0x467C, [8]byte{0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}}
	iidIMMDeviceEnumerator  = guid{0xA95664D2, 0x9614, 0x4F35, [8]byte{0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}}
	iidIAudioEndpointVolume = guid{0x5CDF2C82, 0x841E, 0x4546, [8]byte{0x97, 0x22, 0x0C, 0xF7, 0x40, 0x78, 0x22, 0x9A}}
	// 既定の出力先を変える (Windows 7 以降で使われている非公開インターフェース)
	clsidPolicyConfigClient = guid{0x870AF99C, 0x171D, 0x4F9E, [8]byte{0xAF, 0x0D, 0xE6, 0x3D, 0xF4, 0x0C, 0x2B, 0xC9}}
	iidIPolicyConfig        = guid{0xF8679F50, 0x850A, 0x41CF, [8]byte{0x9C, 0x72, 0x43, 0x0F, 0x29, 0x02, 0x90, 0xC8}}
	// PKEY_Device_FriendlyName
	pkeyFriendlyName = propertyKey{guid{0xA45C254E, 0xDF1C, 0x4EFD, [8]byte{0x80, 0x20, 0x67, 0xD1, 0x46, 0xA8, 0x50, 0xE0}}, 14}
)

type propertyKey struct {
	fmtid guid
	pid   uint32
}

type propVariant struct {
	vt       uint16
	r1       uint16
	r2       uint16
	r3       uint16
	val      *uint16
	reserved uintptr
}

// COMオブジェクト: 先頭が関数表へのポインタ
type comObj struct {
	vtbl *[128]uintptr
}

// COMの関数は syscall.SyscallN を呼び出し元で直接呼ぶ
// (Goの変数のアドレスを渡すとき、その変数が動かないことを保証するため)
func (o *comObj) ptr() uintptr { return uintptr(unsafe.Pointer(o)) }

func hrOf(r, _ uintptr, _ error) uint32 { return uint32(r) }

func (o *comObj) release() {
	if o != nil {
		syscall.SyscallN(o.vtbl[2], o.ptr())
	}
}

var (
	ole32                = syscall.NewLazyDLL("ole32.dll")
	procCoInitializeEx   = ole32.NewProc("CoInitializeEx")
	procCoCreateInstance = ole32.NewProc("CoCreateInstance")
	procCoTaskMemFree    = ole32.NewProc("CoTaskMemFree")
	procPropVariantClear = ole32.NewProc("PropVariantClear")
	errAudio             = errors.New("音声デバイスを操作できませんでした")
)

const (
	clsctxAll       = 0x17
	eRender         = 0
	eConsole        = 0
	eMultimedia     = 1
	eCommunications = 2
	deviceActive    = 1
	vtLPWSTR        = 31
)

var audioWorker = newComWorker("音量")

func onAudioThread(f func() error) error { return audioWorker.run(f) }

func hresultOK(hr uint32) bool { return int32(hr) >= 0 }

func utf16PtrToString(p *uint16) string {
	if p == nil {
		return ""
	}
	var s []uint16
	for i := 0; i < 4096; i++ {
		c := *(*uint16)(unsafe.Add(unsafe.Pointer(p), i*2))
		if c == 0 {
			break
		}
		s = append(s, c)
	}
	return syscall.UTF16ToString(s)
}

func newEnumerator() (*comObj, error) {
	var e *comObj
	hr, _, _ := procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidMMDeviceEnumerator)), 0, clsctxAll,
		uintptr(unsafe.Pointer(&iidIMMDeviceEnumerator)), uintptr(unsafe.Pointer(&e)))
	if !hresultOK(uint32(hr)) || e == nil {
		return nil, fmt.Errorf("%w (0x%08X)", errAudio, uint32(hr))
	}
	return e, nil
}

func deviceID(dev *comObj) string {
	var p *uint16
	if !hresultOK(hrOf(syscall.SyscallN(dev.vtbl[5], dev.ptr(), uintptr(unsafe.Pointer(&p))))) || p == nil {
		return ""
	}
	s := utf16PtrToString(p)
	procCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
	return s
}

func deviceName(dev *comObj) string {
	var store *comObj
	if !hresultOK(hrOf(syscall.SyscallN(dev.vtbl[4], dev.ptr(), 0 /* STGM_READ */, uintptr(unsafe.Pointer(&store))))) || store == nil {
		return ""
	}
	defer store.release()
	var pv propVariant
	if !hresultOK(hrOf(syscall.SyscallN(store.vtbl[5], store.ptr(), uintptr(unsafe.Pointer(&pkeyFriendlyName)), uintptr(unsafe.Pointer(&pv))))) {
		return ""
	}
	name := ""
	if pv.vt == vtLPWSTR {
		name = utf16PtrToString(pv.val)
	}
	procPropVariantClear.Call(uintptr(unsafe.Pointer(&pv)))
	return name
}

// 既定の出力先の音量操作オブジェクト
func withEndpointVolume(f func(v *comObj) error) error {
	en, err := newEnumerator()
	if err != nil {
		return err
	}
	defer en.release()
	var dev *comObj
	if !hresultOK(hrOf(syscall.SyscallN(en.vtbl[4], en.ptr(), eRender, eMultimedia, uintptr(unsafe.Pointer(&dev))))) || dev == nil {
		return errors.New("出力先の音声デバイスがありません")
	}
	defer dev.release()
	var vol *comObj
	if !hresultOK(hrOf(syscall.SyscallN(dev.vtbl[3], dev.ptr(), uintptr(unsafe.Pointer(&iidIAudioEndpointVolume)), clsctxAll, 0, uintptr(unsafe.Pointer(&vol))))) || vol == nil {
		return errAudio
	}
	defer vol.release()
	return f(vol)
}

func getAudioInfo() audioInfo {
	var info audioInfo
	err := onAudioThread(func() error {
		_ = withEndpointVolume(func(v *comObj) error {
			var level float32
			if hresultOK(hrOf(syscall.SyscallN(v.vtbl[9], v.ptr(), uintptr(unsafe.Pointer(&level))))) {
				info.Volume = int(math.Round(float64(level) * 100))
			}
			var mute int32
			if hresultOK(hrOf(syscall.SyscallN(v.vtbl[15], v.ptr(), uintptr(unsafe.Pointer(&mute))))) {
				info.Mute = mute != 0
			}
			return nil
		})

		en, err := newEnumerator()
		if err != nil {
			return err
		}
		defer en.release()
		defID := ""
		var def *comObj
		if hresultOK(hrOf(syscall.SyscallN(en.vtbl[4], en.ptr(), eRender, eMultimedia, uintptr(unsafe.Pointer(&def))))) && def != nil {
			defID = deviceID(def)
			def.release()
		}
		var coll *comObj
		if !hresultOK(hrOf(syscall.SyscallN(en.vtbl[3], en.ptr(), eRender, deviceActive, uintptr(unsafe.Pointer(&coll))))) || coll == nil {
			return errAudio
		}
		defer coll.release()
		var n uint32
		hrOf(syscall.SyscallN(coll.vtbl[3], coll.ptr(), uintptr(unsafe.Pointer(&n))))
		for i := uint32(0); i < n && i < 32; i++ {
			var dev *comObj
			if !hresultOK(hrOf(syscall.SyscallN(coll.vtbl[4], coll.ptr(), uintptr(i), uintptr(unsafe.Pointer(&dev))))) || dev == nil {
				continue
			}
			id := deviceID(dev)
			info.Devices = append(info.Devices, audioDevice{ID: id, Name: deviceName(dev), Default: id == defID})
			dev.release()
		}
		return nil
	})
	if err != nil {
		info.Error = err.Error()
	}
	return info
}

func setAudioVolume(percent int) error {
	if percent < 0 {
		percent = 0
	}
	if percent > 100 {
		percent = 100
	}
	return onAudioThread(func() error {
		return withEndpointVolume(func(v *comObj) error {
			// float の引数は XMM レジスタで渡す必要があるが、Go の呼び出しは
			// 先頭4つの引数を整数・浮動小数点の両方のレジスタに入れるので、ビット列で渡せばよい
			level := math.Float32bits(float32(percent) / 100)
			if !hresultOK(hrOf(syscall.SyscallN(v.vtbl[7], v.ptr(), uintptr(level), 0))) {
				return errAudio
			}
			if percent > 0 {
				hrOf(syscall.SyscallN(v.vtbl[14], v.ptr(), 0, 0)) // 音量を上げたらミュート解除
			}
			return nil
		})
	})
}

func setAudioMute(m bool) error {
	b := uintptr(0)
	if m {
		b = 1
	}
	return onAudioThread(func() error {
		return withEndpointVolume(func(v *comObj) error {
			if !hresultOK(hrOf(syscall.SyscallN(v.vtbl[14], v.ptr(), b, 0))) {
				return errAudio
			}
			return nil
		})
	})
}

func setAudioDefault(id string) error {
	p, err := syscall.UTF16PtrFromString(id)
	if err != nil {
		return err
	}
	return onAudioThread(func() error {
		var pc *comObj
		hr, _, _ := procCoCreateInstance.Call(
			uintptr(unsafe.Pointer(&clsidPolicyConfigClient)), 0, clsctxAll,
			uintptr(unsafe.Pointer(&iidIPolicyConfig)), uintptr(unsafe.Pointer(&pc)))
		if !hresultOK(uint32(hr)) || pc == nil {
			return fmt.Errorf("出力先を切り替えられません (0x%08X)", uint32(hr))
		}
		defer pc.release()
		for _, role := range []uintptr{eConsole, eMultimedia, eCommunications} {
			if r := hrOf(syscall.SyscallN(pc.vtbl[13], pc.ptr(), uintptr(unsafe.Pointer(p)), role)); !hresultOK(r) {
				return fmt.Errorf("出力先を切り替えられません (0x%08X)", r)
			}
		}
		runtime.KeepAlive(p)
		return nil
	})
}
