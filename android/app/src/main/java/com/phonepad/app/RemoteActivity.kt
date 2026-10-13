package com.phonepad.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** キーボードに自分で追加したキー (rect は最初に置く位置) */
class CustomKey(val id: String, val sc: Shortcut, val rect: FloatArray)

/**
 * キーボード + マウス(トラックパッド/スティック/片手) 画面。
 * 部品は FreeStage の上に自由に並べられる (✎ で変更)。文字を打っている間は、決まった並び (kbCard) に切り替える。
 */
class RemoteActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var field: KeyInputView
    private lateinit var sender: ImeSender
    private lateinit var pcField: PcFieldView
    private lateinit var pcField2: PcFieldView
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
    private lateinit var kbCard: View
    private lateinit var stage: FreeStage
    private lateinit var top: LinearLayout
    private lateinit var editBar: LinearLayout
    private lateinit var selLabel: TextView
    private lateinit var hideBtn: KeyButton
    private lateinit var fnPanel: GridLayout
    private val modButtons = HashMap<Int, MutableList<KeyButton>>()
    private val stageMods = ArrayList<Pair<Int, KeyButton>>()
    private var fnBtn: KeyButton? = null
    private val middle = MiddleState()
    private var ohPad: OneHandPadView? = null
    private var imeVisible = false
    private var typingRequested = false
    private var curMode = ""
    private var customs = ArrayList<CustomKey>()

    /** 入力欄をタップしてもキーボードが出なかったときは、元の配置に戻す */
    private val typingTimeout = Runnable {
        if (!imeVisible && typingRequested) {
            typingRequested = false
            field.clearFocus()
            applyTyping()
        }
    }

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
        top = LinearLayout(this).apply {
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
        }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "🔊", 16f).apply {
            onTap = { VolumePanel(this@RemoteActivity).show() }
        }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "🖥", 16f).apply {
            onTap = {
                hideIme()
                startActivity(Intent(this@RemoteActivity, ScreenActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { rightMargin = dpi(4) })
        // ボタンの配置を変える
        top.addView(KeyButton(this, "✎", 17f).apply {
            onTap = { setEditing(true) }
        }, LinearLayout.LayoutParams(dpi(40), dpi(40)).apply { rightMargin = dpi(4) })
        top.addView(KeyButton(this, "⚙", 18f).apply {
            onTap = { startActivity(Intent(this@RemoteActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        root.addView(top)
        root.addView(buildEditBar())

        // キーボード表示中に、入力欄と特殊キーをキーボードのすぐ上へ寄せるための余白
        imeSpacer = View(this).apply { visibility = View.GONE }
        root.addView(imeSpacer, LinearLayout.LayoutParams(-1, 0, 1f))

        kbCard = buildKeyboardCard()
        root.addView(kbCard, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dpi(8), dpi(6), dpi(8), dpi(6))
        })

        // ---- 自由に並べられる部品 (文字を打っていないとき) ----
        pcField2 = PcFieldView(this).apply {
            fillParent()
            background = rounded(C.SURFACE, dp(14f))
            setPadding(dpi(6), dpi(6), dpi(6), dpi(6))
        }
        stage = FreeStage(this)
        stage.onSelect = { p -> paintSel(p) }
        stage.onChanged = {
            Prefs.setKbLayout(curMode, stage.toJson())
            updateFieldVisibility()
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dpi(8), dpi(4), dpi(8), dpi(8))
        })
        customs = loadCustoms()
        buildStage()

        setContentView(root)
        applyTyping()
        updateStatus()
    }

    // ---------------------------------------------------------------- 配置を変える

    private fun buildEditBar(): View {
        editBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(6), dpi(8), dpi(2))
            visibility = View.GONE
        }
        selLabel = label(this, "", 13f, C.ACCENT, true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpi(4), 0, dpi(4), 0)
        }
        editBar.addView(selLabel, LinearLayout.LayoutParams(0, -2, 1f))
        fun btn(t: String, f: () -> Unit) = KeyButton(this, t, 13f).apply {
            setPadding(dpi(10), 0, dpi(10), 0)
            onTap = f
        }
        hideBtn = btn("隠す") { hideSelected() }
        editBar.addView(hideBtn, LinearLayout.LayoutParams(-2, dpi(40)).apply { leftMargin = dpi(4) })
        editBar.addView(btn("＋キー") { addCustomKey() }, LinearLayout.LayoutParams(-2, dpi(40)).apply { leftMargin = dpi(4) })
        editBar.addView(btn("初期配置") { resetLayout() }, LinearLayout.LayoutParams(-2, dpi(40)).apply { leftMargin = dpi(4) })
        editBar.addView(btn("完了") { setEditing(false) }.apply { active = 2 },
            LinearLayout.LayoutParams(-2, dpi(40)).apply { leftMargin = dpi(4) })
        paintSel(null)
        return editBar
    }

    private fun setEditing(on: Boolean) {
        if (on) {
            hideIme()
            typingRequested = false
            applyTyping()
        }
        stage.editing = on
        top.visibility = if (on) View.GONE else View.VISIBLE
        editBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) toast("部品をタップして選び、なぞって動かします。右下の丸で大きさを変えられます")
        updateFieldVisibility()
    }

    private fun paintSel(p: FreeStage.Part?) {
        selLabel.text = p?.name ?: "部品をタップして選ぶ"
        hideBtn.text = when {
            p == null -> "隠す"
            p.customId != null -> "削除"
            (stage.rects[p.id]?.get(4) ?: 0f) != 0f -> "表示"
            else -> "隠す"
        }
    }

    private fun hideSelected() {
        val id = stage.sel
        val p = id?.let { stage.parts[it] }
        if (id == null || p == null) {
            toast("変えたい部品をタップして選んでください")
            return
        }
        if (p.fixed) {
            toast("入力欄は隠せません（動かす・大きさを変えることはできます）")
            return
        }
        if (p.customId != null) {
            val c = customs.firstOrNull { it.id == id } ?: return
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("「${c.sc.label}」を削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    customs.remove(c)
                    saveCustoms()
                    stage.remove(id)
                    Prefs.setKbLayout(curMode, stage.toJson())
                }
                .setNegativeButton("キャンセル", null).show()
            return
        }
        stage.toggleHidden(id)
    }

    private fun addCustomKey() {
        ShortcutPanel(this).editShortcut(null, "キーを追加") { sc ->
            val c = CustomKey("c" + System.currentTimeMillis().toString(36), sc, floatArrayOf(.35f, .45f, .3f, .07f, 0f))
            customs.add(c)
            saveCustoms()
            buildStage()
            Prefs.setKbLayout(curMode, stage.toJson())
            stage.select(c.id)
            toast("「${sc.label}」を追加しました。動かして好きな場所に置いてください")
        }
    }

    private fun resetLayout() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("この画面の配置を初期に戻しますか？（追加したキーも消えます）")
            .setPositiveButton("戻す") { _, _ ->
                Prefs.setKbLayout(curMode, null)
                customs.clear()
                saveCustoms()
                buildStage()
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun loadCustoms(): ArrayList<CustomKey> {
        val out = ArrayList<CustomKey>()
        try {
            val a = JSONArray(Prefs.kbCustom)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val r = o.optJSONArray("rect")
                val rect = FloatArray(5) { j -> if (r != null && j < r.length()) r.optDouble(j, 0.0).toFloat() else floatArrayOf(.35f, .45f, .3f, .07f, 0f)[j] }
                out.add(CustomKey(o.getString("id"), Shortcut(o.optString("l"), o.optString("k"), o.optInt("m"), o.optString("r")), rect))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveCustoms() {
        val a = JSONArray()
        for (c in customs) {
            val r = JSONArray()
            c.rect.forEach { r.put(it.toDouble()) }
            a.put(JSONObject().put("id", c.id).put("l", c.sc.label).put("k", c.sc.key).put("m", c.sc.mods).put("r", c.sc.run).put("rect", r))
        }
        Prefs.kbCustom = a.toString()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::stage.isInitialized && stage.editing) {
            setEditing(false)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
        for ((lab, key) in FN_KEYS) {
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
        pcField2.setEcho(echo.toString())
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

    private fun modKey(lab: String, bit: Int, onStage: Boolean = false) = KeyButton(this, lab, 13f).apply {
        onTap = { mods.tap(bit) }
        onLongPress = { mods.lock(bit) }
        if (!onStage) {
            layoutParams = LinearLayout.LayoutParams(0, dpi(42), 1f).apply {
                setMargins(dpi(2), 0, dpi(2), 0)
            }
        } else {
            stageMods.add(bit to this)
        }
        modButtons.getOrPut(bit) { ArrayList() }.add(this)
    }

    private fun refreshMods() {
        for ((bit, list) in modButtons) for (b in list) b.active = mods.state(bit)
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

    // ---------------------------------------------------------------- 自由に並べる部品

    /** tp=マウス(トラックパッド) / stick=スティック / oneR・oneL=片手操作 (配置はそれぞれ別に保存) */
    private fun kbMode() = when {
        Prefs.oneHand -> if (Prefs.oneHandLeft) "oneL" else "oneR"
        Prefs.padMode == "stick" -> "stick"
        else -> "tp"
    }

    private fun defaults(m: String): HashMap<String, FloatArray> {
        val l = HashMap<String, FloatArray>()
        fun r(x: Float, y: Float, w: Float, h: Float, off: Boolean = false) = floatArrayOf(x, y, w, h, if (off) 1f else 0f)
        l["input"] = r(.02f, .01f, .96f, .075f)
        l["star"] = r(.84f, .01f, .14f, .075f, true)
        KEYS1.forEachIndexed { i, (id, _) -> l[id] = r(.02f + i * .137f, .10f, .127f, .065f) }
        KEYS2.forEachIndexed { i, (id, _) -> l[id] = r(.02f + i * .137f, .175f, .127f, .065f) }
        val mouse = HashMap<String, FloatArray>()
        if (m == "tp" || m == "stick") {
            val st = m == "stick"
            mouse["pad"] = r(.02f, .255f, .96f, .62f)
            mouse["stickMode"] = r(.04f, .27f, .30f, .05f)
            mouse["oneMode"] = r(.66f, .27f, .30f, .05f)
            mouse["mbL"] = r(.02f, .885f, .30f, .105f, !st)
            mouse["mbM"] = if (st) r(.335f, .885f, .33f, .105f) else r(.02f, .885f, .96f, .105f)
            mouse["mbR"] = r(.68f, .885f, .30f, .105f, !st)
        } else {
            // 右手用: ボタンの列を右 (親指の側) に。左手用は左右を入れ替える
            mouse["ohPad"] = r(.02f, .255f, .66f, .735f)
            mouse["hand"] = r(.04f, .27f, .26f, .055f)
            mouse["ohExit"] = r(.70f, .255f, .28f, .06f)
            mouse["ohUp"] = r(.70f, .325f, .28f, .12f)
            mouse["ohDown"] = r(.70f, .455f, .28f, .12f)
            mouse["ohRight"] = r(.70f, .585f, .28f, .11f)
            mouse["ohGrab"] = r(.70f, .705f, .28f, .11f)
            mouse["ohLeft"] = r(.70f, .825f, .28f, .165f)
            if (m == "oneL") for (v in mouse.values) v[0] = 1f - v[0] - v[2]
        }
        if (Prefs.fieldAlways) {
            // 以前「PCの入力欄を常に表示」にしていた人は、入力欄を出してマウスを下に詰める
            for (v in mouse.values) {
                v[1] = .385f + (v[1] - .255f) * (.615f / .745f)
                v[3] = v[3] * (.615f / .745f)
            }
            l["mirror"] = r(.02f, .255f, .96f, .12f)
        } else {
            l["mirror"] = r(.02f, .255f, .96f, .13f, true)
        }
        l.putAll(mouse)
        for (c in customs) l[c.id] = c.rect.copyOf()
        return l
    }

    private fun buildStage() {
        for ((bit, b) in stageMods) modButtons[bit]?.remove(b)
        stageMods.clear()
        if (ohPad?.grabbing == true) Link.send("MB L 0")
        ohPad = null
        stage.clear()
        val m = kbMode()
        curMode = m
        fun add(id: String, v: View, name: String) = stage.add(FreeStage.Part(id, v, name))
        fun key(lab: String, sp: Float = 13f, repeat: Boolean = false, f: () -> Unit) =
            KeyButton(this, lab, sp).apply { this.repeat = repeat; onTap = f }

        // マウスの面 (いちばん下に重ねる)
        if (m == "tp" || m == "stick") {
            val pad: View = if (m == "stick") StickPadView(this, middle) else TrackpadView(this, middle)
            middle.onChange = { pad.invalidate() }
            add("pad", pad, if (m == "stick") "マウス（スティック）" else "マウス（トラックパッド）")
        } else {
            val p = OneHandPadView(this)
            ohPad = p
            add("ohPad", p, "マウス（片手）")
        }
        stage.add(FreeStage.Part("input", fakeInput(), "入力欄", fixed = true))
        add("mirror", pcField2, "PCの入力欄（PCで入力中の文字）")
        add("star", key("★", 17f) { hideIme(); ShortcutPanel(this).show() }, "★ ショートカット")

        // キー
        for ((id, lab) in KEYS1 + KEYS2) {
            val v: View = when (id) {
                "ctrl" -> modKey(lab, Modifiers.CTRL, true)
                "shift" -> modKey(lab, Modifiers.SHIFT, true)
                "alt" -> modKey(lab, Modifiers.ALT, true)
                "win" -> modKey(lab, Modifiers.WIN, true)
                "fn" -> key(lab) { showFnDialog() }
                "bksp" -> key(lab, repeat = true) { sender.backspace(1) }
                "enter" -> key(lab) { sender.enter() }
                else -> key(lab, repeat = id in REPEAT_KEYS) { sendKey(KEY_NAMES.getValue(id)) }
            }
            add(id, v, PART_NAMES[id] ?: lab)
        }

        if (m == "tp" || m == "stick") {
            val st = m == "stick"
            add("stickMode", key(if (st) "マウス操作" else "スティック操作", 12f) {
                Prefs.padMode = if (st) "trackpad" else "stick"
                buildStage()
            }, "「スティック操作」の切り替え")
            add("oneMode", key("片手操作", 12f) {
                Prefs.oneHand = true
                buildStage()
            }, "「片手操作」ボタン")
            add("mbL", KeyButton(this, "左", 18f).apply {
                onDown = { Link.send("MB L 1") }
                onUp = { Link.send("MB L 0") }
            }, "左ボタン（押している間押しっぱなし）")
            add("mbM", KeyButton(this, "長押し", 15f).apply {
                onDown = { middle.press() }
                onUp = { middle.release() }
            }, "長押しボタン（スクロール・中クリック）")
            add("mbR", KeyButton(this, "右", 18f).apply {
                onDown = { Link.send("MB R 1") }
                onUp = { Link.send("MB R 0") }
            }, "右ボタン（押している間押しっぱなし）")
        } else {
            val left = m == "oneL"
            val pad = ohPad!!
            add("hand", key(if (left) "＞ 右手用" else "＜ 左手用") {
                Prefs.oneHandLeft = !left
                buildStage()
            }, "左手用・右手用の切り替え")
            add("ohExit", key("片手をやめる", 12f) {
                Prefs.oneHand = false
                buildStage()
            }, "「片手をやめる」ボタン")
            add("ohUp", key("▲\nスクロール", 13f, true) { Link.send("MW 120 0") }, "▲ スクロール")
            add("ohDown", key("▼\nスクロール", 13f, true) { Link.send("MW -120 0") }, "▼ スクロール")
            add("ohRight", key("右クリック", 14f) { Link.send("MC R") }, "右クリック")
            val grab = KeyButton(this, "つかむ", 14f)
            grab.onTap = {
                pad.grabbing = !pad.grabbing
                Link.send("MB L ${if (pad.grabbing) 1 else 0}")
                grab.text = if (pad.grabbing) "はなす" else "つかむ"
                grab.active = if (pad.grabbing) 2 else 0
            }
            add("ohGrab", grab, "つかむ（ドラッグ）")
            add("ohLeft", key("左クリック", 16f) { Link.send("MC L") }, "左クリック")
        }

        // 自分で追加したキー
        for (c in customs) {
            stage.add(FreeStage.Part(c.id, key(c.sc.label, 13f) { sendCustom(c.sc) }.apply { maxLines = 2 },
                "追加したキー「${c.sc.label}」", customId = c.id))
        }

        stage.rects = FreeStage.merge(defaults(m), Prefs.kbLayout(m))
        stage.applyVisibility()
        paintSel(null)
        refreshMods()
        updateFieldVisibility()
    }

    private fun sendCustom(sc: Shortcut) {
        if (sc.run.isNotEmpty()) Link.send("RUN " + Link.b64(sc.run))
        else Link.send("KY ${sc.key} ${sc.mods or mods.consume()}")
    }

    /** 入力欄の見た目の部品。タップすると決まった並びに切り替えて、本物の入力欄に文字を打てるようにする */
    private fun fakeInput() = TextView(this).apply {
        text = "ここをタップして入力（すぐPCへ送られます）"
        setTextColor(C.SUB)
        textSize = 15f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(C.BG, dp(10f), C.LINE, dpi(1))
        setPadding(dpi(12), 0, dpi(12), 0)
        setOnClickListener { startTyping() }
    }

    private fun startTyping() {
        if (stage.editing) return
        typingRequested = true
        applyTyping()
        field.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
        handler.removeCallbacks(typingTimeout)
        handler.postDelayed(typingTimeout, 1500)
    }

    private fun showFnDialog() {
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(dpi(14), dpi(8), dpi(14), dpi(4))
        }
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ファンクションキーなど")
            .setView(grid)
            .setNegativeButton("閉じる", null)
            .create()
        for ((lab, key) in FN_KEYS) {
            grid.addView(KeyButton(this, lab, 14f).apply {
                onTap = {
                    sendKey(key)
                    d.dismiss()
                }
            }, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0; height = dpi(46)
                setMargins(dpi(3), dpi(3), dpi(3), dpi(3))
            })
        }
        d.show()
    }

    // ---------------------------------------------------------------- 文字入力中の切り替え

    private fun typingNow() = imeVisible || typingRequested

    private fun applyTyping() {
        val t = typingNow()
        kbCard.visibility = if (t) View.VISIBLE else View.GONE
        imeSpacer.visibility = if (t) View.VISIBLE else View.GONE
        stage.visibility = if (t) View.GONE else View.VISIBLE
        pcField.compact = imeVisible
        updateFieldVisibility()
    }

    private fun setImeVisible(v: Boolean) {
        if (v == imeVisible) return
        imeVisible = v
        typingRequested = false
        handler.removeCallbacks(typingTimeout)
        if (!v) field.clearFocus()
        if (::stage.isInitialized) applyTyping()
    }

    private var fieldWasShown = false
    private fun fieldShown() = ::stage.isInitialized && (typingNow() || (!stage.editing && stage.isShown("mirror")))

    /** PCの入力欄は、文字を打っている間と、配置で「PCの入力欄」を表示にしているときに読む */
    private fun updateFieldVisibility() {
        val show = fieldShown()
        if (show && !fieldWasShown) {
            handler.removeCallbacks(quickQuery)
            handler.post(quickQuery)
        }
        fieldWasShown = show
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
        Link.onField = {
            pcField.update(it)
            pcField2.update(it)
        }
        // 設定画面などで配置や操作の種類が変わっていることがあるので、作り直す
        if (!stage.editing && !typingNow()) {
            customs = loadCustoms()
            buildStage()
        }
        updateFieldVisibility()
        Link.onError = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        handler.removeCallbacks(fieldPoll)
        handler.post(fieldPoll)
    }

    companion object {
        val KEYS1 = listOf("esc" to "Esc", "tab" to "Tab", "ctrl" to "Ctrl", "shift" to "Shift", "alt" to "Alt", "win" to "Win", "fn" to "Fn")
        val KEYS2 = listOf("left" to "←", "up" to "↑", "down" to "↓", "right" to "→", "bksp" to "⌫", "del" to "Del", "enter" to "⏎")
        val KEY_NAMES = mapOf("esc" to "ESC", "tab" to "TAB", "left" to "LEFT", "up" to "UP", "down" to "DOWN", "right" to "RIGHT", "del" to "DEL")
        val REPEAT_KEYS = setOf("left", "up", "down", "right", "del")
        val PART_NAMES = mapOf("fn" to "Fn（ファンクションキーなど）", "bksp" to "⌫（1文字消す）", "enter" to "⏎（Enter）")
        val FN_KEYS = (1..12).map { "F$it" to "F$it" } + listOf(
            "Home" to "HOME", "End" to "END", "PgUp" to "PGUP", "PgDn" to "PGDN",
            "Ins" to "INS", "PrtSc" to "PRTSC", "半/全" to "KANJI", "Winのみ" to "WIN",
        )
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(fieldPoll)
        handler.removeCallbacks(quickQuery)
        if (Link.onField != null) Link.onField = null
    }
}
