//go:build windows

package main

import (
	"errors"
	"image"
	"sync"
	"syscall"
	"unsafe"
)

// メインモニターの画面を縮小して取り込む (GDI)

var (
	gdi32                  = syscall.NewLazyDLL("gdi32.dll")
	procGetDC              = user32.NewProc("GetDC")
	procReleaseDC          = user32.NewProc("ReleaseDC")
	procGetSystemMetrics   = user32.NewProc("GetSystemMetrics")
	procGetCursorPos       = user32.NewProc("GetCursorPos")
	procSetDpiAwareCtx     = user32.NewProc("SetProcessDpiAwarenessContext")
	procSetProcessDPIAware = user32.NewProc("SetProcessDPIAware")
	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateCompatBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procStretchBlt         = gdi32.NewProc("StretchBlt")
	procSetStretchBltMode  = gdi32.NewProc("SetStretchBltMode")
	procSetBrushOrgEx      = gdi32.NewProc("SetBrushOrgEx")
	procGetDIBits          = gdi32.NewProc("GetDIBits")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procDeleteDC           = gdi32.NewProc("DeleteDC")

	captureMu sync.Mutex
)

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type bitmapInfo struct {
	Header bitmapInfoHeader
	Colors [4]uint32
}

type point struct{ X, Y int32 }

// 高DPIの画面でも実際の解像度で取り込めるようにする
func setupDPI() {
	if procSetDpiAwareCtx.Find() == nil {
		r, _, _ := procSetDpiAwareCtx.Call(^uintptr(3)) // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 (-4)
		if r != 0 {
			return
		}
	}
	if procSetProcessDPIAware.Find() == nil {
		procSetProcessDPIAware.Call()
	}
}

func screenSize() (int, int) {
	w, _, _ := procGetSystemMetrics.Call(0) // SM_CXSCREEN
	h, _, _ := procGetSystemMetrics.Call(1) // SM_CYSCREEN
	return int(w), int(h)
}

// 幅 maxW 以下に縮小した画面を返す
func captureScreen(maxW int) (*image.RGBA, error) {
	captureMu.Lock()
	defer captureMu.Unlock()

	sw, sh := screenSize()
	if sw <= 0 || sh <= 0 {
		return nil, errors.New("画面の大きさを取得できません")
	}
	tw := sw
	if maxW > 0 && tw > maxW {
		tw = maxW
	}
	th := sh * tw / sw
	if th < 1 {
		th = 1
	}

	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, errors.New("画面を取り込めません")
	}
	defer procReleaseDC.Call(0, screenDC)
	memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
	if memDC == 0 {
		return nil, errors.New("画面を取り込めません")
	}
	defer procDeleteDC.Call(memDC)
	bmp, _, _ := procCreateCompatBitmap.Call(screenDC, uintptr(tw), uintptr(th))
	if bmp == 0 {
		return nil, errors.New("画面を取り込めません")
	}
	defer procDeleteObject.Call(bmp)

	old, _, _ := procSelectObject.Call(memDC, bmp)
	procSetStretchBltMode.Call(memDC, 4) // HALFTONE (縮小してもきれい)
	procSetBrushOrgEx.Call(memDC, 0, 0, 0)
	r, _, _ := procStretchBlt.Call(memDC, 0, 0, uintptr(tw), uintptr(th), screenDC, 0, 0, uintptr(sw), uintptr(sh), 0x00CC0020)
	procSelectObject.Call(memDC, old)
	if r == 0 {
		return nil, errors.New("画面を取り込めません (ロック画面やUACの確認画面は取り込めません)")
	}

	bi := bitmapInfo{Header: bitmapInfoHeader{
		Size: uint32(unsafe.Sizeof(bitmapInfoHeader{})), Width: int32(tw), Height: -int32(th),
		Planes: 1, BitCount: 32,
	}}
	img := image.NewRGBA(image.Rect(0, 0, tw, th))
	n, _, _ := procGetDIBits.Call(memDC, bmp, 0, uintptr(th), uintptr(unsafe.Pointer(&img.Pix[0])), uintptr(unsafe.Pointer(&bi)), 0)
	if n == 0 {
		return nil, errors.New("画面を取り込めません")
	}
	// BGRA → RGBA
	p := img.Pix
	for i := 0; i+3 < len(p); i += 4 {
		p[i], p[i+2] = p[i+2], p[i]
		p[i+3] = 255
	}

	// マウスカーソルの位置に印を付ける
	var pt point
	if c, _, _ := procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt))); c != 0 {
		drawCursorMark(img, int(pt.X)*tw/sw, int(pt.Y)*th/sh)
	}
	return img, nil
}
