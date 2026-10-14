package com.phonepad.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.atan2
import kotlin.math.hypot

/** コントローラー画面 (横向き) */
class ControllerActivity : Activity() {
    private lateinit var view: ControllerView
    private val linkListener: (Link.State) -> Unit = { view.invalidate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        view = ControllerView(
            this,
            onSwitch = { finish() },
            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
        )
        view.setOnApplyWindowInsetsListener { v, ins ->
            // カメラの切り欠き部分にはボタンを置かない
            val c = ins.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(view)
        hideBars()
        // 接続画面の「コントローラーの配置を変える」から開いたときは、すぐ配置の変更にする
        if (intent.getBooleanExtra(EXTRA_EDIT, false)) view.post { view.startEditing() }
    }

    companion object {
        const val EXTRA_EDIT = "edit"
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

    override fun onStart() {
        super.onStart()
        Link.addListener(linkListener)
        if (Link.state == Link.State.DISCONNECTED) {
            Prefs.target?.let { Link.connect(it, null, persistent = true) }
        }
    }

    override fun onStop() {
        super.onStop()
        Link.removeListener(linkListener)
    }

    /** PC から配置が届いた・アプリごとの配置に切り替わった */
    private val syncListener: () -> Unit = {
        view.abandonEditing()
        view.reloadPrefs()
    }

    override fun onResume() {
        super.onResume()
        ConfigSync.listeners.add(syncListener)
        ConfigSync.onNotice = { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show() }
        view.reloadPrefs()
        view.error = null
        Link.onGamepadError = { msg ->
            view.error = "コントローラーを使えません: $msg（PCの画面からドライバーを入れてください）"
            view.invalidate()
        }
        // ゲームの振動をスマホで再現
        Link.onRumble = { large, small -> if (Prefs.rumble) Haptics.rumble(large, small) }
        hideBars()
    }

    override fun onPause() {
        super.onPause()
        ConfigSync.listeners.remove(syncListener)
        ConfigSync.onNotice = null
        Link.onRumble = null
        Haptics.rumble(0, 0)
        view.releaseAll()
        view.finishEditing()
        Link.send("GX")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 配置の編集中は、戻る操作で編集を終える
        if (view.editing) {
            view.finishEditing()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ControllerView(
    ctx: Activity,
    private val onSwitch: () -> Unit,
    private val onSettings: () -> Unit,
) : View(ctx) {

    companion object {
        const val UP = 0x0001
        const val DOWN = 0x0002
        const val LEFT = 0x0004
        const val RIGHT = 0x0008
        const val START = 0x0010
        const val BACK = 0x0020
        const val LTHUMB = 0x0040
        const val RTHUMB = 0x0080
        const val LB = 0x0100
        const val RB = 0x0200
        const val GUIDE = 0x0400
        const val XA = 0x1000
        const val XB = 0x2000
        const val XX = 0x4000
        const val XY = 0x8000
        const val LT = 1 shl 16
        const val RT = 1 shl 17
        const val TURBO_ELIGIBLE = (0x3FFFF) and (START or BACK or GUIDE).inv()

        /** 各部品の初期位置 (画面の幅・高さに対する割合) */
        private val DEFAULTS = linkedMapOf(
            "ZL" to floatArrayOf(0.075f, 0.095f), "L" to floatArrayOf(0.075f, 0.315f),
            "ZR" to floatArrayOf(0.925f, 0.095f), "R" to floatArrayOf(0.925f, 0.315f),
            "LS" to floatArrayOf(0.29f, 0.36f), "DP" to floatArrayOf(0.27f, 0.78f),
            "FACE" to floatArrayOf(0.73f, 0.32f), "RS" to floatArrayOf(0.71f, 0.74f),
            "MINUS" to floatArrayOf(0.40f, 0.17f), "PLUS" to floatArrayOf(0.60f, 0.17f),
            "T" to floatArrayOf(0.445f, 0.36f), "H" to floatArrayOf(0.555f, 0.36f),
            "L3" to floatArrayOf(0.44f, 0.60f), "R3" to floatArrayOf(0.56f, 0.60f),
            "GEAR" to floatArrayOf(0.46f, 0.06f), "EDIT" to floatArrayOf(0.54f, 0.06f),
            "SWITCH" to floatArrayOf(0.50f, 0.885f),
        )
        private val NAMES = mapOf(
            "ZL" to "ZL", "L" to "L", "ZR" to "ZR", "R" to "R", "LS" to "Lスティック", "RS" to "Rスティック",
            "DP" to "十字キー", "FACE" to "ABXY", "MINUS" to "−", "PLUS" to "+", "T" to "T", "H" to "H",
            "L3" to "L3", "R3" to "R3", "GEAR" to "設定", "EDIT" to "配置", "SWITCH" to "切替",
        )
    }

    private enum class Kind { NORMAL, FACE, SHOULDER, TURBO, SWITCH, GEAR, EDIT }

    private inner class Btn(
        val label: String,
        val fixedBit: Int,
        val kind: Kind = Kind.NORMAL,
        val face: Char = ' ',
    ) {
        var id = ""
        var cx = 0f
        var cy = 0f
        var r = 0f
        val rect = RectF()
        val isRect get() = kind == Kind.SHOULDER || kind == Kind.SWITCH
        val bit: Int
            get() = if (kind == Kind.FACE) faceBit(face) else fixedBit

        fun hit(x: Float, y: Float, scale: Float): Float? {
            return if (isRect) {
                val pad = dp(10f)
                if (x >= rect.left - pad && x <= rect.right + pad && y >= rect.top - pad && y <= rect.bottom + pad) 0f else null
            } else {
                val d = hypot(x - cx, y - cy)
                if (d <= r * scale) d / r else null
            }
        }
    }

    private inner class Stick(val label: String) {
        var homeX = 0f
        var homeY = 0f
        var cx = 0f
        var cy = 0f
        var r = 0f
        var kx = 0f
        var ky = 0f
        var active = false

        fun begin(x: Float, y: Float) {
            active = true
            if (floatingSticks) {
                cx = x; cy = y
            } else {
                cx = homeX; cy = homeY
            }
            set(x, y)
        }

        fun set(x: Float, y: Float) {
            var dx = x - cx
            var dy = y - cy
            val l = hypot(dx, dy)
            if (l > r) {
                dx = dx / l * r; dy = dy / l * r
            }
            kx = dx; ky = dy
        }

        fun end() {
            active = false
            kx = 0f; ky = 0f
            cx = homeX; cy = homeY
        }

        /** 遊び・感度・カーブを反映した出力 (-32767〜32767) */
        fun axes(): Pair<Int, Int> {
            if (r <= 0f) return 0 to 0
            val nx = kx / r
            val ny = -ky / r
            val m = hypot(nx, ny)
            if (m <= deadzone || m == 0f) return 0 to 0
            val norm = ((m - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
            val out = (Math.pow(norm.toDouble(), curve.toDouble()).toFloat() * sensitivity).coerceAtMost(1f)
            val k = out / m
            return (nx * k * 32767).toInt().coerceIn(-32767, 32767) to (ny * k * 32767).toInt().coerceIn(-32767, 32767)
        }
    }

    private sealed class Owner {
        class B(val btn: Any) : Owner()
        class S(val stick: Any) : Owner()
        object D : Owner()
        class A(var area: Any) : Owner()
    }

    // ---- 自由な形のエリア: 指でなぞって描いた形を、好きなボタンにする (点は割合 0〜1) ----
    private class Area(val key: String, val pts: FloatArray)

    private val areas = ArrayList<Area>()
    private val AREA_KEYS = listOf("A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−", "H", "L3", "R3", "↑", "↓", "←", "→")

    private fun areaBit(k: String): Int = when (k) {
        "A", "B", "X", "Y" -> faceBit(k[0])
        "L" -> LB; "R" -> RB; "ZL" -> LT; "ZR" -> RT
        "+" -> START; "−" -> BACK; "H" -> GUIDE; "L3" -> LTHUMB; "R3" -> RTHUMB
        "↑" -> UP; "↓" -> DOWN; "←" -> LEFT; "→" -> RIGHT
        else -> 0
    }

    private fun ax(u: Float) = contentL + u * contentW
    private fun ay(v: Float) = contentT + v * contentH

    /** 点が多角形の内側にあるか (交差の数で判定) */
    private fun inArea(a: Area, x: Float, y: Float): Boolean {
        var inside = false
        val p = a.pts
        var j = p.size - 2
        var i = 0
        while (i < p.size) {
            val xi = ax(p[i]); val yi = ay(p[i + 1]); val xj = ax(p[j]); val yj = ay(p[j + 1])
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
            i += 2
        }
        return inside
    }

    private fun areaAt(x: Float, y: Float): Int {
        for (i in areas.indices.reversed()) if (inArea(areas[i], x, y)) return i
        return -1
    }

    private fun areaCenter(a: Area): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var i = 0
        while (i < a.pts.size) {
            x += a.pts[i]; y += a.pts[i + 1]; i += 2
        }
        val n = a.pts.size / 2
        return (x / n) to (y / n)
    }

    private fun areasJson(): String {
        val arr = org.json.JSONArray()
        for (a in areas) {
            val o = org.json.JSONObject().put("k", a.key)
            val pa = org.json.JSONArray()
            for (v in a.pts) pa.put(v.toDouble())
            arr.put(o.put("p", pa))
        }
        return arr.toString()
    }

    private fun loadAreas(json: String) {
        areas.clear()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val k = o.optString("k")
                val pa = o.optJSONArray("p") ?: continue
                if (k !in AREA_KEYS || pa.length() < 6) continue
                areas.add(Area(k, FloatArray(pa.length()) { pa.getDouble(it).toFloat().coerceIn(0f, 1f) }))
            }
        } catch (_: Exception) {
        }
    }

    private fun hiddenId(id: String) = (specs[id]?.getOrNull(4) ?: 0f) > 0.5f

    // ---- 部品 ----
    private val bA = Btn("A", 0, Kind.FACE, 'A')
    private val bB = Btn("B", 0, Kind.FACE, 'B')
    private val bX = Btn("X", 0, Kind.FACE, 'X')
    private val bY = Btn("Y", 0, Kind.FACE, 'Y')
    private val bL = Btn("L", LB, Kind.SHOULDER)
    private val bR = Btn("R", RB, Kind.SHOULDER)
    private val bZL = Btn("ZL", LT, Kind.SHOULDER)
    private val bZR = Btn("ZR", RT, Kind.SHOULDER)
    private val bMinus = Btn("−", BACK)
    private val bPlus = Btn("+", START)
    private val bHome = Btn("H", GUIDE)
    private val bTurbo = Btn("T", 0, Kind.TURBO)
    private val bL3 = Btn("L3", LTHUMB)
    private val bR3 = Btn("R3", RTHUMB)
    private val bSwitch = Btn("⌨", 0, Kind.SWITCH)
    private val bGear = Btn("⚙", 0, Kind.GEAR)
    private val bEdit = Btn("✎", 0, Kind.EDIT)
    private val faces = listOf(bA, bB, bX, bY)
    private val circles = listOf(bA, bB, bX, bY, bMinus, bPlus, bHome, bTurbo, bL3, bR3, bGear, bEdit)
    private val rects = listOf(bL, bR, bZL, bZR, bSwitch)
    private val slideable = listOf(bA, bB, bX, bY, bL, bR, bZL, bZR)

    private val sL = Stick("L")
    private val sR = Stick("R")

    private var dcx = 0f
    private var dcy = 0f
    private var dOff = 0f
    private var dR = 0f
    private var dpadBits = 0
    private var faceCx = 0f
    private var faceCy = 0f
    private var faceOff = 0f

    private val owners = HashMap<Int, Owner>()

    // ---- ターボ ----
    private var auto = 0
    private var globalTurbo = false

    private var positional = false
    private var floatingSticks = true
    private var deadzone = 0.08f
    private var sensitivity = 1f
    private var curve = 1f
    private var rate = 15
    private var lastLine = ""
    var error: String? = null

    // ---- 配置 ----
    /** id → [x割合, y割合, 大きさ倍率] */
    private val specs = LinkedHashMap<String, FloatArray>()
    private val editBounds = LinkedHashMap<String, RectF>()

    init {
        loadLayout()
    }

    fun reloadPrefs() {
        positional = Prefs.positionalAbxy
        floatingSticks = Prefs.padStickFloating
        deadzone = Prefs.padDeadzone.coerceIn(0f, 0.6f)
        sensitivity = Prefs.padSensitivity.coerceIn(0.3f, 3f)
        curve = Prefs.padCurve.coerceIn(0.3f, 3f)
        rate = Prefs.turboRate
        loadLayout()
        layoutParts()
        lastLine = ""
        sendState()
        invalidate()
    }

    private fun faceBit(c: Char): Int = if (positional) {
        // Switchの位置 → Xboxの同じ位置
        when (c) {
            'A' -> XB; 'B' -> XA; 'X' -> XY; else -> XX
        }
    } else {
        when (c) {
            'A' -> XA; 'B' -> XB; 'X' -> XX; else -> XY
        }
    }

    private fun loadLayout() {
        specs.clear()
        for ((id, d) in DEFAULTS) specs[id] = floatArrayOf(d[0], d[1], 1f, 0f, 0f)
        for (item in Prefs.padLayout.split(";")) {
            val kv = item.split(":")
            if (kv.size != 2) continue
            val v = kv[1].split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size >= 3 && specs.containsKey(kv[0])) {
                specs[kv[0]] = floatArrayOf(
                    v[0].coerceIn(0f, 1f), v[1].coerceIn(0f, 1f), v[2].coerceIn(0.4f, 2.5f),
                    v.getOrElse(3) { 0f }.coerceIn(0f, 3f),
                    if (v.getOrElse(4) { 0f } > 0.5f) 1f else 0f,
                )
            }
        }
            loadAreas(Prefs.padAreas)
    }

    private fun saveLayout() {
        Prefs.padLayout = specs.entries.joinToString(";") { (id, v) ->
            "$id:${v[0]},${v[1]},${v[2]},${v.getOrElse(3) { 0f }.toInt()},${v.getOrElse(4) { 0f }.toInt()}"
        }
        Prefs.padAreas = areasJson()
    }

    // ---------------------------------------------------------------- 配置の計算

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutParts()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutParts()
    }

    private val contentL get() = paddingLeft.toFloat()
    private val contentT get() = paddingTop.toFloat()
    private val contentW get() = (width - paddingLeft - paddingRight).toFloat()
    private val contentH get() = (height - paddingTop - paddingBottom).toFloat()

    private fun layoutParts() {
        val L = contentL
        val T = contentT
        val W = contentW
        val H = contentH
        if (W <= 0 || H <= 0) return
        editBounds.clear()
        fun sp(id: String) = specs[id] ?: floatArrayOf(0.5f, 0.5f, 1f)
        fun cx(id: String) = L + sp(id)[0] * W
        fun cy(id: String) = T + sp(id)[1] * H
        fun sc(id: String) = sp(id)[2]
        fun bound(id: String, x: Float, y: Float, hw: Float, hh: Float) {
            editBounds[id] = RectF(x - hw, y - hh, x + hw, y + hh)
        }

        fun rectBtn(b: Btn, id: String, w: Float, h: Float) {
            b.id = id
            val x = cx(id)
            val y = cy(id)
            val s = sc(id)
            b.rect.set(x - w * s / 2, y - h * s / 2, x + w * s / 2, y + h * s / 2)
            bound(id, x, y, w * s / 2, h * s / 2)
        }
        rectBtn(bZL, "ZL", 0.15f * W, 0.19f * H)
        rectBtn(bL, "L", 0.15f * W, 0.21f * H)
        rectBtn(bZR, "ZR", 0.15f * W, 0.19f * H)
        rectBtn(bR, "R", 0.15f * W, 0.21f * H)
        rectBtn(bSwitch, "SWITCH", 0.20f * W, 0.17f * H)

        fun stick(s: Stick, id: String) {
            s.homeX = cx(id); s.homeY = cy(id); s.r = H * 0.17f * sc(id)
            if (!s.active) {
                s.cx = s.homeX; s.cy = s.homeY
            }
            bound(id, s.homeX, s.homeY, s.r, s.r)
        }
        stick(sL, "LS")
        stick(sR, "RS")

        dcx = cx("DP"); dcy = cy("DP"); dOff = H * 0.115f * sc("DP"); dR = H * 0.06f * sc("DP")
        bound("DP", dcx, dcy, dOff + dR, dOff + dR)

        faceCx = cx("FACE"); faceCy = cy("FACE"); faceOff = H * 0.13f * sc("FACE")
        val fr = H * 0.066f * sc("FACE")
        bY.cx = faceCx; bY.cy = faceCy - faceOff
        bB.cx = faceCx; bB.cy = faceCy + faceOff
        bX.cx = faceCx - faceOff; bX.cy = faceCy
        bA.cx = faceCx + faceOff; bA.cy = faceCy
        faces.forEach { it.r = fr; it.id = "FACE" }
        bound("FACE", faceCx, faceCy, faceOff + fr, faceOff + fr)

        fun circle(b: Btn, id: String, base: Float) {
            b.id = id
            b.cx = cx(id); b.cy = cy(id); b.r = H * base * sc(id)
            bound(id, b.cx, b.cy, b.r, b.r)
        }
        circle(bMinus, "MINUS", 0.05f)
        circle(bPlus, "PLUS", 0.05f)
        circle(bTurbo, "T", 0.062f)
        circle(bHome, "H", 0.062f)
        circle(bL3, "L3", 0.055f)
        circle(bR3, "R3", 0.055f)
        circle(bGear, "GEAR", 0.045f)
        circle(bEdit, "EDIT", 0.045f)
    }

    // ---------------------------------------------------------------- 入力

    private fun hit(x: Float, y: Float): Owner? {
        // 丸ボタン (一番近いもの)
        var best: Btn? = null
        var bestD = Float.MAX_VALUE
        for (b in circles) {
            if (hiddenId(b.id)) continue
            val d = b.hit(x, y, 1.3f) ?: continue
            if (d < bestD) {
                bestD = d; best = b
            }
        }
        if (best != null) return Owner.B(best)
        for (b in rects) if (!hiddenId(b.id) && b.hit(x, y, 1f) != null) return Owner.B(b)
        if (!hiddenId("DP") && hypot(x - dcx, y - dcy) < dOff + dR * 1.9f) return Owner.D
        val ai = areaAt(x, y)
        if (ai >= 0) return Owner.A(areas[ai])
        // スティック: 「触れた位置に出す」ときは周りの広い範囲で反応する
        val zone = if (floatingSticks) 2.1f else 1.5f
        val dl = if (hiddenId("LS")) Float.MAX_VALUE else hypot(x - sL.homeX, y - sL.homeY) / sL.r
        val dr = if (hiddenId("RS")) Float.MAX_VALUE else hypot(x - sR.homeX, y - sR.homeY) / sR.r
        if (dl < zone && dl <= dr) return Owner.S(sL)
        if (dr < zone) return Owner.S(sR)
        // ABXYの周辺は一番近いボタンにする (押し間違いを減らす)
        if (!hiddenId("FACE") && hypot(x - faceCx, y - faceCy) < faceOff + bA.r * 1.8f) {
            return Owner.B(faces.minBy { hypot(x - it.cx, y - it.cy) })
        }
        return null
    }

    private fun dpadDir(x: Float, y: Float): Int {
        val dx = x - dcx
        val dy = y - dcy
        if (hypot(dx, dy) < dOff * 0.3f) return 0
        val a = Math.toDegrees(atan2(-dy, dx).toDouble())
        val sector = (((a + 360.0 + 22.5) % 360.0) / 45.0).toInt()
        return when (sector) {
            0 -> RIGHT
            1 -> RIGHT or UP
            2 -> UP
            3 -> UP or LEFT
            4 -> LEFT
            5 -> LEFT or DOWN
            6 -> DOWN
            else -> DOWN or RIGHT
        }
    }

    private fun held(): Int {
        var m = dpadBits
        for (o in owners.values) {
            if (o is Owner.B) {
                val b = o.btn as Btn
                if (b.kind == Kind.NORMAL || b.kind == Kind.FACE || b.kind == Kind.SHOULDER) m = m or b.bit
            } else if (o is Owner.A) {
                m = m or areaBit((o.area as Area).key)
            }
        }
        return m
    }

    private fun pressed(b: Btn): Boolean = owners.values.any { it is Owner.B && it.btn === b }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) {
            editTouch(e)
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                val x = e.getX(i)
                val y = e.getY(i)
                val o = hit(x, y) ?: return true
                if (o is Owner.S && (o.stick as Stick).active) return true // 同じスティックに2本目の指
                owners[id] = o
                when (o) {
                    is Owner.S -> {
                        (o.stick as Stick).begin(x, y); Haptics.tick()
                    }

                    is Owner.D -> {
                        dpadBits = dpadDir(x, y); Haptics.tick()
                    }

                    is Owner.B -> {
                        val b = o.btn as Btn
                        if (b.kind == Kind.TURBO) turboPressed() else Haptics.tick()
                    }

                    is Owner.A -> Haptics.tick()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val o = owners[id] ?: continue
                    val x = e.getX(i)
                    val y = e.getY(i)
                    when (o) {
                        is Owner.S -> (o.stick as Stick).set(x, y)
                        is Owner.D -> {
                            val nd = dpadDir(x, y)
                            if (nd != dpadBits) {
                                dpadBits = nd
                                if (nd != 0) Haptics.tick()
                            }
                        }

                        is Owner.A -> {
                            // エリアからエリアへ指を滑らせられる
                            if (!inArea(o.area as Area, x, y)) {
                                val ai = areaAt(x, y)
                                if (ai >= 0) {
                                    o.area = areas[ai]
                                    Haptics.tick()
                                }
                            }
                        }

                        is Owner.B -> {
                            // ボタンからボタンへ指を滑らせられる
                            val cur = o.btn as Btn
                            if (cur in slideable && cur.hit(x, y, 1.15f) == null) {
                                val nb = slideable.firstOrNull { it !== cur && it.hit(x, y, 1.0f) != null }
                                if (nb != null) {
                                    owners[id] = Owner.B(nb)
                                    Haptics.tick()
                                }
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                val idxs = if (cancel) (0 until e.pointerCount).toList() else listOf(e.actionIndex)
                for (i in idxs) {
                    val id = e.getPointerId(i)
                    val o = owners.remove(id) ?: continue
                    when (o) {
                        is Owner.S -> (o.stick as Stick).end()
                        is Owner.D -> dpadBits = 0
                        is Owner.A -> {}
                        is Owner.B -> {
                            val b = o.btn as Btn
                            if (!cancel && b.hit(e.getX(i), e.getY(i), 1.3f) != null) {
                                when (b.kind) {
                                    Kind.SWITCH -> post { onSwitch() }
                                    Kind.GEAR -> post { onSettings() }
                                    Kind.EDIT -> post { startEditing() }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
        }
        sendState()
        invalidate()
        return true
    }

    /**
     * T単体 → 全体ターボ(押している間連打)のON/OFF
     * ボタンを押しながらT → そのボタンの自動連打のON/OFF
     */
    private fun turboPressed() {
        val others = held() and TURBO_ELIGIBLE
        if (others != 0) {
            auto = auto xor others
            Haptics.double()
        } else {
            globalTurbo = !globalTurbo
            Haptics.click()
        }
    }

    private fun sendState() {
        val (lx, ly) = sL.axes()
        val (rx, ry) = sR.axes()
        val line = "GP %x %x %d %d %d %d %d %d".format(
            held(), auto, if (globalTurbo) 1 else 0, rate, lx, ly, rx, ry,
        )
        if (line != lastLine) {
            Link.send(line)
            lastLine = line
        }
    }

    fun releaseAll() {
        owners.clear()
        dpadBits = 0
        sL.end(); sR.end()
        lastLine = ""
        invalidate()
    }

    // ---------------------------------------------------------------- 配置の編集

    var editing = false
        private set
    private var selected: String? = null
    private var dragging = false
    private var dragDx = 0f
    private var dragDy = 0f
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinching = false
    private val tbSmaller = RectF()
    private val tbBigger = RectF()
    private val tbReset = RectF()
    private val tbDone = RectF()
    private val tbShape = RectF()
    private val tbPreset = RectF()
    private val tbHide = RectF()
    private val tbArea = RectF()

    // エリアを描いている途中
    private var drawingKey: String? = null
    private var drawPts: ArrayList<Float>? = null
    private var areaLastX = 0f
    private var areaLastY = 0f

    private fun selArea(): Area? {
        val id = selected ?: return null
        if (!id.startsWith("AREA:")) return null
        return areas.getOrNull(id.removePrefix("AREA:").toIntOrNull() ?: -1)
    }

    fun startEditing() {
        if (editing) return
        releaseAll()
        Link.send("GX")
        editing = true
        selected = null
        Toast.makeText(context, "部品をドラッグで移動、2本指で大きさを変えられます", Toast.LENGTH_LONG).show()
        invalidate()
    }

    /** 保存せずに配置の変更をやめる (PC から別の配置が届いたとき) */
    fun abandonEditing() {
        if (!editing) return
        editing = false
        drawingKey = null
        drawPts = null
        selected = null
        dragging = false
        pinching = false
        invalidate()
    }

    fun finishEditing() {
        if (!editing) return
        editing = false
        drawingKey = null
        drawPts = null
        selected = null
        dragging = false
        pinching = false
        saveLayout()
        invalidate()
    }

    private fun layoutToolbar() {
        val W = contentW
        val H = contentH
        val bw = W * 0.104f
        val bh = H * 0.11f
        val gap = W * 0.008f
        val bars = listOf(tbSmaller, tbBigger, tbShape, tbHide, tbArea, tbPreset, tbReset, tbDone)
        val total = bw * bars.size + gap * (bars.size - 1)
        var x = contentL + (W - total) / 2
        val y = contentT + H * 0.02f
        for (r in bars) {
            r.set(x, y, x + bw, y + bh)
            x += bw + gap
        }
    }

    private val SHAPE_NAMES = listOf("標準", "丸", "角丸", "四角")

    /** 選んだ部品の形を 標準→丸→角丸→四角 の順に変える */
    private fun changeShape() {
        val id = selected
        if (id == null) {
            Toast.makeText(context, "先に部品に触れて選んでください", Toast.LENGTH_SHORT).show()
            return
        }
        if (id.startsWith("AREA:")) {
            Toast.makeText(context, "エリアの形は、消してから描き直してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (id == "LS" || id == "RS") {
            Toast.makeText(context, "スティックは丸のままです", Toast.LENGTH_SHORT).show()
            return
        }
        val v = specs[id] ?: return
        val next = ((v.getOrElse(3) { 0f }.toInt() + 1) % 4)
        specs[id] = floatArrayOf(v[0], v[1], v[2], next.toFloat(), v.getOrElse(4) { 0f })
        saveLayout()
        Haptics.tick()
        Toast.makeText(context, "形: ${SHAPE_NAMES[next]}", Toast.LENGTH_SHORT).show()
    }

    /** 部品を隠す・表示に戻す (エリアを選んでいるときは消す) */
    private fun toggleHide() {
        val id = selected
        if (id == null) {
            Toast.makeText(context, "隠す部品（または消すエリア）を選んでください", Toast.LENGTH_SHORT).show()
            return
        }
        val a = selArea()
        if (a != null) {
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("このエリア（${a.key}）を消しますか？")
                .setPositiveButton("消す") { _, _ ->
                    areas.remove(a)
                    selected = null
                    saveLayout()
                    invalidate()
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        if (id == "EDIT" || id == "SWITCH") {
            Toast.makeText(context, "配置（✎）と切替（⌨）は隠せません", Toast.LENGTH_SHORT).show()
            return
        }
        val v = specs[id] ?: return
        val hide = (v.getOrElse(4) { 0f }) < 0.5f
        specs[id] = floatArrayOf(v[0], v[1], v[2], v.getOrElse(3) { 0f }, if (hide) 1f else 0f)
        saveLayout()
        Haptics.tick()
        Toast.makeText(context, if (hide) "隠しました（使うときは見えません）" else "表示に戻しました", Toast.LENGTH_SHORT).show()
    }

    /** ボタンを選んでから、指でなぞってエリアを描く */
    private fun pickAreaKey() {
        android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("どのボタンのエリアを描きますか？")
            .setItems(AREA_KEYS.toTypedArray()) { _, i ->
                drawingKey = AREA_KEYS[i]
                selected = null
                Toast.makeText(context, "「${AREA_KEYS[i]}」にしたい場所を、指でなぞって囲んでください", Toast.LENGTH_LONG).show()
                invalidate()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    /** 配置のプリセット (保存・読み込み・削除) */
    private fun showPresets() {
        saveLayout()
        val obj = try {
            org.json.JSONObject(Prefs.padPresets)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        val names = obj.keys().asSequence().toList().sorted()
        val b = android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("配置のプリセット")
        if (names.isEmpty()) {
            b.setMessage("まだ保存されていません。「今の配置を保存」で、今の配置・大きさ・形を保存できます。")
        } else {
            b.setItems(names.toTypedArray()) { _, i ->
                // 新しい形式: {"l": 配置, "a": エリア}。前の形式は配置だけ
                val raw = obj.optString(names[i])
                val po = try {
                    org.json.JSONObject(raw)
                } catch (_: Exception) {
                    null
                }
                Prefs.padLayout = po?.optString("l") ?: raw
                Prefs.padAreas = po?.optString("a", "[]") ?: "[]"
                loadLayout(); layoutParts()
                selected = null
                invalidate()
                Toast.makeText(context, "「${names[i]}」を読み込みました", Toast.LENGTH_SHORT).show()
            }
        }
        b.setPositiveButton("今の配置を保存") { _, _ ->
            val et = android.widget.EditText(context).apply { hint = "名前（例: レースゲーム）"; isSingleLine = true }
            val box = android.widget.LinearLayout(context).apply {
                setPadding(dpi(20), dpi(8), dpi(20), 0)
                addView(et, android.widget.LinearLayout.LayoutParams(-1, -2))
            }
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("今の配置を保存")
                .setView(box)
                .setPositiveButton("保存") { _, _ ->
                    val name = et.text.toString().trim().ifEmpty { "プリセット${names.size + 1}" }
                    obj.put(name, org.json.JSONObject().put("l", Prefs.padLayout).put("a", Prefs.padAreas).toString())
                    Prefs.padPresets = obj.toString()
                    Toast.makeText(context, "「$name」として保存しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        if (names.isNotEmpty()) {
            b.setNeutralButton("削除…") { _, _ ->
                android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("削除するプリセット")
                    .setItems(names.toTypedArray()) { _, i ->
                        obj.remove(names[i])
                        Prefs.padPresets = obj.toString()
                        Toast.makeText(context, "「${names[i]}」を削除しました", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
        b.setNegativeButton("閉じる", null).show()
    }

    private fun scaleSelected(f: Float) {
        selArea()?.let { a ->
            val (cx, cy) = areaCenter(a)
            var i = 0
            while (i < a.pts.size) {
                a.pts[i] = (cx + (a.pts[i] - cx) * f).coerceIn(0f, 1f)
                a.pts[i + 1] = (cy + (a.pts[i + 1] - cy) * f).coerceIn(0f, 1f)
                i += 2
            }
            saveLayout()
            return
        }
        val id = selected ?: return
        val v = specs[id] ?: return
        v[2] = (v[2] * f).coerceIn(0.4f, 2.5f)
        layoutParts()
        saveLayout()
    }

    private fun editTouch(e: MotionEvent) {
        layoutToolbar()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x
                val y = e.y
                when {
                    tbSmaller.contains(x, y) -> {
                        scaleSelected(0.9f); Haptics.tick(); return
                    }

                    tbBigger.contains(x, y) -> {
                        scaleSelected(1.1f); Haptics.tick(); return
                    }

                    tbShape.contains(x, y) -> {
                        changeShape(); return
                    }

                    tbPreset.contains(x, y) -> {
                        Haptics.tick(); post { showPresets() }; return
                    }

                    tbHide.contains(x, y) -> {
                        post { toggleHide() }; return
                    }

                    tbArea.contains(x, y) -> {
                        Haptics.tick(); post { pickAreaKey() }; return
                    }

                    tbReset.contains(x, y) -> {
                        Prefs.padLayout = ""
                        Prefs.padAreas = "[]"
                        loadLayout(); layoutParts()
                        selected = null
                        Haptics.click()
                        Toast.makeText(context, "初期配置に戻しました", Toast.LENGTH_SHORT).show()
                        return
                    }

                    tbDone.contains(x, y) -> {
                        Haptics.click(); finishEditing(); return
                    }
                }
                // エリアを描く
                if (drawingKey != null) {
                    drawPts = arrayListOf(x, y)
                    return
                }
                // 指の下にある一番小さい部品を選ぶ (なければエリア)
                var id = editBounds.entries
                    .filter { it.value.contains(x, y) }
                    .minByOrNull { it.value.width() * it.value.height() }?.key
                if (id == null) {
                    val ai = areaAt(x, y)
                    if (ai >= 0) id = "AREA:$ai"
                }
                selected = id
                if (id != null && id.startsWith("AREA:")) {
                    areaLastX = x; areaLastY = y
                    dragging = true
                    Haptics.tick()
                } else if (id != null) {
                    val v = specs[id]!!
                    dragDx = x - (contentL + v[0] * contentW)
                    dragDy = y - (contentT + v[1] * contentH)
                    dragging = true
                    Haptics.tick()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (selected != null && e.pointerCount >= 2 && drawPts == null) {
                    pinchStartDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
                    pinchStartScale = specs[selected!!]?.get(2) ?: 1f
                    pinching = true
                    dragging = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                drawPts?.let { p ->
                    val n = p.size
                    if (hypot(e.x - p[n - 2], e.y - p[n - 1]) >= dp(5f) && n < 1200) {
                        p.add(e.x); p.add(e.y)
                    }
                    return
                }
                selArea()?.let { a ->
                    if (pinching && e.pointerCount >= 2) {
                        val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        scaleSelected(d / pinchStartDist)
                        pinchStartDist = d
                    } else if (dragging) {
                        val du = (e.x - areaLastX) / contentW
                        val dv = (e.y - areaLastY) / contentH
                        var i = 0
                        while (i < a.pts.size) {
                            a.pts[i] = (a.pts[i] + du).coerceIn(0f, 1f)
                            a.pts[i + 1] = (a.pts[i + 1] + dv).coerceIn(0f, 1f)
                            i += 2
                        }
                        areaLastX = e.x; areaLastY = e.y
                    }
                    return
                }
                val id = selected ?: return
                val v = specs[id] ?: return
                if (pinching && e.pointerCount >= 2) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    v[2] = (pinchStartScale * d / pinchStartDist).coerceIn(0.4f, 2.5f)
                    layoutParts()
                } else if (dragging) {
                    v[0] = ((e.x - dragDx - contentL) / contentW).coerceIn(0.02f, 0.98f)
                    v[1] = ((e.y - dragDy - contentT) / contentH).coerceIn(0.02f, 0.98f)
                    layoutParts()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 2本指を離した後に残った指で動かすと位置が飛ぶので、ドラッグは終わりにする
                pinching = false
                dragging = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                dragging = false
                val p = drawPts
                val key = drawingKey
                if (p != null && key != null) {
                    drawPts = null
                    // 描き終わり: 小さすぎなければエリアにする
                    var a2 = 0f
                    var j = p.size - 2
                    var i = 0
                    while (i < p.size) {
                        a2 += p[j] * p[i + 1] - p[i] * p[j + 1]
                        j = i; i += 2
                    }
                    if (p.size < 8 || kotlin.math.abs(a2) / 2 < dp(30f) * dp(30f)) {
                        Toast.makeText(context, "小さすぎます。もう少し大きく囲んでください", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val pts = FloatArray(p.size)
                    i = 0
                    while (i < p.size) {
                        pts[i] = ((p[i] - contentL) / contentW).coerceIn(0f, 1f)
                        pts[i + 1] = ((p[i + 1] - contentT) / contentH).coerceIn(0f, 1f)
                        i += 2
                    }
                    areas.add(Area(key, pts))
                    selected = "AREA:${areas.size - 1}"
                    drawingKey = null
                    Haptics.click()
                    Toast.makeText(context, "「$key」のエリアを作りました", Toast.LENGTH_SHORT).show()
                }
                saveLayout()
            }
        }
    }

    // ---------------------------------------------------------------- 描画

    private val strokeColor = 0xFF8A7B6C.toInt()
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = strokeColor
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val pTurbo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = C.TURBO
    }
    private val pDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = C.SUB
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        pText.textSize = size
        pText.color = color
        c.drawText(s, x, y - (pText.descent() + pText.ascent()) / 2, pText)
    }

    private fun isAuto(b: Btn) = b.bit != 0 && auto and b.bit != 0

    private fun shapeOf(id: String): Int = specs[id]?.getOrNull(3)?.toInt() ?: 0
    private val tmpRect = RectF()

    /** 部品の形で塗る・縁取る (round=標準が丸い部品か) */
    private fun drawShape(c: Canvas, id: String, r: RectF, round: Boolean, p: Paint) {
        when (shapeOf(id)) {
            1 -> c.drawOval(r, p)
            2 -> {
                val rr = minOf(r.width(), r.height()) * 0.28f
                c.drawRoundRect(r, rr, rr, p)
            }

            3 -> c.drawRoundRect(r, dp(3f), dp(3f), p)
            else -> if (round) c.drawOval(r, p) else c.drawRoundRect(r, dp(14f), dp(14f), p)
        }
    }

    private fun drawCircleBtn(c: Canvas, b: Btn, on: Boolean, text: String = b.label, textScale: Float = 0.9f) {
        pStroke.strokeWidth = dp(3f)
        pFill.color = if (on) C.ACCENT else C.SURFACE
        tmpRect.set(b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r)
        drawShape(c, b.id, tmpRect, true, pFill)
        drawShape(c, b.id, tmpRect, true, pStroke)
        if (isAuto(b)) {
            pTurbo.strokeWidth = dp(3f)
            tmpRect.inset(-dp(5f), -dp(5f))
            drawShape(c, b.id, tmpRect, true, pTurbo)
        }
        drawLabel(c, text, b.cx, b.cy, b.r * textScale, if (on) C.ACCENT_INK else C.ACCENT)
    }

    private fun drawRectBtn(c: Canvas, b: Btn, on: Boolean, size: Float) {
        pStroke.strokeWidth = dp(3f)
        pFill.color = if (on) C.ACCENT else C.SURFACE
        drawShape(c, b.id, b.rect, false, pFill)
        drawShape(c, b.id, b.rect, false, pStroke)
        if (isAuto(b)) {
            pTurbo.strokeWidth = dp(3f)
            val r2 = RectF(b.rect).apply { inset(dp(5f), dp(5f)) }
            drawShape(c, b.id, r2, false, pTurbo)
        }
        val sz = minOf(size, b.rect.height() * 0.42f)
        drawLabel(c, b.label, b.rect.centerX(), b.rect.centerY(), sz, if (on) C.ACCENT_INK else C.ACCENT)
    }

    private fun drawStick(c: Canvas, s: Stick, pressedBit: Boolean) {
        pStroke.strokeWidth = dp(3f)
        val idle = floatingSticks && !s.active && !editing
        pFill.color = C.SURFACE
        pFill.alpha = if (idle) 110 else 255
        pStroke.alpha = if (idle) 110 else 255
        c.drawCircle(s.cx, s.cy, s.r, pFill)
        c.drawCircle(s.cx, s.cy, s.r, pStroke)
        val kr = s.r * 0.45f
        pFill.color = if (pressedBit) C.ACCENT else C.SURFACE2
        pFill.alpha = if (idle) 140 else 255
        c.drawCircle(s.cx + s.kx, s.cy + s.ky, kr, pFill)
        pStroke.color = C.ACCENT
        c.drawCircle(s.cx + s.kx, s.cy + s.ky, kr, pStroke)
        pStroke.color = strokeColor
        pStroke.alpha = 255
        pFill.alpha = 255
        drawLabel(c, s.label, s.cx + s.kx, s.cy + s.ky, kr * 0.7f, if (pressedBit) C.ACCENT_INK else C.ACCENT)
    }

    private val pArea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaPath = android.graphics.Path()

    private fun drawAreas(c: Canvas) {
        for ((i, a) in areas.withIndex()) {
            val on = owners.values.any { it is Owner.A && it.area === a }
            areaPath.reset()
            var k = 0
            while (k < a.pts.size) {
                if (k == 0) areaPath.moveTo(ax(a.pts[k]), ay(a.pts[k + 1])) else areaPath.lineTo(ax(a.pts[k]), ay(a.pts[k + 1]))
                k += 2
            }
            areaPath.close()
            pArea.style = Paint.Style.FILL
            pArea.color = C.ACCENT
            pArea.alpha = if (on) 140 else 32
            c.drawPath(areaPath, pArea)
            val sel = editing && selected == "AREA:$i"
            pArea.style = Paint.Style.STROKE
            pArea.alpha = 255
            pArea.color = if (sel) C.INK else C.ACCENT
            pArea.strokeWidth = dp(if (sel) 3f else 2f)
            c.drawPath(areaPath, pArea)
            val (cu, cv) = areaCenter(a)
            drawLabel(c, a.key, ax(cu), ay(cv), minOf(contentH * 0.09f, dp(30f)), if (on) C.ACCENT_INK else C.ACCENT)
        }
        drawPts?.let { p ->
            if (p.size >= 4) {
                areaPath.reset()
                areaPath.moveTo(p[0], p[1])
                var k = 2
                while (k < p.size) {
                    areaPath.lineTo(p[k], p[k + 1]); k += 2
                }
                pArea.style = Paint.Style.STROKE
                pArea.color = C.INK
                pArea.strokeWidth = dp(3f)
                c.drawPath(areaPath, pArea)
            }
        }
    }

    /** 隠した部品は描かない (配置の編集中だけ薄く描く) */
    private inline fun part(c: Canvas, id: String, block: () -> Unit) {
        if (!hiddenId(id)) {
            block(); return
        }
        if (!editing) return
        val save = c.saveLayerAlpha(null, 70)
        block()
        c.restoreToCount(save)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val h = contentH

        drawAreas(c)

        part(c, "ZL") { drawRectBtn(c, bZL, pressed(bZL), h * 0.07f) }
        part(c, "L") { drawRectBtn(c, bL, pressed(bL), h * 0.07f) }
        part(c, "ZR") { drawRectBtn(c, bZR, pressed(bZR), h * 0.07f) }
        part(c, "R") { drawRectBtn(c, bR, pressed(bR), h * 0.07f) }

        part(c, "LS") { drawStick(c, sL, pressed(bL3)) }
        part(c, "RS") { drawStick(c, sR, pressed(bR3)) }

        // 十字キー
        val dirs = listOf(
            Triple(UP, 0f to -1f, "↑"), Triple(DOWN, 0f to 1f, "↓"),
            Triple(LEFT, -1f to 0f, "←"), Triple(RIGHT, 1f to 0f, "→"),
        )
        part(c, "DP") {
            for ((bit, off, lab) in dirs) {
                val tmp = Btn(lab, bit).apply {
                    id = "DP"
                    cx = dcx + off.first * dOff; cy = dcy + off.second * dOff; r = dR
                }
                drawCircleBtn(c, tmp, dpadBits and bit != 0, lab, 1.1f)
            }
        }

        part(c, "FACE") { faces.forEach { drawCircleBtn(c, it, pressed(it)) } }
        part(c, "MINUS") { drawCircleBtn(c, bMinus, pressed(bMinus), "−", 1.1f) }
        part(c, "PLUS") { drawCircleBtn(c, bPlus, pressed(bPlus), "+", 1.1f) }
        part(c, "H") { drawCircleBtn(c, bHome, pressed(bHome)) }
        part(c, "L3") { drawCircleBtn(c, bL3, pressed(bL3), "L3", 0.7f) }
        part(c, "R3") { drawCircleBtn(c, bR3, pressed(bR3), "R3", 0.7f) }

        // T (全体ターボ中はオレンジ)
        part(c, "T") {
            pStroke.strokeWidth = dp(3f)
            pFill.color = if (globalTurbo) C.TURBO else if (pressed(bTurbo)) C.ACCENT else C.SURFACE
            tmpRect.set(bTurbo.cx - bTurbo.r, bTurbo.cy - bTurbo.r, bTurbo.cx + bTurbo.r, bTurbo.cy + bTurbo.r)
            drawShape(c, "T", tmpRect, true, pFill)
            drawShape(c, "T", tmpRect, true, pStroke)
            drawLabel(c, "T", bTurbo.cx, bTurbo.cy, bTurbo.r * 0.9f, if (globalTurbo) C.ACCENT_INK else C.ACCENT)
        }

        part(c, "GEAR") { drawCircleBtn(c, bGear, pressed(bGear), "⚙", 1.0f) }
        drawCircleBtn(c, bEdit, pressed(bEdit), "✎", 1.0f)
        drawRectBtn(c, bSwitch, pressed(bSwitch), h * 0.09f)

        if (editing) {
            drawEditOverlay(c)
            return
        }

        // 状態表示
        val info = buildString {
            when (Link.state) {
                Link.State.CONNECTED -> append("● ${Link.pcName}")
                Link.State.CONNECTING -> append("接続中…")
                Link.State.DISCONNECTED -> append("未接続")
            }
            if (globalTurbo) append("　ターボON")
            if (auto != 0) append("　連打中")
        }
        val infoColor = if (Link.state == Link.State.CONNECTED) C.OK else C.WARN
        drawLabel(c, info, width / 2f, contentT + h * 0.50f, h * 0.035f, infoColor)
        error?.let {
            drawLabel(c, it, width / 2f, contentT + h * 0.71f, h * 0.03f, C.WARN)
        }
    }

    private fun drawEditOverlay(c: Canvas) {
        c.drawColor(0x66000000)
        pDash.strokeWidth = dp(2f)
        for ((id, r) in editBounds) {
            val sel = id == selected
            pDash.color = if (sel) C.ACCENT else C.SUB
            pDash.strokeWidth = if (sel) dp(3f) else dp(1.5f)
            c.drawRoundRect(r, dp(8f), dp(8f), pDash)
            if (sel) {
                val name = (NAMES[id] ?: id) + if (hiddenId(id)) "（隠し中）" else ""
                drawLabel(c, name, r.centerX(), r.top - dp(12f), dp(14f), C.ACCENT)
            }
        }
        layoutToolbar()
        val hideLabel = when {
            selArea() != null -> "消す"
            selected != null && hiddenId(selected!!) -> "表示"
            else -> "隠す"
        }
        val labels = listOf(
            tbSmaller to "－ 小さく", tbBigger to "＋ 大きく", tbShape to "形", tbHide to hideLabel,
            tbArea to "エリア", tbPreset to "プリセット", tbReset to "初期配置", tbDone to "完了",
        )
        for ((r, lab) in labels) {
            val enabled = r === tbReset || r === tbDone || r === tbPreset || r === tbArea || selected != null
            pFill.color = if (r === tbDone) C.ACCENT else C.SURFACE2
            pFill.alpha = if (enabled) 255 else 120
            c.drawRoundRect(r, dp(10f), dp(10f), pFill)
            pFill.alpha = 255
            drawLabel(c, lab, r.centerX(), r.centerY(), minOf(r.height() * 0.34f, r.width() * 0.17f), if (r === tbDone) C.ACCENT_INK else C.INK)
        }
        val tip = when {
            drawingKey != null -> "「$drawingKey」にしたい場所を、指でなぞって囲んでください"
            selected == null -> "動かしたい部品やエリアに触れてください。「エリア」で自由な形のボタンを描けます"
            else -> "ドラッグで移動・2本指で大きさ変更・「形」「隠す」も使えます"
        }
        drawLabel(c, tip, width / 2f, tbDone.bottom + dp(18f), dp(13f), C.INK)
    }
}
