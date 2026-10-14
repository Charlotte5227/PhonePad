package com.phonepad.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

/** PCの画面を表示する。触れた場所をクリックすることもできる */
class ScreenActivity : Activity() {
    private lateinit var screen: ScreenView
    private lateinit var status: TextView
    private lateinit var ctrlBtn: KeyButton
    private lateinit var qualityBtn: KeyButton
    private lateinit var kbBtn: KeyButton
    private lateinit var dragBtn: KeyButton
    private lateinit var input: KeyInputView
    private lateinit var panel: LinearLayout
    private lateinit var echoView: TextView
    private lateinit var barScroll: View
    private val echo = StringBuilder()
    private var mods: Modifiers? = null
    private val modButtons = HashMap<Int, KeyButton>()
    private var imeShown = false

    @Volatile
    private var running = false

    @Volatile
    private var shownSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val screenFrame = FrameLayout(this)
        column.addView(screenFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        screen = ScreenView(this)
        screenFrame.addView(screen, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        }
        bar.addView(KeyButton(this, "≡", 20f).apply {
            alpha = 0.85f
            onTap = { drawer.open() }
        }, LinearLayout.LayoutParams(dpi(44), dpi(38)).apply { rightMargin = dpi(6) })
        bar.addView(KeyButton(this, "← 戻る", 13f).apply {
            alpha = 0.85f
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = { finish() }
        }, LinearLayout.LayoutParams(-2, dpi(38)))
        ctrlBtn = KeyButton(this, "", 13f).apply {
            alpha = 0.85f
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = {
                Prefs.screenControl = !Prefs.screenControl
                refreshButtons()
                if (Prefs.screenControl) {
                    android.widget.Toast.makeText(
                        this@ScreenActivity,
                        "タップ=クリック　長押し=右クリック　長押しして動かす=ドラッグ\n" +
                            "2本指でなぞる=スクロール　2本指で広げる=拡大\n" +
                            "1本指でなぞる=" + (if (Prefs.screenDragCursor) "カーソル移動" else "スクロール") + "（☑で切り替え）",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        bar.addView(ctrlBtn, LinearLayout.LayoutParams(-2, dpi(38)).apply { leftMargin = dpi(6) })
        qualityBtn = KeyButton(this, "", 13f).apply {
            alpha = 0.85f
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = {
                Prefs.screenQuality = (Prefs.screenQuality + 1) % 3
                refreshButtons()
            }
        }
        bar.addView(qualityBtn, LinearLayout.LayoutParams(-2, dpi(38)).apply { leftMargin = dpi(6) })
        // 1本指でなぞったときの動き: チェックでカーソル移動、外すとスクロール
        dragBtn = KeyButton(this, "", 13f).apply {
            alpha = 0.85f
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = {
                Prefs.screenDragCursor = !Prefs.screenDragCursor
                refreshButtons()
            }
        }
        bar.addView(dragBtn, LinearLayout.LayoutParams(-2, dpi(38)).apply { leftMargin = dpi(6) })

        // 文字入力: 下に入力パネル (送った文字・よく使うキー・入力欄) を出す。画面はその上に収める
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, dp(16f))
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
            visibility = View.GONE
        }
        echoView = label(this, "", 13f, C.SUB).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
            setPadding(dpi(4), 0, dpi(4), dpi(6))
        }
        panel.addView(echoView)
        val mods = Modifiers { paintMods() }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(lab: String, repeat: Boolean = false, act: () -> Unit) {
            keys.addView(KeyButton(this, lab, 14f).apply {
                this.repeat = repeat
                onTap = act
                setPadding(dpi(10), 0, dpi(10), 0)
            }, LinearLayout.LayoutParams(-2, dpi(42)).apply { rightMargin = dpi(5) })
        }
        fun send(k: String) = Link.send("KY $k ${mods.consume()}")
        key("Esc") { send("ESC") }
        key("Tab") { send("TAB") }
        for ((lab, bit) in listOf("Ctrl" to Modifiers.CTRL, "Shift" to Modifiers.SHIFT, "Alt" to Modifiers.ALT, "Win" to Modifiers.WIN)) {
            val b = KeyButton(this, lab, 14f).apply {
                onTap = { mods.tap(bit) }
                setPadding(dpi(10), 0, dpi(10), 0)
            }
            modButtons[bit] = b
            keys.addView(b, LinearLayout.LayoutParams(-2, dpi(42)).apply { rightMargin = dpi(5) })
        }
        this.mods = mods
        key("←", true) { send("LEFT") }
        key("↑", true) { send("UP") }
        key("↓", true) { send("DOWN") }
        key("→", true) { send("RIGHT") }
        key("⌫", true) { send("BACK"); onEcho(null) }
        key("Del", true) { send("DEL") }
        key("⏎") { send("ENTER"); onEcho("\n") }
        panel.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(keys)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(6) })
        val inRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = KeyInputView(this).apply {
            applyMode(Prefs.latinOnly)
            sender = ImeSender(mods) { onEcho(it) }
            hint = "ここに打った文字はすぐPCへ送られます"
            setHintTextColor(C.SUB)
            setTextColor(C.INK)
            textSize = 16f
            background = rounded(C.BG, dp(10f), C.LINE, dpi(1))
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
        }
        inRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        inRow.addView(KeyButton(this, "閉じる", 14f).apply {
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = { hideIme() }
        }, LinearLayout.LayoutParams(-2, dpi(46)).apply { leftMargin = dpi(6) })
        panel.addView(inRow)
        showEcho()

        kbBtn = KeyButton(this, "⌨ 文字入力", 13f).apply {
            alpha = 0.85f
            setPadding(dpi(12), 0, dpi(12), 0)
            onTap = { if (panel.visibility == View.VISIBLE) hideIme() else showIme() }
        }
        bar.addView(kbBtn, LinearLayout.LayoutParams(-2, dpi(38)).apply { leftMargin = dpi(6) })
        status = label(this, "", 12f, C.SUB).apply {
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            setPadding(dpi(10), 0, 0, 0)
        }
        bar.addView(status)
        // ボタンが画面に入りきらないときは横にずらして見られるようにする
        val barScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        screenFrame.addView(barScroll, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        this.barScroll = barScroll
        // 横向きでは、上のボタンを「＞」にしまう (押すと出て「＜」になる。画面に触れるとしまう)
        menuBtn = KeyButton(this, "＞", 18f).apply {
            contentDescription = "ボタンを出す"
            alpha = 0.8f
            visibility = View.GONE
            onTap = { setMenuOpen(!menuOpen) }
        }
        screenFrame.addView(menuBtn, FrameLayout.LayoutParams(dpi(46), dpi(40), Gravity.TOP or Gravity.START).apply {
            setMargins(dpi(8), dpi(8), 0, 0)
        })
        screen.onTouchStart = { if (land && menuOpen) setMenuOpen(false) }
        screenFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val isLand = (r - l) > (b - t)
            if (isLand != land) {
                land = isLand
                screenFrame.post { applyLand() }
            }
        }
        column.addView(panel, LinearLayout.LayoutParams(-1, -2))

        root.setOnApplyWindowInsetsListener { v, ins ->
            // キーボードが出ているときは、その上に画面を収める
            val c = ins.getInsets(
                WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars() or WindowInsets.Type.ime(),
            )
            v.setPadding(c.left, c.top, c.right, c.bottom)
            val shown = ins.isVisible(WindowInsets.Type.ime())
            if (shown != imeShown) {
                imeShown = shown
                kbBtn.active = if (shown) 1 else 0
                if (shown) {
                    // 画面がパネルの上に縮んでから、最後にタップした場所を拡大する
                    if (Prefs.screenAutoZoom) screen.postDelayed({ screen.focusLast() }, 120)
                } else {
                    closePanel()
                }
            }
            WindowInsets.CONSUMED
        }
        setContentView(root)
        refreshButtons()
        hideBars()
    }

    private fun refreshButtons() {
        ctrlBtn.text = if (Prefs.screenControl) "操作: ON" else "操作: OFF"
        ctrlBtn.active = if (Prefs.screenControl) 1 else 0
        qualityBtn.text = "画質: " + when (Prefs.screenQuality) {
            0 -> "低"; 2 -> "高"; else -> "標準"
        }
        screen.control = Prefs.screenControl
        screen.dragCursor = Prefs.screenDragCursor
        dragBtn.text = (if (Prefs.screenDragCursor) "☑" else "☐") + " なぞってカーソル移動"
        dragBtn.active = if (Prefs.screenDragCursor) 1 else 0
        dragBtn.visibility = if (Prefs.screenControl) View.VISIBLE else View.GONE
    }

    private var land = false
    private var menuOpen = false
    private val drawer by lazy { MenuDrawer(this) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isOpen) {
            drawer.close()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private lateinit var menuBtn: KeyButton

    private fun applyLand() {
        val typing = panel.visibility == View.VISIBLE
        menuBtn.visibility = if (land && !typing) View.VISIBLE else View.GONE
        val lp = barScroll.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = if (land) dpi(54) else 0
        barScroll.layoutParams = lp
        barScroll.visibility = when {
            typing -> View.GONE
            land -> if (menuOpen) View.VISIBLE else View.GONE
            else -> View.VISIBLE
        }
        menuBtn.active = if (land && menuOpen) 1 else 0
        menuBtn.text = if (land && menuOpen) "＜" else "＞"
        menuBtn.contentDescription = if (land && menuOpen) "ボタンをしまう" else "ボタンを出す"
    }

    private fun setMenuOpen(open: Boolean) {
        menuOpen = open
        applyLand()
    }

    private fun closePanel() {
        input.clearFocus()
        panel.visibility = View.GONE
        menuOpen = false
        applyLand()
        screen.post { screen.restoreZoom() }
    }

    private fun onEcho(t: String?) {
        if (t == null) {
            if (echo.isNotEmpty()) echo.setLength(echo.length - 1)
        } else {
            echo.append(t)
        }
        if (echo.length > 200) echo.delete(0, echo.length - 200)
        showEcho()
    }

    private fun showEcho() {
        val t = echo.toString().replace("\n", "⏎")
        echoView.text = if (t.isEmpty()) {
            "先にPCの画面で入力したい欄をタップしてから打ってください"
        } else {
            "送った文字: $t"
        }
    }

    private fun paintMods() {
        val m = mods ?: return
        for ((bit, b) in modButtons) b.active = m.state(bit)
    }

    private fun showIme() {
        panel.visibility = View.VISIBLE
        applyLand()
        input.requestFocus()
        input.post {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, 0)
        }
    }

    private fun hideIme() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        closePanel()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        startFetching()
    }

    override fun onPause() {
        super.onPause()
        running = false
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    /** 画面を取りに行き続ける */
    private fun startFetching() {
        if (running) return
        running = true
        thread(name = "screen", isDaemon = true) {
            val bitmaps = arrayOfNulls<Bitmap>(2)
            var idx = 0
            var frames = 0
            var fpsT = System.currentTimeMillis()
            var postedSeq = 0
            while (running) {
                val t = Prefs.target
                val host = Link.host ?: t?.hosts?.firstOrNull()
                if (t == null || host == null) {
                    setStatus("PCに接続していません")
                    Thread.sleep(1000)
                    continue
                }
                val (w, q) = when (Prefs.screenQuality) {
                    0 -> 640 to 45
                    2 -> 1440 to 75
                    else -> 1024 to 60
                }
                val started = System.currentTimeMillis()
                var conn: HttpURLConnection? = null
                try {
                    val https = URL("https://$host:47804/frame?w=$w&q=$q").openConnection() as javax.net.ssl.HttpsURLConnection
                    // 暗号化: PCの証明書の指紋で確認する (名前の確認は指紋で代わりにする)
                    https.sslSocketFactory = Tls.factory(Tls.PinTrust(t.fp))
                    https.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    conn = https
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("X-Code", t.code)
                    val code = conn.responseCode
                    if (code != 200) {
                        val msg = try {
                            conn.errorStream?.bufferedReader()?.readText()?.trim()
                        } catch (_: Exception) {
                            null
                        }
                        setStatus(
                            when (code) {
                                403 -> "接続コードが違います"
                                429 -> "しばらく待ってください"
                                else -> msg?.ifEmpty { null } ?: "画面を取得できません ($code)"
                            },
                        )
                        Thread.sleep(1000)
                        continue
                    }
                    val bytes = conn.inputStream.use { it.readBytes() }
                    // 表示中でない方のビットマップを使い回して読み込む
                    val opt = BitmapFactory.Options().apply {
                        inMutable = true
                        inBitmap = bitmaps[idx]
                    }
                    val bmp = try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
                    } catch (_: IllegalArgumentException) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } ?: continue
                    bitmaps[idx] = bmp
                    idx = 1 - idx
                    postedSeq++
                    val seq = postedSeq
                    runOnUiThread {
                        screen.setFrame(bmp)
                        shownSeq = seq
                    }
                    // 画面が切り替わるまで待つ (表示中の画像を上書きしないため)
                    var waited = 0
                    while (running && shownSeq != seq && waited < 500) {
                        Thread.sleep(5); waited += 5
                    }
                    frames++
                    val now = System.currentTimeMillis()
                    if (now - fpsT >= 1000) {
                        setStatus("${frames}コマ/秒")
                        frames = 0
                        fpsT = now
                    }
                    // 最大でも1秒に20コマ
                    val spent = System.currentTimeMillis() - started
                    if (spent < 50) Thread.sleep(50 - spent)
                } catch (e: Exception) {
                    setStatus("画面を取得できません: ${e.javaClass.simpleName}")
                    try {
                        Thread.sleep(1000)
                    } catch (_: InterruptedException) {
                    }
                } finally {
                    conn?.disconnect()
                }
            }
        }
    }
}

/**
 * PC画面の表示。2本指で広げる/つまむ=拡大縮小。
 * 操作ONのとき: タップ=左クリック、長押し=右クリック、長押しして動かす=ドラッグ、
 *   なぞる=スクロールかカーソル移動 (設定。スクロールの設定で拡大中は表示の移動)、2本指でなぞる=スクロール、
 *   2本指タップ=右クリック、3本指タップ=中クリック
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScreenView(ctx: Context) : View(ctx) {
    var control = true

    /** 画面に触れたとき (横向きのメニューをしまうため) */
    var onTouchStart: (() -> Unit)? = null

    /** 1本指でなぞったとき: true=カーソル移動 / false=スクロール */
    var dragCursor = false
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.SUB; textSize = dp(15f); textAlign = Paint.Align.CENTER
    }

    // 表示の拡大率と移動量
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    fun setFrame(b: Bitmap) {
        bmp = b
        invalidate()
    }

    /** 画像の表示位置 (拡大前) */
    private fun baseScale(b: Bitmap) = minOf(width.toFloat() / b.width, height.toFloat() / b.height)

    private fun updateMatrix(b: Bitmap) {
        val s = baseScale(b) * zoom
        val dw = b.width * s
        val dh = b.height * s
        // はみ出しすぎないように移動量を制限
        val maxX = maxOf(0f, (dw - width) / 2)
        val maxY = maxOf(0f, (dh - height) / 2)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - dw) / 2 + panX, (height - dh) / 2 + panY)
    }

    override fun onDraw(c: Canvas) {
        val b = bmp
        if (b == null) {
            c.drawText("PCの画面を読み込んでいます…", width / 2f, height / 2f, hint)
            return
        }
        updateMatrix(b)
        c.drawBitmap(b, matrix, paint)
    }

    /** 表示上の位置 → PC画面上の位置 (0〜65535)。画面外なら null */
    private fun toPc(x: Float, y: Float): Pair<Int, Int>? {
        val b = bmp ?: return null
        updateMatrix(b)
        val inv = Matrix()
        if (!matrix.invert(inv)) return null
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val u = p[0] / b.width
        val v = p[1] / b.height
        if (u < 0 || u > 1 || v < 0 || v > 1) return null
        return Pair((u * 65535).toInt(), (v * 65535).toInt())
    }

    private fun sendMove(x: Float, y: Float): Boolean {
        val p = toPc(x, y) ?: return false
        Link.send("MA ${p.first} ${p.second}")
        return true
    }

    // ---- タッチ ----
    private val slop = dp(10f)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var multi = false
    private var longPressed = false
    private var dragging = false
    private var scrolling1 = false
    private var cursorMoving = false
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var startMidX = 0f
    private var startMidY = 0f
    private var midX = 0f
    private var midY = 0f
    private var lastTapTime = 0L
    private var downTime = 0L
    private var maxPointers = 0

    /** 2本指の動き: 未決定 / 拡大縮小 / スクロール */
    private var twoMode = 0
    private val wheel = WheelAccum()

    private val longPress = Runnable {
        if (!moved && !multi && control) {
            longPressed = true
            Haptics.heavy()
        }
    }

    private val zoomed get() = zoom > 1.05f

    private fun scroll(dx: Float, dy: Float) {
        // 指の動きに中身がついてくる向き (スマホと同じ)
        val k = 6f * Prefs.scrollSpeed / resources.displayMetrics.density
        wheel.add(dy * k, -dx * k)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onTouchStart?.invoke()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; multi = false; longPressed = false; dragging = false; scrolling1 = false
                cursorMoving = false
                downTime = System.currentTimeMillis()
                maxPointers = 1
                wheel.reset()
                postDelayed(longPress, 450)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) {
                    removeCallbacks(longPress)
                    if (dragging) {
                        Link.send("MB L 0"); dragging = false
                    }
                    multi = true
                    twoMode = 0
                    wheel.reset()
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    pinchZoom = zoom
                    midX = (e.getX(0) + e.getX(1)) / 2
                    midY = (e.getY(0) + e.getY(1)) / 2
                    startMidX = midX; startMidY = midY
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    if (twoMode == 0) {
                        // 指の間隔が変わったら拡大縮小、そろって動いたらスクロール
                        when {
                            abs(d - pinchDist) > dp(28f) -> twoMode = 1
                            control && hypot(mx - startMidX, my - startMidY) > dp(16f) -> {
                                twoMode = 2
                                sendMove(startMidX, startMidY)
                            }

                            !control && hypot(mx - startMidX, my - startMidY) > dp(16f) -> twoMode = 1
                        }
                    }
                    if (twoMode == 1) {
                        val newZoom = (pinchZoom * d / pinchDist).coerceIn(1f, 6f)
                        // 指の中心を基準に拡大する
                        val f = newZoom / zoom
                        panX = (panX - (mx - width / 2f)) * f + (mx - width / 2f) + (mx - midX)
                        panY = (panY - (my - height / 2f)) * f + (my - height / 2f) + (my - midY)
                        zoom = newZoom
                    } else if (twoMode == 2) {
                        scroll(mx - midX, my - midY)
                    }
                    if (twoMode != 0) {
                        midX = mx; midY = my
                    }
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        // 操作ONのとき、1本指でなぞると カーソル移動 か スクロール (拡大中のスクロール設定は表示の移動)
                        if (control && !longPressed) {
                            if (dragCursor) {
                                cursorMoving = true
                            } else if (!zoomed) {
                                scrolling1 = true
                                sendMove(downX, downY)
                            }
                        }
                    }
                    if (control && longPressed) {
                        // 長押ししてから動かす → ドラッグ
                        if (!dragging) {
                            sendMove(downX, downY)
                            Link.send("MB L 1")
                            dragging = true
                        }
                        sendMove(e.x, e.y)
                    } else if (cursorMoving) {
                        sendMove(e.x, e.y)
                    } else if (scrolling1) {
                        scroll(e.x - lastX, e.y - lastY)
                    } else if (moved) {
                        // 1本指で動かす → 表示の移動 (拡大しているとき)
                        panX += e.x - lastX
                        panY += e.y - lastY
                    }
                    lastX = e.x; lastY = e.y
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val quick = System.currentTimeMillis() - downTime < 300
                when {
                    multi -> {
                        // 2本指で軽くタップ → 右クリック / 3本指 → 中クリック
                        if (control && twoMode == 0 && quick) {
                            if (sendMove(startMidX, startMidY)) {
                                Link.send(if (maxPointers >= 3) "MC M" else "MC R")
                                Haptics.tick()
                            }
                        }
                    }

                    dragging -> {
                        sendMove(e.x, e.y)
                        Link.send("MB L 0")
                    }

                    control && longPressed -> {
                        if (sendMove(downX, downY)) Link.send("MC R")
                    }

                    control && !moved -> {
                        if (sendMove(downX, downY)) {
                            Link.send("MC L")
                            Haptics.tick()
                            noteTap(downX, downY)
                        }
                    }

                    !control && !moved -> {
                        // 操作OFFのときはダブルタップで拡大/元に戻す
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            if (zoomed) {
                                zoom = 1f; panX = 0f; panY = 0f
                            } else {
                                zoom = 2.5f
                                panX = -(downX - width / 2f) * 1.5f
                                panY = -(downY - height / 2f) * 1.5f
                            }
                            lastTapTime = 0
                        } else {
                            lastTapTime = now
                        }
                    }
                }
                dragging = false
                scrolling1 = false
                cursorMoving = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) Link.send("MB L 0")
                dragging = false
                scrolling1 = false
            }
        }
        invalidate()
        return true
    }

    /** 拡大を元に戻す */
    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        invalidate()
    }

    // ---- 文字入力のときの拡大 ----
    // 最後にタップした場所 (PC画面上の割合)。入力欄をタップしてから文字を打つことが多いので、そこを拡大する
    private var lastU = -1f
    private var lastV = -1f
    private var saved: FloatArray? = null

    private fun noteTap(x: Float, y: Float) {
        val p = toPc(x, y) ?: return
        lastU = p.first / 65535f
        lastV = p.second / 65535f
    }

    fun focusLast() {
        val b = bmp ?: return
        if (lastU < 0f || width == 0) return
        if (saved == null) saved = floatArrayOf(zoom, panX, panY)
        zoom = maxOf(zoom, 2.2f)
        val s = baseScale(b) * zoom
        panX = b.width * s * (0.5f - lastU)
        panY = b.height * s * (0.5f - lastV)
        invalidate()
    }

    fun restoreZoom() {
        val v = saved ?: return
        zoom = v[0]; panX = v[1]; panY = v[2]
        saved = null
        invalidate()
    }
}
