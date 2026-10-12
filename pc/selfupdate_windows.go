//go:build windows

package main

import (
	"errors"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

func oldExePath() string {
	exe, _ := os.Executable()
	return filepath.Join(filepath.Dir(exe), "PhonePad.old.exe")
}

// 前回の更新で残った古い exe を消す
func cleanupOldExe() {
	go func() {
		for i := 0; i < 20; i++ {
			if err := os.Remove(oldExePath()); err == nil || os.IsNotExist(err) {
				return
			}
			time.Sleep(time.Second)
		}
	}()
}

func selfUpdate(url string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	tmp := exe + ".new"
	c := &http.Client{Timeout: 5 * time.Minute}
	r, err := c.Get(url)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return errors.New("ダウンロードに失敗しました (" + r.Status + ")")
	}
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	n, err := io.Copy(f, r.Body)
	f.Close()
	if err != nil || n < 1<<20 {
		os.Remove(tmp)
		return errors.New("ダウンロードしたファイルが壊れています")
	}
	head := make([]byte, 2)
	if hf, err := os.Open(tmp); err == nil {
		_, _ = hf.Read(head)
		hf.Close()
	}
	if string(head) != "MZ" {
		os.Remove(tmp)
		return errors.New("ダウンロードしたファイルが正しくありません")
	}
	// 動いている exe は名前を変えることはできるので、古い方を退避して入れ替える
	old := oldExePath()
	_ = os.Remove(old)
	if err := os.Rename(exe, old); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, exe); err != nil {
		_ = os.Rename(old, exe)
		return err
	}
	args := []string{"--after-update"}
	if background {
		args = append(args, "--background")
	}
	cmd := exec.Command(exe, args...)
	cmd.Dir = filepath.Dir(exe)
	if err := cmd.Start(); err != nil {
		_ = os.Rename(exe, tmp)
		_ = os.Rename(old, exe)
		return err
	}
	// 新しい方がポートを使えるように終わる
	time.Sleep(300 * time.Millisecond)
	os.Exit(0)
	return nil
}
