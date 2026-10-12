package main

// 音声デバイスの状態 (スマホへ送る)
type audioDevice struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Default bool   `json:"default"`
}

type audioInfo struct {
	Volume  int           `json:"vol"`
	Mute    bool          `json:"mute"`
	Devices []audioDevice `json:"devices"`
	Error   string        `json:"error,omitempty"`
}
