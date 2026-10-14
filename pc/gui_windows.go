//go:build windows

package main

// 通知領域 (タスクバー右下) のアイコンと、画面の隅の「スマホが接続中」の表示。
// ウィンドウは1つのスレッドでまとめて動かし、他のゴルーチンからは PostMessage で知らせる。

import (
	"bytes"
	"errors"
	"image"
	"image/png"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	procRegisterClassExW        = user32.NewProc("RegisterClassExW")
	procCreateWindowExW         = user32.NewProc("CreateWindowExW")
	procDefWindowProcW          = user32.NewProc("DefWindowProcW")
	procGetMessageW             = user32.NewProc("GetMessageW")
	procTranslateMessage        = user32.NewProc("TranslateMessage")
	procDispatchMessageW        = user32.NewProc("DispatchMessageW")
	procCreatePopupMenu         = user32.NewProc("CreatePopupMenu")
	procAppendMenuW             = user32.NewProc("AppendMenuW")
	procSetMenuDefaultItem      = user32.NewProc("SetMenuDefaultItem")
	procTrackPopupMenu          = user32.NewProc("TrackPopupMenu")
	procDestroyMenu             = user32.NewProc("DestroyMenu")
	procSetForegroundWindow     = user32.NewProc("SetForegroundWindow")
	procRegisterWindowMessageW  = user32.NewProc("RegisterWindowMessageW")
	procShowWindow              = user32.NewProc("ShowWindow")
	procSetWindowPos            = user32.NewProc("SetWindowPos")
	procInvalidateRect          = user32.NewProc("InvalidateRect")
	procBeginPaint              = user32.NewProc("BeginPaint")
	procEndPaint                = user32.NewProc("EndPaint")
	procFillRect                = user32.NewProc("FillRect")
	procDrawTextW               = user32.NewProc("DrawTextW")
	procSetWindowRgn            = user32.NewProc("SetWindowRgn")
	procSetLayeredWindowAttribs = user32.NewProc("SetLayeredWindowAttributes")
	procSystemParametersInfoW   = user32.NewProc("SystemParametersInfoW")
	procSetWindowDisplayAffin   = user32.NewProc("SetWindowDisplayAffinity")
	procCreateIconIndirect      = user32.NewProc("CreateIconIndirect")
	procGetDpiForSystem         = user32.NewProc("GetDpiForSystem")
	procLoadCursorW             = user32.NewProc("LoadCursorW")
	procLoadIconW               = user32.NewProc("LoadIconW")
	procShellNotifyIconW        = shell32.NewProc("Shell_NotifyIconW")
	procSHGetKnownFolderPath    = shell32.NewProc("SHGetKnownFolderPath")
	procAddClipboardListener    = user32.NewProc("AddClipboardFormatListener")
	procGetModuleHandleW        = kernel32.NewProc("GetModuleHandleW")
	procCreateSolidBrush        = gdi32.NewProc("CreateSolidBrush")
	procCreatePen               = gdi32.NewProc("CreatePen")
	procCreateFontW             = gdi32.NewProc("CreateFontW")
	procSetTextColor            = gdi32.NewProc("SetTextColor")
	procSetBkMode               = gdi32.NewProc("SetBkMode")
	procCreateRoundRectRgn      = gdi32.NewProc("CreateRoundRectRgn")
	procRoundRect               = gdi32.NewProc("RoundRect")
	procEllipse                 = gdi32.NewProc("Ellipse")
	procCreateDIBSection        = gdi32.NewProc("CreateDIBSection")
	procCreateBitmap            = gdi32.NewProc("CreateBitmap")
	procGetDeviceCaps           = gdi32.NewProc("GetDeviceCaps")
)

const (
	wmDestroy       = 0x0002
	wmPaint         = 0x000F
	wmNull          = 0x0000
	wmMouseActivate = 0x0021
	wmLButtonUp     = 0x0202
	wmLButtonDblClk = 0x0203
	wmRButtonUp     = 0x0205
	wmContextMenu   = 0x007B
	wmApp           = 0x8000
	wmTray          = wmApp + 1 // 通知領域のアイコンが押された
	wmRefresh       = wmApp + 2 // 接続中のスマホが変わった
	wmBalloon       = wmApp + 3 // お知らせを出す

	nimAdd    = 0
	nimModify = 1
	nimDelete = 2
	nifMsg    = 0x01
	nifIcon   = 0x02
	nifTip    = 0x04
	nifInfo   = 0x10

	menuOpen    = 1
	menuKickAll = 2
	menuOverlay = 3
	menuQuit    = 4
	menuInfo    = 5
	menuRing    = 6

	wmClipboardUpdate   = 0x031D
	ninBalloonUserClick = 0x0405 // お知らせが押された
)

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   uintptr
	Icon       uintptr
	Cursor     uintptr
	Background uintptr
	MenuName   *uint16
	ClassName  *uint16
	IconSm     uintptr
}

type winMsg struct {
	Hwnd    uintptr
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
	Private uint32
}

type winRect struct{ Left, Top, Right, Bottom int32 }

type paintStruct struct {
	Hdc         uintptr
	Erase       int32
	Paint       winRect
	Restore     int32
	IncUpdate   int32
	RgbReserved [32]byte
}

type notifyIconData struct {
	Size            uint32
	Wnd             uintptr
	ID              uint32
	Flags           uint32
	CallbackMessage uint32
	Icon            uintptr
	Tip             [128]uint16
	State           uint32
	StateMask       uint32
	Info            [256]uint16
	TimeoutVersion  uint32
	InfoTitle       [64]uint16
	InfoFlags       uint32
	GuidItem        [16]byte
	BalloonIcon     uintptr
}

type iconInfo struct {
	FIcon    int32
	XHotspot uint32
	YHotspot uint32
	HbmMask  uintptr
	HbmColor uintptr
}

type guiState struct {
	srv            *server
	tray           uintptr // 通知領域のアイコン用の見えないウィンドウ
	overlay        uintptr // 画面の隅の表示
	icon           uintptr
	taskbarCreated uint32 // エクスプローラーが再起動したときの知らせ
	scale          float64
	font, fontBold uintptr
	names          []string
	btn            winRect // 「切断」ボタンの位置
	width, height  int32

	mu           sync.Mutex
	balloonTitle string
	balloonText  string
	balloonPath  string // お知らせを押したときに開くファイル
}

var (
	guiMu  sync.Mutex
	theGUI *guiState
)

func currentGUI() *guiState {
	guiMu.Lock()
	defer guiMu.Unlock()
	return theGUI
}

func guiAvailable() bool { return currentGUI() != nil }

func startGUI(s *server) {
	ready := make(chan struct{})
	go func() {
		// ウィンドウは作ったスレッドでしか動かせないので、このゴルーチンをスレッドに固定する
		runtime.LockOSThread()
		defer guard("通知領域")
		g := &guiState{srv: s}
		if err := g.init(); err != nil {
			log.Println("通知領域のアイコンを作れませんでした:", err)
			close(ready)
			return
		}
		guiMu.Lock()
		theGUI = g
		guiMu.Unlock()
		close(ready)
		g.refresh()
		g.loop()
	}()
	select {
	case <-ready:
	case <-time.After(3 * time.Second):
	}
}

// 接続中のスマホの表示を更新する (どのゴルーチンからでも呼べる)
func guiRefresh() {
	if g := currentGUI(); g != nil {
		procPostMessageW.Call(g.tray, wmRefresh, 0, 0)
	}
}

func guiBalloon(title, text string) { guiBalloonPath(title, text, "") }

func guiBalloonPath(title, text, path string) {
	g := currentGUI()
	if g == nil {
		return
	}
	g.mu.Lock()
	g.balloonTitle, g.balloonText, g.balloonPath = title, text, path
	g.mu.Unlock()
	procPostMessageW.Call(g.tray, wmBalloon, 0, 0)
}

// 終わる前に通知領域のアイコンを消す (残ると、マウスを乗せるまで消えない)
func guiShutdown() {
	if g := currentGUI(); g != nil {
		nid := g.nid()
		procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&nid)))
	}
}

// PhonePad の画面を開く。Edge のアプリ表示 (アドレスバーのない窓) で開き、無ければいつものブラウザで開く
func openManager() {
	u := managerURL()
	if err := shellExec("msedge.exe", "--app="+u+" --window-size=1080,820"); err != nil {
		openURL(u)
	}
}

func managerURL() string { return "http://127.0.0.1:" + strconv.Itoa(statusPort) + "/" }

func shellExec(file, params string) error {
	verb, _ := syscall.UTF16PtrFromString("open")
	f, err := syscall.UTF16PtrFromString(file)
	if err != nil {
		return err
	}
	p, err := syscall.UTF16PtrFromString(params)
	if err != nil {
		return err
	}
	r, _, _ := procShellExecuteW.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(f)), uintptr(unsafe.Pointer(p)), 0, 1)
	if r <= 32 {
		return errors.New("ShellExecute failed")
	}
	return nil
}

// ---------------------------------------------------------------- 準備

var wndProcCB = syscall.NewCallback(func(hwnd, msg, wp, lp uintptr) uintptr {
	if g := currentGUI(); g != nil {
		if r, ok := g.handle(hwnd, uint32(msg), wp, lp); ok {
			return r
		}
	}
	r, _, _ := procDefWindowProcW.Call(hwnd, msg, wp, lp)
	return r
})

func (g *guiState) init() error {
	inst, _, _ := procGetModuleHandleW.Call(0)
	hand, _, _ := procLoadCursorW.Call(0, 32649) // IDC_HAND

	trayClass, _ := syscall.UTF16PtrFromString("PhonePadTray")
	wc := wndClassEx{WndProc: wndProcCB, Instance: inst, ClassName: trayClass}
	wc.Size = uint32(unsafe.Sizeof(wc))
	if r, _, err := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
		return err
	}
	ovClass, _ := syscall.UTF16PtrFromString("PhonePadOverlay")
	wc2 := wndClassEx{WndProc: wndProcCB, Instance: inst, ClassName: ovClass, Cursor: hand}
	wc2.Size = uint32(unsafe.Sizeof(wc2))
	if r, _, err := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc2))); r == 0 {
		return err
	}

	title, _ := syscall.UTF16PtrFromString("PhonePad")
	g.tray, _, _ = procCreateWindowExW.Call(0, uintptr(unsafe.Pointer(trayClass)), uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0, 0, 0, inst, 0)
	if g.tray == 0 {
		return errors.New("CreateWindowEx (tray) failed")
	}
	// 最前面・タスクバーに出さない・押しても前に出てこない・半透明
	const exStyle = 0x00000008 | 0x00000080 | 0x08000000 | 0x00080000
	g.overlay, _, _ = procCreateWindowExW.Call(exStyle, uintptr(unsafe.Pointer(ovClass)), uintptr(unsafe.Pointer(title)),
		0x80000000 /* WS_POPUP */, 0, 0, 10, 10, 0, 0, inst, 0)
	if g.overlay != 0 {
		procSetLayeredWindowAttribs.Call(g.overlay, 0, 238, 2 /* LWA_ALPHA */)
		// PCの画面をスマホに映すときには写らないようにする (Windows 10 2004 以降)
		procSetWindowDisplayAffin.Call(g.overlay, 0x11 /* WDA_EXCLUDEFROMCAPTURE */)
	}

	tc, _ := syscall.UTF16PtrFromString("TaskbarCreated")
	r, _, _ := procRegisterWindowMessageW.Call(uintptr(unsafe.Pointer(tc)))
	g.taskbarCreated = uint32(r)

	g.scale = systemScale()
	face, _ := syscall.UTF16PtrFromString("Yu Gothic UI")
	px := func(n float64) uintptr { return uintptr(int32(-n * g.scale)) } // 負の値=文字の高さ
	g.font, _, _ = procCreateFontW.Call(px(14), 0, 0, 0, 400, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(face)))
	g.fontBold, _, _ = procCreateFontW.Call(px(14), 0, 0, 0, 700, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(face)))

	sm, _, _ := procGetSystemMetrics.Call(49) // SM_CXSMICON
	g.icon = makeIcon(int(sm))
	if g.icon == 0 {
		g.icon, _, _ = procLoadIconW.Call(0, 32512) // IDI_APPLICATION
	}
	g.addTray()
	// PCのクリップボードが変わったら知らせてもらう (スマホとの自動共有)
	procAddClipboardListener.Call(g.tray)
	return nil
}

func systemScale() float64 {
	if procGetDpiForSystem.Find() == nil {
		if d, _, _ := procGetDpiForSystem.Call(); d > 0 {
			return float64(d) / 96
		}
	}
	dc, _, _ := procGetDC.Call(0)
	defer procReleaseDC.Call(0, dc)
	if d, _, _ := procGetDeviceCaps.Call(dc, 88 /* LOGPIXELSX */); d > 0 {
		return float64(d) / 96
	}
	return 1
}

func (g *guiState) nid() notifyIconData {
	var n notifyIconData
	n.Size = uint32(unsafe.Sizeof(n))
	n.Wnd = g.tray
	n.ID = 1
	return n
}

func putUTF16(dst []uint16, s string) {
	u := utf16.Encode([]rune(s))
	if len(u) > len(dst)-1 {
		u = u[:len(dst)-1]
	}
	copy(dst, u)
	dst[len(u)] = 0
}

func (g *guiState) addTray() {
	n := g.nid()
	n.Flags = nifMsg | nifIcon | nifTip
	n.CallbackMessage = wmTray
	n.Icon = g.icon
	putUTF16(n.Tip[:], g.tipText())
	procShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&n)))
}

func (g *guiState) tipText() string {
	switch len(g.names) {
	case 0:
		return "PhonePad PC（接続を待っています）"
	case 1:
		return "PhonePad PC（" + g.names[0] + " が接続中）"
	default:
		return "PhonePad PC（スマホ " + strconv.Itoa(len(g.names)) + " 台が接続中）"
	}
}

func (g *guiState) loop() {
	var m winMsg
	for {
		r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			return
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
}

// ---------------------------------------------------------------- 知らせを受ける

func (g *guiState) handle(hwnd uintptr, msg uint32, wp, lp uintptr) (uintptr, bool) {
	if msg == g.taskbarCreated && g.taskbarCreated != 0 {
		g.addTray()
		return 0, true
	}
	if hwnd == g.tray {
		switch msg {
		case wmTray:
			switch uint32(lp) & 0xFFFF {
			case wmLButtonUp, wmLButtonDblClk:
				go openManager()
			case wmRButtonUp, wmContextMenu:
				g.menu()
			case ninBalloonUserClick:
				g.mu.Lock()
				p := g.balloonPath
				g.mu.Unlock()
				if p != "" {
					// 受け取ったファイルを選んだ状態でフォルダを開く
					go func() { _ = shellExec("explorer.exe", `/select,"`+p+`"`) }()
				}
			}
			return 0, true
		case wmClipboardUpdate:
			go g.srv.clipboardChanged()
			return 0, true
		case wmRefresh:
			g.refresh()
			return 0, true
		case wmBalloon:
			g.balloon()
			return 0, true
		}
		return 0, false
	}
	if hwnd == g.overlay && g.overlay != 0 {
		switch msg {
		case wmMouseActivate:
			return 3, true // MA_NOACTIVATE: 押しても他のウィンドウから入力先を奪わない
		case wmPaint:
			g.paint()
			return 0, true
		case wmLButtonUp:
			x := int32(int16(lp & 0xFFFF))
			y := int32(int16((lp >> 16) & 0xFFFF))
			if x >= g.btn.Left && x <= g.btn.Right && y >= g.btn.Top && y <= g.btn.Bottom {
				go g.srv.kickAll()
			} else {
				go openManager()
			}
			return 0, true
		}
	}
	return 0, false
}

func (g *guiState) menu() {
	h, _, _ := procCreatePopupMenu.Call()
	if h == 0 {
		return
	}
	defer procDestroyMenu.Call(h)
	add := func(flags uint32, id int, text string) {
		t, _ := syscall.UTF16PtrFromString(text)
		procAppendMenuW.Call(h, uintptr(flags), uintptr(id), uintptr(unsafe.Pointer(t)))
	}
	const grayed, checked, sep = 0x1, 0x8, 0x800
	add(0, menuOpen, "PhonePad の画面を開く")
	procSetMenuDefaultItem.Call(h, menuOpen, 0)
	add(sep, 0, "")
	info := "接続中のスマホ: なし"
	if len(g.names) == 1 {
		info = "接続中: " + g.names[0]
	} else if len(g.names) > 1 {
		info = "接続中のスマホ: " + strconv.Itoa(len(g.names)) + " 台"
	}
	add(grayed, menuInfo, info)
	kf := uint32(0)
	if len(g.names) == 0 {
		kf = grayed
	}
	add(kf, menuRing, "スマホを鳴らす（探す）")
	add(kf, menuKickAll, "すべてのスマホを切断")
	add(sep, 0, "")
	of := uint32(0)
	if g.srv.overlayOn() {
		of = checked
	}
	add(of, menuOverlay, "接続中は画面の右下に表示する")
	add(sep, 0, "")
	add(0, menuQuit, "PhonePad を終了")

	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroundWindow.Call(g.tray)
	cmd, _, _ := procTrackPopupMenu.Call(h, 0x0100|0x0002 /* TPM_RETURNCMD|TPM_RIGHTBUTTON */, uintptr(pt.X), uintptr(pt.Y), 0, g.tray, 0)
	procPostMessageW.Call(g.tray, wmNull, 0, 0)
	switch int(cmd) {
	case menuOpen:
		go openManager()
	case menuKickAll:
		go g.srv.kickAll()
	case menuRing:
		go g.srv.ringPhones(nil, true)
	case menuOverlay:
		g.srv.setOverlay(!g.srv.overlayOn())
	case menuQuit:
		log.Println("通知領域から終了しました")
		appExit(0)
	}
}

func (g *guiState) balloon() {
	g.mu.Lock()
	title, text := g.balloonTitle, g.balloonText
	g.mu.Unlock()
	n := g.nid()
	n.Flags = nifInfo
	putUTF16(n.InfoTitle[:], title)
	putUTF16(n.Info[:], text)
	n.InfoFlags = 0x1 // NIIF_INFO
	procShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&n)))
}

// ---------------------------------------------------------------- 画面の隅の表示

func (g *guiState) overlayText() string {
	if len(g.names) == 1 {
		n := []rune(g.names[0])
		if len(n) > 24 {
			n = append(n[:23], '…')
		}
		return "スマホが接続中: " + string(n)
	}
	return "スマホ " + strconv.Itoa(len(g.names)) + " 台が接続中"
}

func (g *guiState) refresh() {
	g.names = g.srv.connectedNames()
	n := g.nid()
	n.Flags = nifTip
	putUTF16(n.Tip[:], g.tipText())
	procShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&n)))

	if g.overlay == 0 {
		return
	}
	if len(g.names) == 0 || !g.srv.overlayOn() {
		procShowWindow.Call(g.overlay, 0) // SW_HIDE
		return
	}
	// 大きさ: 文字の幅 + 「切断」ボタン
	s := func(v float64) int32 { return int32(v*g.scale + 0.5) }
	textW := g.measure(g.overlayText(), g.font)
	btnW := g.measure("切断", g.fontBold) + s(24)
	g.height = s(36)
	g.width = s(14) + s(10) + s(8) + textW + s(12) + btnW + s(6)
	g.btn = winRect{g.width - s(6) - btnW, s(5), g.width - s(6), g.height - s(5)}

	// 画面の右下 (タスクバーの上)
	var work winRect
	procSystemParametersInfoW.Call(0x0030 /* SPI_GETWORKAREA */, 0, uintptr(unsafe.Pointer(&work)), 0)
	x := work.Right - g.width - s(12)
	y := work.Bottom - g.height - s(12)
	const hwndTopmost = ^uintptr(0) // -1
	procSetWindowPos.Call(g.overlay, hwndTopmost, uintptr(x), uintptr(y), uintptr(g.width), uintptr(g.height), 0x0010|0x0040 /* NOACTIVATE|SHOWWINDOW */)
	rgn, _, _ := procCreateRoundRectRgn.Call(0, 0, uintptr(g.width+1), uintptr(g.height+1), uintptr(s(14)), uintptr(s(14)))
	procSetWindowRgn.Call(g.overlay, rgn, 1)
	procInvalidateRect.Call(g.overlay, 0, 1)
}

func (g *guiState) measure(text string, font uintptr) int32 {
	dc, _, _ := procGetDC.Call(g.overlay)
	defer procReleaseDC.Call(g.overlay, dc)
	old, _, _ := procSelectObject.Call(dc, font)
	defer procSelectObject.Call(dc, old)
	t, _ := syscall.UTF16FromString(text)
	var r winRect
	procDrawTextW.Call(dc, uintptr(unsafe.Pointer(&t[0])), uintptr(len(t)-1), uintptr(unsafe.Pointer(&r)), 0x400|0x20|0x800 /* CALCRECT|SINGLELINE|NOPREFIX */)
	return r.Right - r.Left
}

// 色は 0x00BBGGRR
func rgb(r, gg, b byte) uintptr { return uintptr(r) | uintptr(gg)<<8 | uintptr(b)<<16 }

func (g *guiState) paint() {
	var ps paintStruct
	dc, _, _ := procBeginPaint.Call(g.overlay, uintptr(unsafe.Pointer(&ps)))
	defer procEndPaint.Call(g.overlay, uintptr(unsafe.Pointer(&ps)))
	s := func(v float64) int32 { return int32(v*g.scale + 0.5) }

	bg, _, _ := procCreateSolidBrush.Call(rgb(0x26, 0x22, 0x1E))
	all := winRect{0, 0, g.width, g.height}
	procFillRect.Call(dc, uintptr(unsafe.Pointer(&all)), bg)
	procDeleteObject.Call(bg)

	nullPen, _, _ := procCreatePen.Call(5 /* PS_NULL */, 0, 0)
	oldPen, _, _ := procSelectObject.Call(dc, nullPen)
	// 緑の丸 (つながっている印)
	dot, _, _ := procCreateSolidBrush.Call(rgb(0x6C, 0xC2, 0x8F))
	oldBrush, _, _ := procSelectObject.Call(dc, dot)
	cy := g.height / 2
	procEllipse.Call(dc, uintptr(s(14)), uintptr(cy-s(5)), uintptr(s(24)), uintptr(cy+s(5)))
	procSelectObject.Call(dc, oldBrush)
	procDeleteObject.Call(dot)

	// 「切断」ボタン
	btnBrush, _, _ := procCreateSolidBrush.Call(rgb(0x33, 0x2D, 0x27))
	edge, _, _ := procCreatePen.Call(0, uintptr(s(1)), rgb(0x4E, 0x45, 0x3C))
	procSelectObject.Call(dc, btnBrush)
	procSelectObject.Call(dc, edge)
	procRoundRect.Call(dc, uintptr(g.btn.Left), uintptr(g.btn.Top), uintptr(g.btn.Right), uintptr(g.btn.Bottom), uintptr(s(10)), uintptr(s(10)))
	procSelectObject.Call(dc, oldBrush)
	procSelectObject.Call(dc, oldPen)
	procDeleteObject.Call(btnBrush)
	procDeleteObject.Call(edge)
	procDeleteObject.Call(nullPen)

	procSetBkMode.Call(dc, 1) // TRANSPARENT
	draw := func(text string, font uintptr, color uintptr, r winRect, center bool) {
		old, _, _ := procSelectObject.Call(dc, font)
		procSetTextColor.Call(dc, color)
		t, _ := syscall.UTF16FromString(text)
		flags := uintptr(0x20 | 0x4 | 0x800) // SINGLELINE|VCENTER|NOPREFIX
		if center {
			flags |= 0x1
		}
		procDrawTextW.Call(dc, uintptr(unsafe.Pointer(&t[0])), uintptr(len(t)-1), uintptr(unsafe.Pointer(&r)), flags)
		procSelectObject.Call(dc, old)
	}
	draw(g.overlayText(), g.font, rgb(0xF1, 0xEB, 0xE4), winRect{s(32), 0, g.btn.Left - s(8), g.height}, false)
	draw("切断", g.fontBold, rgb(0xF1, 0xEB, 0xE4), g.btn, true)
}

// ---------------------------------------------------------------- アイコン

// ブラウザ版のアイコン画像から、通知領域用の小さなアイコンを作る
func makeIcon(size int) uintptr {
	if size <= 0 {
		size = 16
	}
	data, err := webappFS.ReadFile("webapp/icon-180.png")
	if err != nil {
		return 0
	}
	src, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		return 0
	}
	px := shrink(src, size)

	bi := bitmapInfo{Header: bitmapInfoHeader{Width: int32(size), Height: -int32(size), Planes: 1, BitCount: 32}}
	bi.Header.Size = uint32(unsafe.Sizeof(bi.Header))
	var bits unsafe.Pointer
	dc, _, _ := procGetDC.Call(0)
	color, _, _ := procCreateDIBSection.Call(dc, uintptr(unsafe.Pointer(&bi)), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	procReleaseDC.Call(0, dc)
	if color == 0 || bits == nil {
		return 0
	}
	defer procDeleteObject.Call(color)
	copy(unsafe.Slice((*byte)(bits), size*size*4), px)

	rowBytes := ((size + 15) / 16) * 2
	maskBits := make([]byte, rowBytes*size)
	mask, _, _ := procCreateBitmap.Call(uintptr(size), uintptr(size), 1, 1, uintptr(unsafe.Pointer(&maskBits[0])))
	if mask == 0 {
		return 0
	}
	defer procDeleteObject.Call(mask)
	ii := iconInfo{FIcon: 1, HbmMask: mask, HbmColor: color}
	h, _, _ := procCreateIconIndirect.Call(uintptr(unsafe.Pointer(&ii)))
	return h
}

// size×size に縮める (面積平均)。BGRA の並びで返す
func shrink(src image.Image, size int) []byte {
	b := src.Bounds()
	out := make([]byte, size*size*4)
	for y := 0; y < size; y++ {
		y0 := b.Min.Y + y*b.Dy()/size
		y1 := b.Min.Y + (y+1)*b.Dy()/size
		if y1 <= y0 {
			y1 = y0 + 1
		}
		for x := 0; x < size; x++ {
			x0 := b.Min.X + x*b.Dx()/size
			x1 := b.Min.X + (x+1)*b.Dx()/size
			if x1 <= x0 {
				x1 = x0 + 1
			}
			var r, gg, bb, a, n uint64
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					cr, cg, cb, ca := src.At(sx, sy).RGBA() // 16bit・乗算済み
					r += uint64(cr)
					gg += uint64(cg)
					bb += uint64(cb)
					a += uint64(ca)
					n++
				}
			}
			i := (y*size + x) * 4
			if a == 0 {
				continue
			}
			// アイコンは乗算していない色を使う
			out[i+0] = byte(bb * 255 / a)
			out[i+1] = byte(gg * 255 / a)
			out[i+2] = byte(r * 255 / a)
			out[i+3] = byte(a / n >> 8)
		}
	}
	return out
}

// スマホからファイルを受け取ったことを知らせる (押すとフォルダが開く)
func guiFileReceived(path, from string) {
	guiBalloonPath("ファイルを受け取りました", filepath.Base(path)+"（"+from+" から）\n押すとフォルダを開きます", path)
}

// ダウンロードフォルダ (場所を変えていればそちら)
func downloadsDir() string {
	// FOLDERID_Downloads {374DE290-123F-4565-9164-39C4925E467B}
	id := [16]byte{0x90, 0xE2, 0x4D, 0x37, 0x3F, 0x12, 0x65, 0x45, 0x91, 0x64, 0x39, 0xC4, 0x92, 0x5E, 0x46, 0x7B}
	var p *uint16
	if r, _, _ := procSHGetKnownFolderPath.Call(uintptr(unsafe.Pointer(&id[0])), 0, 0, uintptr(unsafe.Pointer(&p))); r == 0 && p != nil {
		defer procCoTaskMemFree.Call(uintptr(unsafe.Pointer(p)))
		return syscall.UTF16ToString(unsafe.Slice(p, 1024))
	}
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, "Downloads")
	}
	return ""
}

var procMessageBoxW = user32.NewProc("MessageBoxW")

// コマンドから使ったときのエラー (黒いウィンドウが無いので、小さな窓で出す)
func cliError(msg string) {
	t, _ := syscall.UTF16PtrFromString("PhonePad")
	m, _ := syscall.UTF16PtrFromString(msg)
	procMessageBoxW.Call(0, uintptr(unsafe.Pointer(m)), uintptr(unsafe.Pointer(t)), 0x30 /* MB_ICONWARNING */)
}
