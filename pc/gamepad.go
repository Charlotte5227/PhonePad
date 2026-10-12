package main

import (
	"log"
	"sync"
	"time"
)

// Xbox 360 (XUSB) のボタンビット。トリガーは独自に16/17ビット目を使う
const (
	xDpadUp    = 0x0001
	xDpadDown  = 0x0002
	xDpadLeft  = 0x0004
	xDpadRight = 0x0008
	xStart     = 0x0010
	xBack      = 0x0020
	xLThumb    = 0x0040
	xRThumb    = 0x0080
	xLB        = 0x0100
	xRB        = 0x0200
	xGuide     = 0x0400
	xA         = 0x1000
	xB         = 0x2000
	xX         = 0x4000
	xY         = 0x8000
	xLT        = 1 << 16
	xRT        = 1 << 17

	// ターボの対象外: + - ホーム
	turboExcluded = xStart | xBack | xGuide
)

type xusbReport struct {
	Buttons  uint16
	LTrigger uint8
	RTrigger uint8
	LX, LY   int16
	RX, RY   int16
}

type padSink interface {
	update(r xusbReport) error
	close()
}

type padState struct {
	held   uint32 // 指で押されているボタン
	auto   uint32 // 個別の自動連打が有効なボタン
	global bool   // 全体ターボ (押している間連打)
	rate   int    // 1秒あたりの連打回数
	lx, ly int16
	rx, ry int16
}

type gamepad struct {
	dead bool

	mu     sync.Mutex
	st     padState
	sink   padSink
	last   xusbReport
	sent   bool
	starts [18]time.Time // 各ボタンが連打を始めた時刻 (押した瞬間から確実に入力されるように)
	active uint32
	stop   chan struct{}
	wake   chan struct{}

	errLogged bool
}

func newGamepad(onRumble func(large, small byte)) (*gamepad, error) {
	sink, err := openPadSink(onRumble)
	if err != nil {
		return nil, err
	}
	g := &gamepad{sink: sink, stop: make(chan struct{}), wake: make(chan struct{}, 1)}
	go g.loop()
	return g, nil
}

func (g *gamepad) setState(st padState) {
	if g.dead {
		return
	}
	if st.rate < 1 {
		st.rate = 1
	}
	if st.rate > 30 {
		st.rate = 30
	}
	g.mu.Lock()
	g.st = st
	g.mu.Unlock()
	select {
	case g.wake <- struct{}{}:
	default:
	}
}

func (g *gamepad) close() {
	if g.dead {
		return
	}
	close(g.stop)
}

func (g *gamepad) loop() {
	defer guard("コントローラー")
	t := time.NewTicker(4 * time.Millisecond)
	defer t.Stop()
	for {
		select {
		case <-g.stop:
			_ = g.sink.update(xusbReport{})
			g.sink.close()
			return
		case <-t.C:
		case <-g.wake:
		}
		g.tick(time.Now())
	}
}

// 現在の状態から実際に出す入力を計算する
func (g *gamepad) compute(now time.Time) xusbReport {
	g.mu.Lock()
	st := g.st
	g.mu.Unlock()

	turboSet := st.auto
	if st.global {
		turboSet |= st.held
	}
	turboSet &^= turboExcluded

	// 連打対象になった瞬間の時刻を記録
	for i := 0; i < 18; i++ {
		bit := uint32(1) << i
		if turboSet&bit != 0 && g.active&bit == 0 {
			g.starts[i] = now
		}
	}
	g.active = turboSet

	rate := st.rate
	if rate < 1 {
		rate = 15 // 設定が届く前でも0で割らないように
	}
	period := time.Second / time.Duration(rate)
	out := st.held &^ turboSet
	for i := 0; i < 18; i++ {
		bit := uint32(1) << i
		if turboSet&bit == 0 {
			continue
		}
		el := now.Sub(g.starts[i]) % period
		if el < period/2 {
			out |= bit
		}
	}

	r := xusbReport{
		Buttons: uint16(out & 0xFFFF),
		LX:      st.lx, LY: st.ly, RX: st.rx, RY: st.ry,
	}
	if out&xLT != 0 {
		r.LTrigger = 255
	}
	if out&xRT != 0 {
		r.RTrigger = 255
	}
	return r
}

func (g *gamepad) tick(now time.Time) {
	r := g.compute(now)
	if g.sent && r == g.last {
		return
	}
	if err := g.sink.update(r); err == nil {
		g.last = r
		g.sent = true
	} else if !g.errLogged {
		g.errLogged = true
		log.Println("コントローラーの更新に失敗:", err)
	}
}
