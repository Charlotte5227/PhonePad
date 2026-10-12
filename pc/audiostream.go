package main

import (
	"bufio"
	"crypto/subtle"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"log"
	"math"
	"net"
	"strings"
	"sync"
	"time"
)

// PCで鳴っている音をスマホへ流す (16bit ステレオ PCM、暗号化)

const audioPort = 47805

type audioHub struct {
	mu      sync.Mutex
	subs    map[chan []byte]struct{}
	rate    int
	stopCap func()
}

var hub = &audioHub{subs: map[chan []byte]struct{}{}}

// 聞く人が来たら取り込みを始め、いなくなったら止める
func (h *audioHub) subscribe() (chan []byte, int, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.stopCap == nil {
		rate, stop, err := startLoopback(h.publish)
		if err != nil {
			return nil, 0, err
		}
		h.rate, h.stopCap = rate, stop
		log.Printf("PCの音の送信を開始しました (%d Hz)", rate)
	}
	c := make(chan []byte, 12)
	h.subs[c] = struct{}{}
	return c, h.rate, nil
}

func (h *audioHub) unsubscribe(c chan []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	delete(h.subs, c)
	if len(h.subs) == 0 && h.stopCap != nil {
		h.stopCap()
		h.stopCap = nil
		log.Println("PCの音の送信を止めました")
	}
}

func (h *audioHub) publish(b []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.subs {
		select {
		case c <- b:
		default: // スマホ側が追いつかないときは捨てる (遅れがたまらないように)
		}
	}
}

func (s *server) runAudioServer() {
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", audioPort))
	if err != nil {
		log.Println("音の送信を開始できませんでした:", err)
		return
	}
	for {
		c, err := ln.Accept()
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		if tc, ok := c.(*net.TCPConn); ok {
			_ = tc.SetNoDelay(true)
		}
		go s.handleAudio(tls.Server(c, s.tlsConfig()))
	}
}

func (s *server) handleAudio(c net.Conn) {
	defer guard("音の送信")
	defer c.Close()
	ip, _, _ := net.SplitHostPort(c.RemoteAddr().String())
	if s.blocked(ip) {
		return
	}
	_ = c.SetReadDeadline(time.Now().Add(10 * time.Second))
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil {
		return
	}
	f := strings.Fields(line)
	if len(f) < 2 || f[0] != "AUDIO" || subtle.ConstantTimeCompare([]byte(f[1]), []byte(s.code())) != 1 {
		s.addFail(ip)
		_, _ = c.Write([]byte("NG\n"))
		return
	}
	ch, rate, err := hub.subscribe()
	if err != nil {
		log.Println("PCの音を取り込めません:", err)
		_, _ = c.Write([]byte("NG " + err.Error() + "\n"))
		return
	}
	defer hub.unsubscribe(ch)
	_ = c.SetReadDeadline(time.Time{})
	_ = c.SetWriteDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte(fmt.Sprintf("OK %d 2\n", rate))); err != nil {
		return
	}
	// スマホが切断したことに気づけるように、読み込み側も見張る
	closed := make(chan struct{})
	go func() {
		buf := make([]byte, 16)
		for {
			if _, err := c.Read(buf); err != nil {
				close(closed)
				return
			}
		}
	}()
	for {
		select {
		case <-closed:
			return
		case b := <-ch:
			_ = c.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if _, err := c.Write(b); err != nil {
				return
			}
		}
	}
}

// 取り込んだ音 (float32 / 16bit / 32bit、チャンネル数いろいろ) を 16bit ステレオにする。
// 88.2kHz 以上は半分ずつに間引いて 48kHz 前後にする。
func toS16Stereo(raw []byte, frames, channels, bits int, isFloat bool, decim int) []byte {
	if channels < 1 || frames <= 0 {
		return nil
	}
	bps := bits / 8
	sample := func(frame, ch int) float64 {
		if ch >= channels {
			ch = channels - 1
		}
		off := (frame*channels + ch) * bps
		if off+bps > len(raw) {
			return 0
		}
		switch {
		case isFloat && bits == 32:
			return float64(math.Float32frombits(binary.LittleEndian.Uint32(raw[off:])))
		case bits == 16:
			return float64(int16(binary.LittleEndian.Uint16(raw[off:]))) / 32768
		case bits == 32:
			return float64(int32(binary.LittleEndian.Uint32(raw[off:]))) / 2147483648
		case bits == 24:
			v := int32(raw[off]) | int32(raw[off+1])<<8 | int32(int8(raw[off+2]))<<16
			return float64(v) / 8388608
		}
		return 0
	}
	if decim < 1 {
		decim = 1
	}
	outFrames := frames / decim
	out := make([]byte, outFrames*4)
	for i := 0; i < outFrames; i++ {
		for c := 0; c < 2; c++ {
			var sum float64
			for k := 0; k < decim; k++ {
				sum += sample(i*decim+k, c)
			}
			v := sum / float64(decim)
			if v > 1 {
				v = 1
			} else if v < -1 {
				v = -1
			}
			binary.LittleEndian.PutUint16(out[i*4+c*2:], uint16(int16(v*32767)))
		}
	}
	return out
}

// 高すぎるサンプリング周波数を何分の1にするか
func decimation(rate int) int {
	d := 1
	for rate/d > 48000 && (rate/d)%2 == 0 {
		d *= 2
	}
	return d
}
