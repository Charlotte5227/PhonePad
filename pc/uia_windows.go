//go:build windows

package main

import (
	"errors"
	"syscall"
	"unsafe"
)

// UI オートメーションで、PC上で入力中の欄の文字とカーソル位置を読み書きする

var (
	clsidCUIAutomation = guid{0xFF48DBA4, 0x60EF, 0x4201, [8]byte{0xAA, 0x87, 0x54, 0x10, 0x3E, 0xEF, 0x59, 0x4E}}
	iidIUIAutomation   = guid{0x30CBE57D, 0xD9D0, 0x452A, [8]byte{0xAB, 0x13, 0x7A, 0xC5, 0xAC, 0x48, 0x25, 0xEE}}
	iidTextPattern     = guid{0x32EBA289, 0x3583, 0x42C9, [8]byte{0x9C, 0x59, 0x3B, 0x6D, 0x9A, 0x1E, 0x9B, 0x6A}}
	iidValuePattern    = guid{0xA94CD8B1, 0x0844, 0x4CD6, [8]byte{0x9D, 0x2D, 0x64, 0x05, 0x37, 0xAB, 0x39, 0xE9}}

	uiaWorker = newComWorker("入力欄")
	uiaAuto   *comObj // uiaWorker のスレッドだけで使う
)

const (
	uiaTextPatternID  = 10014
	uiaValuePatternID = 10002
	endpointStart     = 0
	endpointEnd       = 1
	textUnitCharacter = 0
	maxFieldText      = 20000
)

// 関数表の番号 (Windows SDK の UIAutomationClient.h と同じ順番)
const (
	vAutoGetFocusedElement = 8  // IUIAutomation
	vElemGetPatternAs      = 14 // IUIAutomationElement
	vElemIsPassword        = 35
	vTPGetSelection        = 5 // IUIAutomationTextPattern
	vTPDocumentRange       = 7
	vTRClone               = 3 // IUIAutomationTextRange
	vTRGetText             = 12
	vTRMove                = 13
	vTRMoveEndpointByRange = 15
	vTRSelect              = 16
	vTRAGetLength          = 3 // IUIAutomationTextRangeArray
	vTRAGetElement         = 4
	vVPCurrentValue        = 4 // IUIAutomationValuePattern
)

func uiaClient() *comObj {
	if uiaAuto != nil {
		return uiaAuto
	}
	var a *comObj
	hr, _, _ := procCoCreateInstance.Call(
		uintptr(unsafe.Pointer(&clsidCUIAutomation)), 0, 1, /* CLSCTX_INPROC_SERVER */
		uintptr(unsafe.Pointer(&iidIUIAutomation)), uintptr(unsafe.Pointer(&a)))
	if hresultOK(uint32(hr)) && a != nil {
		uiaAuto = a
	}
	return uiaAuto
}

func rangeText(r *comObj, max int) string {
	var b uintptr
	if !hresultOK(hrOf(syscall.SyscallN(r.vtbl[vTRGetText], r.ptr(), uintptr(int64(max)), uintptr(unsafe.Pointer(&b))))) {
		return ""
	}
	return takeBSTR(b)
}

// 文書の先頭から r の端点までの文字数
func offsetOf(doc, r *comObj, ep uintptr) int {
	var c *comObj
	if !hresultOK(hrOf(syscall.SyscallN(doc.vtbl[vTRClone], doc.ptr(), uintptr(unsafe.Pointer(&c))))) || c == nil {
		return -1
	}
	defer c.release()
	if !hresultOK(hrOf(syscall.SyscallN(c.vtbl[vTRMoveEndpointByRange], c.ptr(), endpointEnd, r.ptr(), ep))) {
		return -1
	}
	return utf16Len(rangeText(c, -1))
}

// 入力中の欄に対する処理を、UIAのスレッドで行う
func withFocused(f func(el, tp, doc, vp *comObj) error) error {
	return uiaWorker.run(func() error {
		a := uiaClient()
		if a == nil {
			return errors.New("この機能はこのPCでは使えません")
		}
		var el *comObj
		if !hresultOK(hrOf(syscall.SyscallN(a.vtbl[vAutoGetFocusedElement], a.ptr(), uintptr(unsafe.Pointer(&el))))) || el == nil {
			return errors.New("入力中の欄がありません")
		}
		defer el.release()
		var pw int32
		syscall.SyscallN(el.vtbl[vElemIsPassword], el.ptr(), uintptr(unsafe.Pointer(&pw)))
		if pw != 0 {
			return errors.New("パスワード欄のため表示しません")
		}
		var tp, doc, vp *comObj
		syscall.SyscallN(el.vtbl[vElemGetPatternAs], el.ptr(), uiaTextPatternID, uintptr(unsafe.Pointer(&iidTextPattern)), uintptr(unsafe.Pointer(&tp)))
		if tp != nil {
			defer tp.release()
			syscall.SyscallN(tp.vtbl[vTPDocumentRange], tp.ptr(), uintptr(unsafe.Pointer(&doc)))
			if doc != nil {
				defer doc.release()
			}
		}
		if doc == nil {
			syscall.SyscallN(el.vtbl[vElemGetPatternAs], el.ptr(), uiaValuePatternID, uintptr(unsafe.Pointer(&iidValuePattern)), uintptr(unsafe.Pointer(&vp)))
			if vp != nil {
				defer vp.release()
			}
		}
		if doc == nil && vp == nil {
			return errors.New("この場所の文字は読み取れません")
		}
		return f(el, tp, doc, vp)
	})
}

func readFocusedField() fieldState {
	st := fieldState{Caret: -1, SelEnd: -1}
	err := withFocused(func(el, tp, doc, vp *comObj) error {
		if doc != nil {
			st.Text = rangeText(doc, maxFieldText)
			var arr *comObj
			syscall.SyscallN(tp.vtbl[vTPGetSelection], tp.ptr(), uintptr(unsafe.Pointer(&arr)))
			if arr != nil {
				defer arr.release()
				var n int32
				syscall.SyscallN(arr.vtbl[vTRAGetLength], arr.ptr(), uintptr(unsafe.Pointer(&n)))
				if n > 0 {
					var r *comObj
					syscall.SyscallN(arr.vtbl[vTRAGetElement], arr.ptr(), 0, uintptr(unsafe.Pointer(&r)))
					if r != nil {
						st.Caret = offsetOf(doc, r, endpointStart)
						st.SelEnd = offsetOf(doc, r, endpointEnd)
						r.release()
					}
				}
			}
		} else {
			var b uintptr
			syscall.SyscallN(vp.vtbl[vVPCurrentValue], vp.ptr(), uintptr(unsafe.Pointer(&b)))
			st.Text = takeBSTR(b)
		}
		st.OK = true
		return nil
	})
	if err != nil {
		st.Reason = err.Error()
	}
	if n := utf16Len(st.Text); st.Caret > n {
		st.Caret = n
	}
	return st
}

// カーソルを文書の先頭から offset 文字目へ動かす
func setFocusedCaret(offset int) error {
	var fallbackText *string
	err := withFocused(func(el, tp, doc, vp *comObj) error {
		if doc == nil {
			t := ""
			var b uintptr
			syscall.SyscallN(vp.vtbl[vVPCurrentValue], vp.ptr(), uintptr(unsafe.Pointer(&b)))
			t = takeBSTR(b)
			fallbackText = &t
			return nil
		}
		var r *comObj
		if !hresultOK(hrOf(syscall.SyscallN(doc.vtbl[vTRClone], doc.ptr(), uintptr(unsafe.Pointer(&r))))) || r == nil {
			return errors.New("カーソルを動かせません")
		}
		defer r.release()
		// 先頭の位置に縮めてから、目的の位置まで動かす (改行の数え方の違いを補正しながら)
		syscall.SyscallN(r.vtbl[vTRMoveEndpointByRange], r.ptr(), endpointEnd, doc.ptr(), endpointStart)
		pos := 0
		for i := 0; i < 4; i++ {
			delta := offset - pos
			if delta == 0 {
				break
			}
			var moved int32
			syscall.SyscallN(r.vtbl[vTRMove], r.ptr(), textUnitCharacter, uintptr(int64(delta)), uintptr(unsafe.Pointer(&moved)))
			np := offsetOf(doc, r, endpointStart)
			if np < 0 || np == pos {
				break
			}
			pos = np
		}
		if !hresultOK(hrOf(syscall.SyscallN(r.vtbl[vTRSelect], r.ptr()))) {
			return errors.New("このアプリではカーソルを動かせません")
		}
		return nil
	})
	if err != nil {
		return err
	}
	if fallbackText != nil {
		// 文字の取得しかできない欄: Ctrl+Home で先頭へ行き、右矢印で進める
		in.moveCaretByKeys(caretSteps(*fallbackText, offset))
	}
	return nil
}
