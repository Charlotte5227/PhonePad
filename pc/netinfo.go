package main

import (
	"net"
	"strings"
)

// 通信に使っているアドレスを持つネットワークの MAC アドレス
func macForAddr(a net.Addr) string {
	host, _, err := net.SplitHostPort(a.String())
	if err != nil {
		return ""
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return ""
	}
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		addrs, _ := ifc.Addrs()
		for _, ad := range addrs {
			if ipn, ok := ad.(*net.IPNet); ok && ipn.IP.Equal(ip) && len(ifc.HardwareAddr) == 6 {
				return strings.ToUpper(ifc.HardwareAddr.String())
			}
		}
	}
	return ""
}

// PCの状態 (スマホへ送る)
type sysInfo struct {
	CPUName   string  `json:"cpuName"`
	CPU       float64 `json:"cpu"`       // CPU使用率 %
	MemUsed   uint64  `json:"memUsed"`   // バイト
	MemTotal  uint64  `json:"memTotal"`  // バイト
	Battery   int     `json:"battery"`   // % (-1 = 電池なし)
	Charging  bool    `json:"charging"`  // 電源につながっている
	DiskFree  uint64  `json:"diskFree"`  // Cドライブの空き
	DiskTotal uint64  `json:"diskTotal"` //
	Uptime    uint64  `json:"uptime"`    // 秒
}

// PCのアプリ (スタートメニューのショートカット)
type appEntry struct {
	Name string `json:"n"`
	Path string `json:"p"`
}
