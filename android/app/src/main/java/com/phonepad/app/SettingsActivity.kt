package com.phonepad.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import kotlin.math.roundToInt

/** 設定画面 */
class SettingsActivity : Activity() {
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(12), dpi(20), dpi(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(box)
            setOnApplyWindowInsetsListener { v, ins ->
                val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(s.left, s.top, s.right, s.bottom)
                WindowInsets.CONSUMED
            }
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(KeyButton(this, "← 戻る", 14f).apply {
            setPadding(dpi(14), 0, dpi(14), 0)
            onTap = { finish() }
        }, LinearLayout.LayoutParams(-2, dpi(40)))
        head.addView(label(this, "　設定", 20f, C.INK, true))
        box.addView(head)

        section("マウス")
        slider("マウスの速さ", 0.3f, 4f, Prefs.mouseSpeed, 1) { Prefs.mouseSpeed = it }
        slider("マウスの加速（速く動かすほど遠くへ）", 0f, 1.5f, Prefs.mouseAccel, 1) { Prefs.mouseAccel = it }
        slider("スクロールの速さ", 0.3f, 3f, Prefs.scrollSpeed, 1) { Prefs.scrollSpeed = it }
        toggle("2本指スクロールの向きを逆にする", Prefs.invertTwoFinger) { Prefs.invertTwoFinger = it }
        slider("スティック操作でのマウスの速さ", 0.3f, 3f, Prefs.stickSpeed, 1) { Prefs.stickSpeed = it }
        toggle("マウスのスティックを触れた位置に出す（オフ: 真ん中に固定）", Prefs.mouseStickFloating) {
            Prefs.mouseStickFloating = it
        }

        section("キーボード")
        toggle("英数字専用キーボードにする（英字が確実に1文字ずつ送られます。日本語入力は使えません）", Prefs.latinOnly) {
            Prefs.latinOnly = it
        }
        toggle("PCの入力欄の内容を常に表示する（オフ: 文字を入力しているときだけ表示）", Prefs.fieldAlways) {
            Prefs.fieldAlways = it
        }
        slider("矢印キーなどを長押ししたときの速さ（回/秒）", 4f, 40f, Prefs.keyRepeatRate.toFloat(), 0) {
            Prefs.keyRepeatRate = it.roundToInt()
        }
        slider("長押ししてから連続入力が始まるまで（ミリ秒）", 150f, 800f, Prefs.keyRepeatDelay.toFloat(), 0) {
            Prefs.keyRepeatDelay = it.roundToInt()
        }

        section("コントローラー")
        slider("連打の速さ（回/秒）", 5f, 30f, Prefs.turboRate.toFloat(), 0) { Prefs.turboRate = it.roundToInt() }
        toggle("ABXYをSwitchの位置どおりに割り当てる（オフ: 文字どおり A→A）", Prefs.positionalAbxy) {
            Prefs.positionalAbxy = it
        }
        toggle("ゲームの振動をスマホで再現する", Prefs.rumble) { Prefs.rumble = it }
        toggle("スティックを触れた位置に出す（オフ: 決まった位置に固定）", Prefs.padStickFloating) {
            Prefs.padStickFloating = it
        }
        slider("スティックの遊び（中心付近で反応しない範囲・%）", 0f, 40f, Prefs.padDeadzone * 100f, 0) {
            Prefs.padDeadzone = it / 100f
        }
        slider("スティックの感度（大きいほど少し倒すだけで最大に）", 0.5f, 2f, Prefs.padSensitivity, 2) {
            Prefs.padSensitivity = it
        }
        slider("スティックの反応カーブ（1=そのまま、大きいほど小さく倒したとき細かく動く）", 0.5f, 2.5f, Prefs.padCurve, 2) {
            Prefs.padCurve = it
        }
        box.addView(label(this, "ボタンやスティックの位置・大きさは、コントローラー画面上部の ✎ から変えられます。", 12f, C.SUB).apply {
            setPadding(0, dpi(6), 0, 0)
        })
        box.addView(KeyButton(this, "コントローラーの配置を初期に戻す", 14f).apply {
            onTap = {
                Prefs.padLayout = ""
                android.widget.Toast.makeText(this@SettingsActivity, "初期配置に戻しました", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, dpi(46)).apply { topMargin = dpi(8) })

        section("PC画面の表示")
        slider("画質（0=低・速い / 1=標準 / 2=高）", 0f, 2f, Prefs.screenQuality.toFloat(), 0) {
            Prefs.screenQuality = it.roundToInt()
        }
        toggle("文字を入力するとき、最後にタップした場所を拡大する", Prefs.screenAutoZoom) { Prefs.screenAutoZoom = it }

        section("PC")
        val auto = toggle("PCの起動時に PhonePad を自動で起動する（PCとつながっているときに変更できます）", false) {
            Link.send("AS ${if (it) 1 else 0}")
        }
        auto.isEnabled = Link.state == Link.State.CONNECTED
        Link.onAutostart = { on ->
            autoUpdating = true
            auto.isChecked = on
            autoUpdating = false
        }
        Link.send("AQ")

        section("その他")
        toggle("ボタンを押したときに振動する", Prefs.vibrate) { Prefs.vibrate = it }

        box.addView(KeyButton(this, "接続先のPCを変更する", 15f).apply {
            onTap = {
                Link.disconnect()
                startActivity(
                    Intent(this@SettingsActivity, ConnectActivity::class.java)
                        .putExtra(ConnectActivity.EXTRA_MANUAL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
        }, LinearLayout.LayoutParams(-1, dpi(52)).apply { topMargin = dpi(28) })

        section("使い方")
        box.addView(KeyButton(this, "使い方を見る（配布サイトの説明書）", 15f).apply {
            onTap = {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.SITE + "guide.html")))
            }
        }, LinearLayout.LayoutParams(-1, dpi(48)))

        section("更新")
        box.addView(KeyButton(this, "新しいバージョンを確認", 15f).apply {
            onTap = { Updater.check(this@SettingsActivity, true) }
        }, LinearLayout.LayoutParams(-1, dpi(48)))
        box.addView(KeyButton(this, "配布サイト・変更内容を見る", 14f).apply {
            onTap = {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Updater.SITE)))
            }
        }, LinearLayout.LayoutParams(-1, dpi(46)).apply { topMargin = dpi(6) })
        if (Link.pcVersion.isNotEmpty()) {
            box.addView(label(this, "PCのソフト: v${Link.pcVersion}", 12f, C.SUB).apply { setPadding(0, dpi(8), 0, 0) })
        }

        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        box.addView(label(this, "PhonePad $ver", 12f, C.SUB).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(16), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        setContentView(scroll)
    }

    private fun section(title: String) {
        box.addView(label(this, title, 14f, C.ACCENT, true).apply {
            setPadding(0, dpi(24), 0, dpi(6))
        })
    }

    private fun slider(title: String, lo: Float, hi: Float, value: Float, decimals: Int, set: (Float) -> Unit) {
        val steps = 100
        val t = label(this, "", 14f, C.INK)
        fun fmt(v: Float) = if (decimals == 0) "${v.roundToInt()}" else "%.${decimals}f".format(v)
        t.text = "$title：${fmt(value)}"
        val sb = SeekBar(this).apply {
            this.max = steps
            progress = (((value - lo) / (hi - lo)) * steps).roundToInt().coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + (hi - lo) * p / steps
                    t.text = "$title：${fmt(v)}"
                    if (fromUser) set(v)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(t.apply { setPadding(0, dpi(10), 0, 0) })
        box.addView(sb, LinearLayout.LayoutParams(-1, dpi(40)))
    }

    private var autoUpdating = false

    override fun onDestroy() {
        super.onDestroy()
        Link.onAutostart = null
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(title: String, value: Boolean, set: (Boolean) -> Unit): Switch {
        val sw = Switch(this).apply {
            text = title
            textSize = 14f
            setTextColor(C.INK)
            isChecked = value
            setPadding(0, dpi(10), 0, dpi(10))
            setOnCheckedChangeListener { _, b -> if (!autoUpdating) set(b) }
        }
        box.addView(sw, LinearLayout.LayoutParams(-1, -2))
        return sw
    }
}
