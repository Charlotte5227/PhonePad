package com.phonepad.app

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/** PCの状態 (CPU・メモリ・電池・ディスク・起動時間) */
class StatusPanel(private val act: Activity) {
    private val dialog = Dialog(act)
    private val h = Handler(Looper.getMainLooper())
    private val rows = HashMap<String, Pair<TextView, ProgressBar?>>()
    private lateinit var cpuName: TextView

    private val poll = object : Runnable {
        override fun run() {
            Link.send("SQ")
            h.postDelayed(this, 1500)
        }
    }

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, act.dp(18f))
            setPadding(act.dpi(18), act.dpi(16), act.dpi(18), act.dpi(16))
        }
        root.addView(label(act, "📊 ${Link.pcName} の状態", 18f, C.INK, true))
        cpuName = label(act, "", 12f, C.SUB).apply { setPadding(0, act.dpi(4), 0, act.dpi(6)) }
        root.addView(cpuName)
        row(root, "cpu", "CPU", true)
        row(root, "mem", "メモリ", true)
        row(root, "disk", "ディスク（C:）", true)
        row(root, "batt", "バッテリー", true)
        row(root, "up", "起動してから", false)
        root.addView(KeyButton(act, "閉じる", 15f).apply { onTap = { dialog.dismiss() } },
            LinearLayout.LayoutParams(-1, act.dpi(46)).apply { topMargin = act.dpi(14) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(act).apply { addView(root) })
        dialog.window?.let {
            it.setBackgroundDrawable(rounded(0, 0f))
            it.setLayout((act.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        Link.onSysInfo = { apply(it) }
        dialog.setOnDismissListener {
            h.removeCallbacks(poll)
            Link.onSysInfo = null
        }
        dialog.show()
        h.post(poll)
    }

    private fun row(root: LinearLayout, key: String, title: String, bar: Boolean) {
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, act.dpi(10), 0, act.dpi(2))
        }
        head.addView(label(act, title, 14f, C.ACCENT, true), LinearLayout.LayoutParams(0, -2, 1f))
        val value = label(act, "…", 14f, C.INK)
        head.addView(value)
        root.addView(head)
        var pb: ProgressBar? = null
        if (bar) {
            pb = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            }
            root.addView(pb, LinearLayout.LayoutParams(-1, act.dpi(10)))
        }
        rows[key] = value to pb
    }

    private fun set(key: String, text: String, percent: Int?) {
        val (t, pb) = rows[key] ?: return
        t.text = text
        if (pb != null) {
            pb.visibility = if (percent == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
            pb.progress = percent ?: 0
        }
    }

    private fun gb(b: Long) = String.format(Locale.JAPAN, "%.1f GB", b / 1024.0 / 1024.0 / 1024.0)

    private fun apply(json: String) {
        try {
            val o = JSONObject(json)
            cpuName.text = o.optString("cpuName")
            val cpu = o.optDouble("cpu", -1.0)
            set("cpu", if (cpu < 0) "–" else "${cpu.toInt()}%", if (cpu < 0) null else cpu.toInt())
            val mu = o.optLong("memUsed")
            val mt = o.optLong("memTotal")
            if (mt > 0) set("mem", "${gb(mu)} / ${gb(mt)}", (mu * 100 / mt).toInt())
            val df = o.optLong("diskFree")
            val dt = o.optLong("diskTotal")
            if (dt > 0) set("disk", "空き ${gb(df)} / ${gb(dt)}", ((dt - df) * 100 / dt).toInt())
            val bt = o.optInt("battery", -1)
            val ch = o.optBoolean("charging")
            if (bt < 0) {
                set("batt", "なし（電源につないで使うPC）", null)
            } else {
                set("batt", "$bt%" + if (ch) "（充電中）" else "", bt)
            }
            val up = o.optLong("uptime")
            val d = up / 86400
            val hh = (up % 86400) / 3600
            val mm = (up % 3600) / 60
            set("up", (if (d > 0) "${d}日 " else "") + "${hh}時間${mm}分", null)
        } catch (_: Exception) {
        }
    }
}
