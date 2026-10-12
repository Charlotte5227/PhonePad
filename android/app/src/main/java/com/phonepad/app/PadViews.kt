package com.phonepad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** 下の「長押し」ボタン (中ボタン) の状態。押している間の操作はスクロールになる。 */
class MiddleState {
    var held = false
    var used = false
    var onChange: (() -> Unit)? = null

    fun press() {
        held = true; used = false; onChange?.invoke()
    }

    /** 離したとき。何も動かさなければ中クリック */
    fun release() {
        if (held && !used) Link.send("MC M")
        held = false
        onChange?.invoke()
    }
}

/** 小数点以下を貯めて整数で送る */
private class Accum {
    var x = 0f
    var y = 0f
    fun add(dx: Float, dy: Float): Pair<Int, Int> {
        x += dx; y += dy
        val ix = x.toInt()
        val iy = y.toInt()
        x -= ix; y -= iy
        return Pair(ix, iy)
    }

    fun reset() {
        x = 0f; y = 0f
    }
}

/** ホイール量 (120で1段) を一定量ごとにまとめて送る */
class WheelAccum {
    var v = 0f
    var h = 0f
    fun add(dv: Float, dh: Float) {
        v += dv; h += dh
        val sv = if (abs(v) >= 15f) v.toInt() else 0
        val sh = if (abs(h) >= 15f) h.toInt() else 0
        if (sv != 0 || sh != 0) {
            Link.send("MW $sv $sh")
            v -= sv; h -= sh
        }
    }

    fun reset() {
        v = 0f; h = 0f
    }
}

/**
 * トラックパッド (ノートPCのタッチパッドのイメージ)
 * ・1本指で移動 ・左側タップ=左クリック / 右側タップ=右クリック
 * ・2本指でスクロール / 2本指タップ=右クリック
 * ・押したまま少し待つ → その側のボタンを押したままドラッグ
 * ・下の「長押し」ボタンを押しながら動かす → スクロール
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TrackpadView(ctx: Context, private val middle: MiddleState) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val slop = 8 * density

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var moved = false
    private var maxPointers = 0
    private var dragButton: Char? = null
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoLastX = 0f
    private var twoLastY = 0f
    private val acc = Accum()
    private val wheel = WheelAccum()
    private val touches = HashMap<Int, Pair<Float, Float>>()

    private val longPress = Runnable {
        if (!moved && maxPointers == 1 && dragButton == null && !middle.held) {
            dragButton = if (downX < width / 2f) 'L' else 'R'
            Link.send("MB $dragButton 1")
            Haptics.heavy()
            invalidate()
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SURFACE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.LINE; strokeWidth = dp(1.5f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.SUB; textSize = dp(13f); textAlign = Paint.Align.CENTER
    }
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40F1EBE4; textSize = dp(22f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55E0A35A }
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, dp(16f), dp(16f), bgPaint)
        val cx = width / 2f
        c.drawLine(cx, height * 0.62f, cx, height - dp(12f), linePaint)
        c.drawText("左クリック", width * 0.25f, height - dp(16f), textPaint)
        c.drawText("右クリック", width * 0.75f, height - dp(16f), textPaint)
        val center = when {
            dragButton == 'L' -> "左ボタンでドラッグ中"
            dragButton == 'R' -> "右ボタンでドラッグ中"
            middle.held -> "スクロール"
            else -> "マウス"
        }
        c.drawText(center, cx, height * 0.45f, bigText)
        for ((_, p) in touches) c.drawCircle(p.first, p.second, dp(28f), dotPaint)
    }

    private fun moveCursor(dx: Float, dy: Float, dtMs: Long) {
        val distDp = hypot(dx, dy) / density
        val v = distDp / maxOf(dtMs, 4L).toFloat() // dp/ms
        val gain = Prefs.mouseSpeed * (1f + Prefs.mouseAccel * min(v, 3f)) * 1.6f
        val (ix, iy) = acc.add(dx / density * gain, dy / density * gain)
        if (ix != 0 || iy != 0) Link.send("MM $ix $iy")
    }

    /** 2本指スクロール: スマホと同じ向き (指を上げるとページが下へ) */
    private fun scrollNatural(dx: Float, dy: Float) {
        val k = 6f * Prefs.scrollSpeed / density
        val sign = if (Prefs.invertTwoFinger) -1f else 1f
        wheel.add(dy * k * sign, -dx * k * sign)
    }

    /** 長押しボタン+移動: ThinkPadと同じ (下へ動かすとページが下へ) */
    private fun scrollPointer(dx: Float, dy: Float) {
        val k = 6f * Prefs.scrollSpeed / density
        wheel.add(-dy * k, dx * k)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime; lastT = e.eventTime
                moved = false; maxPointers = 1; dragButton = null
                acc.reset(); wheel.reset()
                postDelayed(longPress, 450)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                if (e.pointerCount == 2) {
                    twoStartX = (e.getX(0) + e.getX(1)) / 2; twoStartY = (e.getY(0) + e.getY(1)) / 2
                    twoLastX = twoStartX; twoLastY = twoStartY
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2 && dragButton == null) {
                    val cx = (e.getX(0) + e.getX(1)) / 2
                    val cy = (e.getY(0) + e.getY(1)) / 2
                    if (!moved && hypot(cx - twoStartX, cy - twoStartY) > slop) moved = true
                    if (moved) scrollNatural(cx - twoLastX, cy - twoLastY)
                    twoLastX = cx; twoLastY = cy
                } else if (e.pointerCount == 1 || dragButton != null) {
                    val x = e.getX(0)
                    val y = e.getY(0)
                    val dx = x - lastX
                    val dy = y - lastY
                    if (!moved && hypot(x - downX, y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved || dragButton != null) {
                        if (middle.held && dragButton == null) {
                            middle.used = true
                            scrollPointer(dx, dy)
                        } else {
                            moveCursor(dx, dy, e.eventTime - lastT)
                        }
                    }
                    lastX = x; lastY = y; lastT = e.eventTime
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 残った指の位置から続ける (カーソルが飛ばないように)
                val keep = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(keep); lastY = e.getY(keep); lastT = e.eventTime
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val drag = dragButton
                if (drag != null) {
                    Link.send("MB $drag 0")
                    dragButton = null
                } else if (!moved && e.eventTime - downTime < 350) {
                    val b = when {
                        maxPointers >= 2 -> 'R'
                        middle.held -> {
                            middle.used = true; 'M'
                        }

                        downX < width / 2f -> 'L'
                        else -> 'R'
                    }
                    Link.send("MC $b")
                    Haptics.tick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                dragButton?.let { Link.send("MB $it 0") }
                dragButton = null
            }
        }
        // 指の位置を描く
        touches.clear()
        if (e.actionMasked != MotionEvent.ACTION_UP && e.actionMasked != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until e.pointerCount) {
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                touches[e.getPointerId(i)] = Pair(e.getX(i), e.getY(i))
            }
        }
        invalidate()
        return true
    }
}

/**
 * スティック操作。倒した方向・量に応じてカーソルが動き続ける。
 * 下の「長押し」ボタンを押している間はスクロールになる。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class StickPadView(ctx: Context, private val middle: MiddleState) : View(ctx) {
    private var active = false
    private var kx = 0f
    private var ky = 0f
    private var lastNanos = 0L
    private val acc = Accum()
    private val wheel = WheelAccum()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SURFACE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.LINE; style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val tri = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.LINE; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeJoin = Paint.Join.ROUND
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.SURFACE2 }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(3f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.ACCENT; textSize = dp(15f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val rect = RectF()
    private val path = Path()

    private val radius get() = min(width, height) * (if (Prefs.mouseStickFloating) 0.22f else 0.30f)

    private val frame = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.nanoTime()
            val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
            lastNanos = now
            step(dt)
            postOnAnimation(this)
        }
    }

    private fun step(dt: Float) {
        val r = radius
        if (r <= 0f) return
        val len = hypot(kx, ky)
        val m = (len / r).coerceIn(0f, 1f)
        val dz = 0.1f
        if (m <= dz) return
        val mm = ((m - dz) / (1 - dz))
        val curve = mm * mm * 0.75f + mm * 0.25f
        val nx = kx / len
        val ny = ky / len
        if (middle.held) {
            middle.used = true
            val u = 1100f * curve * Prefs.scrollSpeed * dt
            wheel.add(-ny * u, nx * u)
        } else {
            val speed = 1500f * Prefs.stickSpeed * curve * dt
            val (ix, iy) = acc.add(nx * speed, ny * speed)
            if (ix != 0 || iy != 0) Link.send("MM $ix $iy")
        }
    }

    /** スティックの中心 (触れた位置に出す場合は指を置いた場所) */
    private var bx = -1f
    private var by = -1f
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C.SUB; textSize = dp(13f); textAlign = Paint.Align.CENTER
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, dp(16f), dp(16f), bgPaint)
        val floating = Prefs.mouseStickFloating
        val r = radius
        val cx = if (active && bx >= 0) bx else width / 2f
        val cy = if (active && by >= 0) by else height / 2f

        if (floating && !active) {
            // 待機中は薄く表示
            ring.alpha = 90
            c.drawCircle(cx, cy, r, ring)
            ring.alpha = 255
            c.drawText("好きな場所に触れるとスティックが出ます", width / 2f, height - dp(18f), hint)
        } else {
            c.drawCircle(cx, cy, r, ring)
        }
        // 方向の飾り (イメージ図の三角)
        val d = r + dp(34f)
        val s = dp(16f)
        fun triangle(x: Float, y: Float, dirX: Float, dirY: Float) {
            path.reset()
            path.moveTo(x + dirX * s, y + dirY * s)
            path.lineTo(x - dirX * s * 0.6f - dirY * s, y - dirY * s * 0.6f + dirX * s)
            path.lineTo(x - dirX * s * 0.6f + dirY * s, y - dirY * s * 0.6f - dirX * s)
            path.close()
            c.drawPath(path, tri)
        }
        if (!floating || active) {
            if (cy - d - s > 0 && cy + d + s < height) {
                triangle(cx, cy - d, 0f, -1f); triangle(cx, cy + d, 0f, 1f)
            }
            if (cx - d - s > 0 && cx + d + s < width) {
                triangle(cx - d, cy, -1f, 0f); triangle(cx + d, cy, 1f, 0f)
            }
        }

        val kr = r * 0.42f
        val px = cx + kx
        val py = cy + ky
        if (floating && !active) knob.alpha = 120 else knob.alpha = 255
        c.drawCircle(px, py, kr, knob)
        if (!floating || active) c.drawCircle(px, py, kr, knobRing)
        c.drawText(if (middle.held) "スクロール" else "スティック", px, py + dp(5f), text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (Prefs.mouseStickFloating) {
                    // 触れた位置をスティックの中心にする
                    bx = e.x; by = e.y
                } else {
                    bx = width / 2f; by = height / 2f
                }
                kx = 0f; ky = 0f
                if (!Prefs.mouseStickFloating) moveKnob(e.x, e.y)
                active = true
                lastNanos = System.nanoTime()
                acc.reset(); wheel.reset()
                Haptics.tick()
                postOnAnimation(frame)
            }

            MotionEvent.ACTION_MOVE -> if (active) moveKnob(e.x, e.y)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = false
                kx = 0f; ky = 0f
                removeCallbacks(frame)
            }
        }
        invalidate()
        return true
    }

    private fun moveKnob(x: Float, y: Float) {
        var dx = x - bx
        var dy = y - by
        val len = hypot(dx, dy)
        val r = radius
        if (len > r) {
            dx = dx / len * r; dy = dy / len * r
        }
        kx = dx; ky = dy
    }
}
