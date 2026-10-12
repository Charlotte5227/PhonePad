package main

import (
	"bytes"
	"crypto/subtle"
	"crypto/tls"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"log"
	"net"
	"net/http"
	"strconv"
	"time"
)

const screenPort = 47804

// カーソル位置に見やすい輪を描く
func drawCursorMark(img *image.RGBA, cx, cy int) {
	b := img.Bounds()
	r := b.Dx() / 120
	if r < 5 {
		r = 5
	}
	set := func(x, y int, c color.RGBA) {
		if image.Pt(x, y).In(b) {
			img.SetRGBA(x, y, c)
		}
	}
	outer := color.RGBA{255, 255, 255, 255}
	inner := color.RGBA{224, 120, 30, 255}
	for dy := -r - 2; dy <= r+2; dy++ {
		for dx := -r - 2; dx <= r+2; dx++ {
			d2 := dx*dx + dy*dy
			switch {
			case d2 <= (r-2)*(r-2):
			case d2 <= r*r:
				set(cx+dx, cy+dy, inner)
			case d2 <= (r+2)*(r+2):
				set(cx+dx, cy+dy, outer)
			}
		}
	}
	set(cx, cy, inner)
}

// スマホからの画面表示の要求に答える (接続コードが必要)
func (s *server) screenMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/frame", func(w http.ResponseWriter, r *http.Request) {
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		if s.blocked(ip) {
			http.Error(w, "blocked", http.StatusTooManyRequests)
			return
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("X-Code")), []byte(s.code())) != 1 {
			s.addFail(ip)
			http.Error(w, "code", http.StatusForbidden)
			return
		}
		width, _ := strconv.Atoi(r.URL.Query().Get("w"))
		if width < 320 || width > 2560 {
			width = 960
		}
		q, _ := strconv.Atoi(r.URL.Query().Get("q"))
		if q < 20 || q > 95 {
			q = 60
		}
		img, err := captureScreen(width)
		if err != nil {
			http.Error(w, err.Error(), http.StatusServiceUnavailable)
			return
		}
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: q}); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Length", strconv.Itoa(buf.Len()))
		_, _ = w.Write(buf.Bytes())
	})
	return mux
}

// 画面の配信 (暗号化)
func (s *server) runScreenServer() {
	srv := &http.Server{
		Addr:              fmt.Sprintf(":%d", screenPort),
		Handler:           s.screenMux(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       60 * time.Second,
		TLSConfig:         s.tlsConfig(),
		TLSNextProto:      map[string]func(*http.Server, *tls.Conn, http.Handler){}, // HTTP/1.1 のみ
	}
	if err := srv.ListenAndServeTLS("", ""); err != nil {
		log.Println("画面表示を開始できませんでした:", err)
	}
}
