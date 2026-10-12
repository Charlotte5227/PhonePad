package com.phonepad.app

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

/** PCの音量・ミュート・出力先・再生操作のパネル */
class VolumePanel(private val act: Activity) {
    private val dialog = Dialog(act)
    private val h = Handler(Looper.getMainLooper())
    private lateinit var volLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var muteBtn: KeyButton
    private lateinit var devBox: LinearLayout
    private lateinit var errView: TextView
    private var dragging = false
    private var muted = false
    private var lastSent = 0L
    private var lastDevices = ""

    private val poll = object : Runnable {
        override fun run() {
            if (!dragging) Link.send("VQ")
            h.postDelayed(this, 2000)
        }
    }

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, act.dp(18f))
            setPadding(act.dpi(18), act.dpi(16), act.dpi(18), act.dpi(16))
        }
        root.addView(label(act, "PCの音量", 18f, C.INK, true))

        // 音量
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dpi(12), 0, act.dpi(4))
        }
        muteBtn = KeyButton(act, "🔊", 18f).apply {
            onTap = {
                muted = !muted
                refreshMute()
                Link.send("VM ${if (muted) 1 else 0}")
            }
        }
        row.addView(muteBtn, LinearLayout.LayoutParams(act.dpi(52), act.dpi(48)))
        seek = SeekBar(act).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    volLabel.text = "$p"
                    if (!fromUser) return
                    // 動かしている間も1秒に10回まで送る
                    val now = System.currentTimeMillis()
                    if (now - lastSent > 100) {
                        lastSent = now
                        Link.send("VS $p")
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(s: SeekBar?) {
                    dragging = false
                    Link.send("VS ${s?.progress ?: 0}")
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, act.dpi(48), 1f))
        volLabel = label(act, "–", 16f, C.INK, true).apply { gravity = Gravity.CENTER }
        row.addView(volLabel, LinearLayout.LayoutParams(act.dpi(44), -2))
        root.addView(row)

        // PCの音をスマホで聞く
        val audioRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dpi(6), 0, act.dpi(6))
        }
        val audioBtn = KeyButton(act, "", 14f)
        val audioStatus = label(act, "", 12f, C.SUB).apply { setPadding(act.dpi(10), 0, 0, 0) }
        fun refreshAudio() {
            audioBtn.text = if (Prefs.pcAudio) "🎧 スマホで聞く: ON" else "🎧 スマホで聞く: OFF"
            audioBtn.active = if (Prefs.pcAudio) 2 else 0
            audioStatus.text = if (Prefs.pcAudio) PcAudio.status else ""
        }
        audioBtn.onTap = {
            Prefs.pcAudio = !Prefs.pcAudio
            if (Prefs.pcAudio) PcAudio.start() else PcAudio.stop()
            refreshAudio()
        }
        audioRow.addView(audioBtn, LinearLayout.LayoutParams(-2, act.dpi(46)).apply { weight = 0f })
        audioBtn.setPadding(act.dpi(14), 0, act.dpi(14), 0)
        audioRow.addView(audioStatus, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(audioRow)
        @Suppress("UseSwitchCompatOrMaterialCode")
        root.addView(android.widget.Switch(act).apply {
            text = "スマホで聞いている間は、PCのスピーカーから音を出さない（オフ: 両方から鳴らす）"
            textSize = 13f
            setTextColor(C.INK)
            isChecked = Prefs.pcAudioMutePc
            setPadding(0, act.dpi(2), 0, act.dpi(6))
            setOnCheckedChangeListener { _, on ->
                Prefs.pcAudioMutePc = on
                // 聞いている途中なら、つなぎ直して反映する
                if (Prefs.pcAudio) {
                    PcAudio.stop()
                    PcAudio.start()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        PcAudio.onStatus = { refreshAudio() }
        refreshAudio()

        // 再生操作
        val media = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for ((lab, key) in listOf("⏮" to "MPREV", "⏯" to "MPLAY", "⏭" to "MNEXT")) {
            media.addView(KeyButton(act, lab, 18f).apply {
                onTap = { Link.send("KY $key 0") }
            }, LinearLayout.LayoutParams(0, act.dpi(48), 1f).apply {
                setMargins(act.dpi(3), act.dpi(4), act.dpi(3), act.dpi(4))
            })
        }
        root.addView(media)

        // 出力先
        root.addView(label(act, "出力先", 14f, C.ACCENT, true).apply {
            setPadding(0, act.dpi(14), 0, act.dpi(6))
        })
        devBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        devBox.addView(label(act, "読み込み中…", 13f, C.SUB))
        root.addView(devBox)
        errView = label(act, "", 12f, C.WARN).apply { setPadding(0, act.dpi(8), 0, 0) }
        root.addView(errView)

        root.addView(KeyButton(act, "閉じる", 15f).apply {
            onTap = { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-1, act.dpi(46)).apply { topMargin = act.dpi(14) })

        val scroll = ScrollView(act).apply { addView(root) }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(scroll)
        dialog.window?.let {
            it.setBackgroundDrawable(rounded(0, 0f))
            it.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            h.removeCallbacks(poll)
            Link.onVolumeInfo = null
            PcAudio.onStatus = null
        }
        Link.onVolumeInfo = { json -> apply(json) }
        dialog.show()
        h.post(poll)
    }

    private fun refreshMute() {
        muteBtn.text = if (muted) "🔇" else "🔊"
        muteBtn.active = if (muted) 2 else 0
    }

    private fun apply(json: String) {
        try {
            val o = JSONObject(json)
            if (!dragging) {
                seek.progress = o.optInt("vol", 0)
                volLabel.text = "${o.optInt("vol", 0)}"
            }
            muted = o.optBoolean("mute", false)
            refreshMute()
            errView.text = o.optString("error", "")

            val devs = o.optJSONArray("devices")
            val sig = devs?.toString() ?: ""
            if (sig == lastDevices) return
            lastDevices = sig
            devBox.removeAllViews()
            if (devs == null || devs.length() == 0) {
                devBox.addView(label(act, "出力先が見つかりません", 13f, C.SUB))
                return
            }
            for (i in 0 until devs.length()) {
                val d = devs.getJSONObject(i)
                val id = d.optString("id")
                val isDefault = d.optBoolean("default")
                devBox.addView(KeyButton(act, (if (isDefault) "● " else "") + d.optString("name", "?"), 14f).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(act.dpi(14), 0, act.dpi(14), 0)
                    isSingleLine = true
                    active = if (isDefault) 2 else 0
                    onTap = {
                        if (!isDefault) Link.send("VD ${Link.b64(id)}")
                    }
                }, LinearLayout.LayoutParams(-1, act.dpi(48)).apply { bottomMargin = act.dpi(6) })
            }
        } catch (_: Exception) {
        }
    }
}
