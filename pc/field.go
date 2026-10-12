package main

import "unicode/utf16"

// PC上で入力中の欄の状態 (スマホへ送る)
type fieldState struct {
	OK     bool   `json:"ok"`
	Text   string `json:"text"`
	Caret  int    `json:"caret"`  // カーソル位置 (-1=不明)
	SelEnd int    `json:"selEnd"` // 選択範囲の終わり
	Reason string `json:"reason,omitempty"`
}

func utf16Len(s string) int { return len(utf16.Encode([]rune(s))) }

// 文字列の先頭から offset (UTF-16単位) までに右矢印を何回押せばよいか (改行CRLFは1回)
func caretSteps(text string, offset int) int {
	u := utf16.Encode([]rune(text))
	if offset > len(u) {
		offset = len(u)
	}
	steps := 0
	for i := 0; i < offset; i++ {
		if u[i] == '\r' && i+1 < len(u) && u[i+1] == '\n' {
			continue
		}
		if u[i] >= 0xDC00 && u[i] <= 0xDFFF {
			continue // サロゲートペアの後半
		}
		steps++
	}
	return steps
}
