package com.phonepad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 部品を自由な位置・大きさに並べる面 (キーボード画面の配置用)。
 * 位置は面に対する割合 [x, y, 幅, 高さ, 隠す(0/1)]。
 * editing=true のときは、部品に触れても反応せず、選んで動かす・右下の丸で大きさを変える。
 */
@SuppressLint("ViewConstructor")
class FreeStage(ctx: Context) : FrameLayout(ctx) {
    class Part(val id: String, val view: View, val name: String, val fixed: Boolean = false, val customId: String? = null)

    val parts = LinkedHashMap<String, Part>()
    var rects = HashMap<String, FloatArray>()

    var editing = false
        set(v) {
            field = v
            sel = null
            drag = 0
            applyVisibility()
            onSelect?.invoke(null)
            invalidate()
        }
    var sel: String? = null
        private set
    var onSelect: ((Part?) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private var drag = 0 // 0=なし 1=移動 2=大きさ
    private var sx = 0f
    private var sy = 0f
    private var start = FloatArray(5)

    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0xB3A99D91.toInt()
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = C.ACCENT
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
    }

    fun clear() {
        removeAllViews()
        parts.clear()
        sel = null
        drag = 0
    }

    fun add(p: Part) {
        parts[p.id] = p
        (p.view.parent as? android.view.ViewGroup)?.removeView(p.view)
        addView(p.view)
    }

    fun remove(id: String) {
        parts.remove(id)?.let { removeView(it.view) }
        rects.remove(id)
        if (sel == id) {
            sel = null
            onSelect?.invoke(null)
        }
        invalidate()
    }

    fun isShown(id: String): Boolean {
        val r = rects[id] ?: return false
        return parts.containsKey(id) && r[4] == 0f
    }

    fun toggleHidden(id: String) {
        val r = rects[id] ?: return
        r[4] = if (r[4] != 0f) 0f else 1f
        applyVisibility()
        onSelect?.invoke(parts[id])
        onChanged?.invoke()
    }

    fun select(id: String?) {
        sel = id
        onSelect?.invoke(id?.let { parts[it] })
        invalidate()
    }

    /** 隠した部品は、配置を変えているときだけ薄く出す */
    fun applyVisibility() {
        for ((id, p) in parts) {
            val r = rects[id]
            val off = r == null || r[4] != 0f
            p.view.visibility = if (r == null || (off && !editing)) View.GONE else View.VISIBLE
            p.view.alpha = if (off && editing) 0.35f else 1f
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for ((id, p) in parts) {
            val r = rects[id] ?: continue
            if (p.view.visibility == View.GONE) continue
            p.view.measure(
                MeasureSpec.makeMeasureSpec((r[2] * w).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((r[3] * h).roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        for ((id, p) in parts) {
            val r = rects[id] ?: continue
            if (p.view.visibility == View.GONE) continue
            val l = (r[0] * w).roundToInt()
            val t = (r[1] * h).roundToInt()
            p.view.layout(l, t, l + p.view.measuredWidth, t + p.view.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!editing) return
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = dp(10f)
        for ((id, _) in parts) {
            val r = rects[id] ?: continue
            canvas.drawRoundRect(r[0] * w, r[1] * h, (r[0] + r[2]) * w, (r[1] + r[3]) * h, rad, rad, if (id == sel) selPaint else dash)
        }
        val r = sel?.let { rects[it] } ?: return
        // 右下の丸 (大きさを変える)
        val (hx, hy) = handlePos(r)
        fill.color = C.BG
        canvas.drawCircle(hx, hy, dp(13f), fill)
        fill.color = C.ACCENT
        canvas.drawCircle(hx, hy, dp(10f), fill)
    }

    private fun handlePos(r: FloatArray): Pair<Float, Float> {
        val m = dp(14f)
        return Pair(minOf((r[0] + r[2]) * width, width - m), minOf((r[1] + r[3]) * height, height - m))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = editing || super.onInterceptTouchEvent(ev)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editing) return super.onTouchEvent(e)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val x = e.x / w
        val y = e.y / h
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cur = sel?.let { rects[it] }
                if (cur != null && handlePos(cur).let { (hx, hy) -> hypot(e.x - hx, e.y - hy) < dp(28f) }) {
                    drag = 2
                } else {
                    // 触れた場所にある部品のうち、いちばん小さいもの (大きなマウスの上のボタンも選べるように)
                    var best: String? = null
                    var area = Float.MAX_VALUE
                    for ((id, _) in parts) {
                        val r = rects[id] ?: continue
                        if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3] && r[2] * r[3] < area) {
                            best = id; area = r[2] * r[3]
                        }
                    }
                    select(best)
                    drag = if (best != null) 1 else 0
                }
                sel?.let { rects[it] }?.copyInto(start)
                sx = x; sy = y
            }

            MotionEvent.ACTION_MOVE -> {
                val id = sel ?: return true
                val r = rects[id] ?: return true
                if (drag == 1) {
                    r[0] = snap(start[0] + x - sx).coerceIn(0f, 1f - start[2])
                    r[1] = snap(start[1] + y - sy).coerceIn(0f, 1f - start[3])
                } else if (drag == 2) {
                    r[2] = snap(start[2] + x - sx).coerceIn(dp(30f) / w, 1f - start[0])
                    r[3] = snap(start[3] + y - sy).coerceIn(dp(26f) / h, 1f - start[1])
                }
                requestLayout()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != 0) onChanged?.invoke()
                drag = 0
            }
        }
        return true
    }

    private fun snap(v: Float) = (v / 0.005f).roundToInt() * 0.005f

    fun toJson(): String {
        val o = JSONObject()
        for ((id, r) in rects) {
            val a = org.json.JSONArray()
            for (i in 0 until 4) a.put(((r[i] * 1000).roundToInt() / 1000.0))
            a.put(if (r[4] != 0f) 1 else 0)
            o.put(id, a)
        }
        return o.toString()
    }

    companion object {
        /** 保存した配置を defaults に上書きする (知らない部品は無視) */
        fun merge(defaults: HashMap<String, FloatArray>, json: String): HashMap<String, FloatArray> {
            if (json.isEmpty()) return defaults
            try {
                val o = JSONObject(json)
                for (id in o.keys()) {
                    if (!defaults.containsKey(id)) continue
                    val a = o.optJSONArray(id) ?: continue
                    if (a.length() < 4) continue
                    val r = FloatArray(5) { i -> if (i < a.length()) a.optDouble(i, 0.0).toFloat() else 0f }
                    if (r.take(4).any { it.isNaN() || it.isInfinite() }) continue
                    defaults[id] = r
                }
            } catch (_: Exception) {
            }
            return defaults
        }
    }
}
