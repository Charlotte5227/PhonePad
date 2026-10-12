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
import kotlin.math.hypot

/** PCの画面を表示する。触れた場所をクリックすることもできる */
class ScreenActivity : Activity() {
    private lateinit var screen: ScreenView
    private lateinit var status: TextView
    private lateinit var ctrlBtn: KeyButton
    private lateinit var qualityBtn: KeyButton

    @Volatile
    private var running = false

    @Volatile
    private var shownSeq = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        screen = ScreenView(this)
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
        }
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
        status = label(this, "", 12f, C.SUB).apply {
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            setPadding(dpi(10), 0, 0, 0)
        }
        bar.addView(status)
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        root.setOnApplyWindowInsetsListener { v, ins ->
            val c = ins.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            v.setPadding(c.left, c.top, c.right, c.bottom)
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
 * PC画面の表示。2本指で拡大・移動。
 * 操作ONのとき: タップ=左クリック、長押し=右クリック、長押しして動かす=ドラッグ
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ScreenView(ctx: Context) : View(ctx) {
    var control = true
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
    private var pinchDist = 0f
    private var pinchZoom = 1f
    private var midX = 0f
    private var midY = 0f
    private var lastTapTime = 0L

    private val longPress = Runnable {
        if (!moved && !multi && control) {
            longPressed = true
            Haptics.heavy()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; multi = false; longPressed = false; dragging = false
                postDelayed(longPress, 450)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    removeCallbacks(longPress)
                    if (dragging) {
                        Link.send("MB L 0"); dragging = false
                    }
                    multi = true
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    pinchZoom = zoom
                    midX = (e.getX(0) + e.getX(1)) / 2
                    midY = (e.getY(0) + e.getY(1)) / 2
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (multi && e.pointerCount >= 2) {
                    // 2本指: 拡大縮小と移動
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val mx = (e.getX(0) + e.getX(1)) / 2
                    val my = (e.getY(0) + e.getY(1)) / 2
                    val newZoom = (pinchZoom * d / pinchDist).coerceIn(1f, 6f)
                    // 指の中心を基準に拡大する
                    val f = newZoom / zoom
                    panX = (panX - (mx - width / 2f)) * f + (mx - width / 2f) + (mx - midX)
                    panY = (panY - (my - height / 2f)) * f + (my - height / 2f) + (my - midY)
                    zoom = newZoom
                    midX = mx; midY = my
                } else if (!multi) {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (control && longPressed) {
                        // 長押ししてから動かす → ドラッグ
                        if (!dragging) {
                            sendMove(downX, downY)
                            Link.send("MB L 1")
                            dragging = true
                        }
                        sendMove(e.x, e.y)
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
                when {
                    multi -> {}
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
                        }
                    }

                    !control && !moved -> {
                        // 操作OFFのときはダブルタップで拡大/元に戻す
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            if (zoom > 1.05f) {
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
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) Link.send("MB L 0")
                dragging = false
            }
        }
        invalidate()
        return true
    }
}
