package com.phonepad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

/**
 * PCで入力中の欄の内容を表示する。触れた位置にPCのカーソルを移動できる。
 * 読み取れない欄のときは、送った文字の履歴を表示する。
 */
@SuppressLint("ViewConstructor")
class PcFieldView(ctx: Context) : LinearLayout(ctx) {
    private val title = label(ctx, "", 11f, C.SUB)
    private val scroll = object : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (fill) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            // 高さは最大で数行分 (キーボード表示中は少なめ)
            val maxH = context.dpi(if (compact) 66 else 112)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }
    private val text = CaretTextView(ctx)

    /** キーボード表示中は小さくする */
    var compact = false
        set(v) {
            field = v; scroll.requestLayout()
        }

    private var fill = false

    /** 決まった大きさの枠いっぱいに表示する (キーボード画面に自由に置く「PCの入力欄」) */
    fun fillParent() {
        fill = true
        scroll.layoutParams = LayoutParams(-1, 0, 1f)
    }

    private var lastJson = ""
    private var pcText = ""
    private var dispToPc = IntArray(1)
    private var pcToDisp = IntArray(1)
    private var echo = ""
    private var showingField = false
    private var userScrolledAt = 0L

    init {
        orientation = VERTICAL
        title.setPadding(dpi(4), 0, dpi(4), dpi(2))
        addView(title)
        text.setTextColor(C.INK)
        text.textSize = 13f
        text.setPadding(dpi(8), dpi(6), dpi(8), dpi(6))
        scroll.background = rounded(0x22000000, dp(8f))
        scroll.addView(text)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> userScrolledAt = System.currentTimeMillis() }
        addView(scroll, LayoutParams(-1, -2))
        text.onTapOffset = { off -> onTap(off) }
        showEcho()
    }

    /** 送った文字 (読み取れない欄のときに表示) */
    fun setEcho(s: String) {
        echo = s
        if (!showingField) showEcho()
    }

    private fun showEcho() {
        title.text = "PCへ送信した文字"
        text.caret = -1
        text.selEnd = -1
        text.text = echo.ifEmpty { "（ここに表示されます）" }
        text.setTextColor(if (echo.isEmpty()) C.SUB else C.INK)
    }

    fun update(json: String) {
        if (json == lastJson) return
        lastJson = json
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return
        }
        if (!o.optBoolean("ok")) {
            showingField = false
            showEcho()
            o.optString("reason").takeIf { it.isNotEmpty() }?.let { title.text = "PCへ送信した文字（$it）" }
            return
        }
        showingField = true
        pcText = o.optString("text")
        buildMaps()
        val caret = o.optInt("caret", -1)
        val selEnd = o.optInt("selEnd", -1)
        title.text = if (caret >= 0) "PCの入力欄（触れた位置にカーソルを移動）" else "PCの入力欄"
        render(caret, selEnd)
    }

    /** PCの文字列は改行が CRLF なので、表示用(LF)との位置の対応表を作る */
    private fun buildMaps() {
        val n = pcText.length
        val disp = StringBuilder(n)
        pcToDisp = IntArray(n + 1)
        val d2p = ArrayList<Int>(n + 1)
        var i = 0
        while (i < n) {
            pcToDisp[i] = disp.length
            val c = pcText[i]
            if (c == '\r' && i + 1 < n && pcText[i + 1] == '\n') {
                // CRLF は表示上1文字の改行にまとめる
                d2p.add(i)
                disp.append('\n')
                pcToDisp[i + 1] = disp.length - 1
                i += 2
                continue
            }
            d2p.add(i)
            disp.append(if (c == '\r') '\n' else c)
            i++
        }
        pcToDisp[n] = disp.length
        d2p.add(n)
        dispToPc = d2p.toIntArray()
        displayText = disp.toString()
    }

    private var displayText = ""

    private fun render(caret: Int, selEnd: Int) {
        val dc = if (caret in 0..pcText.length) pcToDisp[caret] else -1
        val de = if (selEnd in 0..pcText.length) pcToDisp[selEnd] else -1
        val sp = SpannableString(displayText.ifEmpty { " " })
        if (dc >= 0 && de > dc && de <= displayText.length) {
            sp.setSpan(BackgroundColorSpan(0x66E0A35A), dc, de, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.setTextColor(C.INK)
        text.text = sp
        text.caret = if (de > dc) -1 else dc
        text.selEnd = de
        // カーソルが見える位置までスクロール (自分でスクロールした直後は動かさない)
        if (dc >= 0 && System.currentTimeMillis() - userScrolledAt > 1500) {
            text.post {
                val l = text.layout ?: return@post
                val line = l.getLineForOffset(dc.coerceAtMost(text.length()))
                val y = l.getLineTop(line) + text.paddingTop
                val h = scroll.height
                if (y < scroll.scrollY || y > scroll.scrollY + h - dpi(20)) {
                    scroll.scrollTo(0, (y - h / 3).coerceAtLeast(0))
                }
            }
        }
    }

    private fun onTap(dispOffset: Int) {
        if (!showingField) return
        val pc = dispToPc.getOrElse(dispOffset.coerceIn(0, dispToPc.size - 1)) { pcText.length }
        Link.send("CS $pc")
        Haptics.tick()
        // すぐ見た目に反映 (PCからの返事で正しい位置に直る)
        lastJson = ""
        render(pc, pc)
    }
}

/** カーソル (縦線) を描ける TextView */
class CaretTextView(ctx: Context) : TextView(ctx) {
    var caret = -1
        set(v) {
            field = v; invalidate()
        }
    var selEnd = -1
    var onTapOffset: ((Int) -> Unit)? = null
    private val caretPaint = Paint().apply { color = C.ACCENT }
    private var downX = 0f
    private var downY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (caret < 0 || caret > text.length) return
        val line = l.getLineForOffset(caret)
        val x = l.getPrimaryHorizontal(caret) + totalPaddingLeft
        val top = l.getLineTop(line) + totalPaddingTop
        val bottom = l.getLineBottom(line) + totalPaddingTop
        canvas.drawRect(x - dp(1f), top.toFloat(), x + dp(1f), bottom.toFloat(), caretPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
            }

            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) < dp(10f) && abs(e.y - downY) < dp(10f)) {
                    onTapOffset?.invoke(getOffsetForPosition(e.x, e.y))
                }
            }
        }
        return true
    }
}
