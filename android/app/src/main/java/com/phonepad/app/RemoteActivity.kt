package com.phonepad.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** キーボード + マウス(トラックパッド/スティック) 画面 */
class RemoteActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var field: KeyInputView
    private lateinit var sender: ImeSender
    private lateinit var pcField: PcFieldView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var resumed = false

    /** PCで入力中の欄の内容を定期的に取りに行く */
    private val fieldPoll = object : Runnable {
        override fun run() {
            if (resumed && fieldShown() && Link.state == Link.State.CONNECTED) Link.send("TQ")
            handler.postDelayed(this, 700)
        }
    }
    private val quickQuery = Runnable { if (Link.state == Link.State.CONNECTED) Link.send("TQ") }
    private val echo = StringBuilder()
    private lateinit var mods: Modifiers
    private lateinit var imeSpacer: View
    private lateinit var padFrame: FrameLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var fnPanel: GridLayout
    private lateinit var modeBtn: KeyButton
    private val modButtons = HashMap<Int, KeyButton>()
    private var fnBtn: KeyButton? = null
    private val middle = MiddleState()
    private var imeVisible = false

    private val linkListener: (Link.State) -> Unit = { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mods = Modifiers { refreshMods() }
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        Link.addListener(linkListener)
        field.applyMode(Prefs.latinOnly)
        if (Link.state == Link.State.DISCONNECTED) {
            Prefs.target?.let { Link.connect(it, null, persistent = true) }
        }
    }

    override fun onStop() {
        super.onStop()
        Link.removeListener(linkListener)
    }

    private fun updateStatus() {
        when (Link.state) {
            Link.State.CONNECTED -> {
                statusView.text = "● ${Link.pcName}"
                statusView.setTextColor(C.OK)
            }

            Link.State.CONNECTING -> {
                statusView.text = "PCを探しています…"
                statusView.setTextColor(C.SUB)
            }

            Link.State.DISCONNECTED -> {
                statusView.text = "未接続（タップ）"
                statusView.setTextColor(C.WARN)
            }
        }
    }

    // ---------------------------------------------------------------- 画面構成

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            val sys = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            setImeVisible(ins.isVisible(WindowInsets.Type.ime()))
            WindowInsets.CONSUMED
        }

        // ---- 上部バー ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(6), dpi(8), dpi(2))
        }
        top.addView(KeyButton(this, "🎮", 17f).apply {
            onTap = { openController() }
        }, LinearLayout.LayoutParams(dpi(48), dpi(40)))
        statusView = label(this, "", 13f, C.SUB, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                when (Link.state) {
                    Link.State.DISCONNECTED -> openConnect()
                    Link.State.CONNECTED -> StatusPanel(this@RemoteActivity).show()
                    // 探している途中: 接続画面へ (PCの電源を入れるボタンもある)
                    Link.State.CONNECTING -> {
                        startActivity(Intent(this@RemoteActivity, ConnectActivity::class.java))
                        finish()
                    }
                }
            }
        }
        top.addView(statusView, LinearLayout.LayoutParams(0, dpi(40), 1f))
        top.addView(KeyButton(this, "★", 17f).apply {
            onTap = {
                hideIme()
                ShortcutPanel(this@RemoteActivity).show()
            }
        }, LinearLayout.LayoutParams(dpi(44), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "🔊", 16f).apply {
            onTap = { VolumePanel(this@RemoteActivity).show() }
        }, LinearLayout.LayoutParams(dpi(44), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "🖥", 16f).apply {
            onTap = {
                hideIme()
                startActivity(Intent(this@RemoteActivity, ScreenActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dpi(44), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "⚙", 18f).apply {
            onTap = { startActivity(Intent(this@RemoteActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dpi(44), dpi(40)))
        root.addView(top)

        // キーボード表示中に、入力欄と特殊キーをキーボードのすぐ上へ寄せるための余白
        imeSpacer = View(this).apply { visibility = View.GONE }
        root.addView(imeSpacer, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(buildKeyboardCard(), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dpi(8), dpi(6), dpi(8), dpi(6))
        })

        // ---- マウス ----
        padFrame = FrameLayout(this)
        root.addView(padFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dpi(8), 0, dpi(8), dpi(6))
        })
        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, dpi(76)).apply {
            setMargins(dpi(8), 0, dpi(8), dpi(8))
        })
        buildPad()

        setContentView(root)
        updateFieldVisibility()
        updateStatus()
    }

    private fun buildKeyboardCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, dp(16f))
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        }

        // PCで入力中の欄 (読み取れないときは送った文字)
        pcField = PcFieldView(this)
        card.addView(pcField, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6) })

        // 入力欄
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        field = KeyInputView(this).apply {
            hint = "ここをタップして入力（すぐPCへ送られます）"
            setHintTextColor(C.SUB)
            setTextColor(C.INK)
            textSize = 16f
            background = rounded(C.BG, dp(10f), C.LINE, dpi(1))
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            applyMode(Prefs.latinOnly)
        }
        sender = ImeSender(mods) { onEcho(it) }
        field.sender = sender
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(KeyButton(this, "×", 18f).apply {
            onTap = { field.clearLocal() }
        }, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { leftMargin = dpi(6) })
        card.addView(row)

        // 特殊キー 1段目
        val r1 = keyRow()
        r1.addView(specialKey("Esc") { sendKey("ESC") })
        r1.addView(specialKey("Tab") { sendKey("TAB") })
        r1.addView(modKey("Ctrl", Modifiers.CTRL))
        r1.addView(modKey("Shift", Modifiers.SHIFT))
        r1.addView(modKey("Alt", Modifiers.ALT))
        r1.addView(modKey("Win", Modifiers.WIN))
        val fn = specialKey("Fn") { toggleFn() }
        fnBtn = fn
        r1.addView(fn)
        card.addView(r1)

        // 特殊キー 2段目
        val r2 = keyRow()
        r2.addView(specialKey("←", repeat = true) { sendKey("LEFT") })
        r2.addView(specialKey("↑", repeat = true) { sendKey("UP") })
        r2.addView(specialKey("↓", repeat = true) { sendKey("DOWN") })
        r2.addView(specialKey("→", repeat = true) { sendKey("RIGHT") })
        r2.addView(specialKey("⌫", repeat = true) { sender.backspace(1) })
        r2.addView(specialKey("Del", repeat = true) { sendKey("DEL") })
        r2.addView(specialKey("⏎") { sender.enter() })
        card.addView(r2)

        // Fn パネル
        fnPanel = GridLayout(this).apply {
            columnCount = 4
            visibility = View.GONE
            setPadding(0, dpi(6), 0, 0)
        }
        val fnKeys = (1..12).map { "F$it" to "F$it" } + listOf(
            "Home" to "HOME", "End" to "END", "PgUp" to "PGUP", "PgDn" to "PGDN",
            "Ins" to "INS", "PrtSc" to "PRTSC", "半/全" to "KANJI", "Winのみ" to "WIN",
        )
        for ((lab, key) in fnKeys) {
            val b = KeyButton(this, lab, 14f).apply {
                onTap = {
                    sendKey(key)
                    toggleFn(false)
                }
            }
            fnPanel.addView(b, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0; height = dpi(44)
                setMargins(dpi(3), dpi(3), dpi(3), dpi(3))
            })
        }
        card.addView(fnPanel)
        return card
    }

    /** 送った文字の表示を更新 (最後の60文字だけ残す) */
    private fun onEcho(t: String?) {
        when (t) {
            null -> if (echo.isNotEmpty()) {
                val cp = echo.codePointBefore(echo.length)
                echo.setLength(echo.length - Character.charCount(cp))
            }
            "\n" -> echo.setLength(0)
            else -> echo.append(t)
        }
        if (echo.length > 60) echo.delete(0, echo.length - 60)
        updateEcho()
    }

    private fun updateEcho() {
        pcField.setEcho(echo.toString())
        // 送った直後は早めにPCの入力欄を読み直す
        handler.removeCallbacks(quickQuery)
        handler.postDelayed(quickQuery, 150)
    }

    private fun keyRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dpi(6), 0, 0)
    }

    private fun specialKey(lab: String, repeat: Boolean = false, action: () -> Unit) =
        KeyButton(this, lab, 13f).apply {
            this.repeat = repeat
            onTap = action
            layoutParams = LinearLayout.LayoutParams(0, dpi(42), 1f).apply {
                setMargins(dpi(2), 0, dpi(2), 0)
            }
        }

    private fun modKey(lab: String, bit: Int) = KeyButton(this, lab, 13f).apply {
        onTap = { mods.tap(bit) }
        onLongPress = { mods.lock(bit) }
        layoutParams = LinearLayout.LayoutParams(0, dpi(42), 1f).apply {
            setMargins(dpi(2), 0, dpi(2), 0)
        }
        modButtons[bit] = this
    }

    private fun refreshMods() {
        for ((bit, b) in modButtons) b.active = mods.state(bit)
    }

    private fun sendKey(name: String) {
        Link.send("KY $name ${mods.consume()}")
        handler.removeCallbacks(quickQuery)
        handler.postDelayed(quickQuery, 150)
    }

    private fun toggleFn(show: Boolean = fnPanel.visibility != View.VISIBLE) {
        fnPanel.visibility = if (show) View.VISIBLE else View.GONE
        fnBtn?.active = if (show) 1 else 0
    }

    // ---- マウス部分 (トラックパッド / スティック) ----

    private fun buildPad() {
        padFrame.removeAllViews()
        bottomBar.removeAllViews()
        val stick = Prefs.padMode == "stick"
        val pad: View = if (stick) StickPadView(this, middle) else TrackpadView(this, middle)
        middle.onChange = { pad.invalidate() }
        padFrame.addView(pad, FrameLayout.LayoutParams(-1, -1))

        modeBtn = KeyButton(this, if (stick) "マウス操作" else "スティック操作", 12f).apply {
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = {
                Prefs.padMode = if (stick) "trackpad" else "stick"
                buildPad()
            }
        }
        padFrame.addView(modeBtn, FrameLayout.LayoutParams(-2, dpi(36), Gravity.TOP or Gravity.START).apply {
            setMargins(dpi(8), dpi(8), 0, 0)
        })

        val mid = KeyButton(this, "長押し", 15f).apply {
            onDown = { middle.press() }
            onUp = { middle.release() }
        }
        if (stick) {
            // 左右ボタンは押している間マウスボタンを押しっぱなし → スティックでドラッグできる
            bottomBar.addView(KeyButton(this, "左", 18f).apply {
                onDown = { Link.send("MB L 1") }
                onUp = { Link.send("MB L 0") }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dpi(6) })
            bottomBar.addView(mid, LinearLayout.LayoutParams(0, -1, 1.2f))
            bottomBar.addView(KeyButton(this, "右", 18f).apply {
                onDown = { Link.send("MB R 1") }
                onUp = { Link.send("MB R 0") }
            }, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dpi(6) })
        } else {
            bottomBar.addView(mid, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    private fun setImeVisible(v: Boolean) {
        if (v == imeVisible) return
        imeVisible = v
        // 文字入力中はマウス部分を隠す
        val padVis = if (v) View.GONE else View.VISIBLE
        padFrame.visibility = padVis
        bottomBar.visibility = padVis
        imeSpacer.visibility = if (v) View.VISIBLE else View.GONE
        pcField.compact = v
        updateFieldVisibility()
        if (!v) field.clearFocus()
    }

    private fun fieldShown() = ::pcField.isInitialized && pcField.visibility == View.VISIBLE

    /** PCの入力欄の表示は、設定により常に、または文字入力中だけ */
    private fun updateFieldVisibility() {
        val show = Prefs.fieldAlways || imeVisible
        val was = fieldShown()
        pcField.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !was) {
            handler.removeCallbacks(quickQuery)
            handler.post(quickQuery)
        }
    }

    private fun openController() {
        hideIme()
        startActivity(Intent(this, ControllerActivity::class.java))
    }

    private fun openConnect() {
        Link.disconnect()
        startActivity(Intent(this, ConnectActivity::class.java).putExtra(ConnectActivity.EXTRA_MANUAL, true))
        finish()
    }

    private fun hideIme() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Link.onGamepadError = { msg ->
            Toast.makeText(this, "コントローラーを使えません: $msg", Toast.LENGTH_LONG).show()
        }
        Link.onField = { pcField.update(it) }
        updateFieldVisibility()
        Link.onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        handler.removeCallbacks(fieldPoll)
        handler.post(fieldPoll)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(fieldPoll)
        handler.removeCallbacks(quickQuery)
        if (Link.onField != null) Link.onField = null
    }
}
