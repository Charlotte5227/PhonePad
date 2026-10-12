package com.phonepad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/** 色 (イメージ図の茶色を基調にした暗めの配色) */
object C {
    const val BG = 0xFF1B1815.toInt()
    const val SURFACE = 0xFF26221E.toInt()
    const val SURFACE2 = 0xFF332D27.toInt()
    const val LINE = 0xFF4E453C.toInt()
    const val INK = 0xFFF1EBE4.toInt()
    const val SUB = 0xFFA99D91.toInt()
    const val ACCENT = 0xFFE0A35A.toInt()
    const val ACCENT_INK = 0xFF2A1A08.toInt()
    const val OK = 0xFF6CC28F.toInt()
    const val WARN = 0xFFF0925A.toInt()
    const val TURBO = 0xFFFF6B3D.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Int): Int = context.dpi(v)

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, stroke)
}

/** 振動 */
object Haptics {
    private var vib: Vibrator? = null

    fun init(ctx: Context) {
        // VibratorManager は Android 12 から。Android 11 では Vibrator を直接使う
        vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var rumbling = 0

    /** ゲームの振動 (0なら止める) */
    fun rumble(large: Int, small: Int) {
        val v = vib ?: return
        val a = maxOf(large, small).coerceIn(0, 255)
        if (a == rumbling) return
        rumbling = a
        try {
            if (a == 0) {
                v.cancel()
            } else {
                val amp = if (v.hasAmplitudeControl()) a.coerceAtLeast(1) else VibrationEffect.DEFAULT_AMPLITUDE
                v.vibrate(VibrationEffect.createOneShot(10_000, amp))
            }
        } catch (_: Exception) {
        }
    }

    fun tick() {
        if (!Prefs.vibrate || rumbling > 0) return
        try {
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } catch (_: Exception) {
        }
    }

    fun click() {
        if (!Prefs.vibrate) return
        try {
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (_: Exception) {
        }
    }

    fun heavy() {
        if (!Prefs.vibrate) return
        try {
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        } catch (_: Exception) {
        }
    }

    fun double() {
        if (!Prefs.vibrate) return
        try {
            vib?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        } catch (_: Exception) {
        }
    }
}

/**
 * 角丸のキー。押している間だけ色が変わる。
 * onDown/onUp を使うと押した瞬間・離した瞬間に反応する (マウスボタン用)。
 * repeat=true にすると押しっぱなしで連続入力 (矢印キーなど)。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyButton(
    ctx: Context,
    label: String,
    private val textSp: Float = 15f,
) : TextView(ctx) {
    var onTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onUp: (() -> Unit)? = null
    var repeat = false

    /** 0=通常 1=一時的にON 2=固定ON */
    var active = 0
        set(v) {
            field = v; refresh()
        }

    private var pressed = false
    private var longFired = false
    private val h = Handler(Looper.getMainLooper())
    private val repeatRun = object : Runnable {
        override fun run() {
            onTap?.invoke()
            h.postDelayed(this, (1000 / Prefs.keyRepeatRate.coerceIn(2, 60)).toLong())
        }
    }
    private val longRun = Runnable {
        longFired = true
        if (repeat) {
            h.post(repeatRun)
        } else if (onLongPress != null) {
            Haptics.heavy()
            onLongPress?.invoke()
        }
    }

    init {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        setTextColor(C.INK)
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        refresh()
    }

    private fun refresh() {
        val (bg, fg, stroke) = when {
            pressed -> Triple(C.ACCENT, C.ACCENT_INK, C.ACCENT)
            active == 2 -> Triple(C.ACCENT, C.ACCENT_INK, C.INK)
            active == 1 -> Triple(0x55E0A35A, C.INK, C.ACCENT)
            else -> Triple(C.SURFACE2, C.INK, C.LINE)
        }
        background = rounded(bg, dp(10f), stroke, dpi(1))
        setTextColor(fg)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                longFired = false
                refresh()
                Haptics.tick()
                onDown?.invoke()
                if (repeat || onLongPress != null) h.postDelayed(longRun, if (repeat) Prefs.keyRepeatDelay.toLong() else 450)
            }

            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longRun)
                h.removeCallbacks(repeatRun)
                val wasPressed = pressed
                pressed = false
                refresh()
                onUp?.invoke()
                if (wasPressed && !longFired) onTap?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longRun)
                h.removeCallbacks(repeatRun)
                pressed = false
                refresh()
                onUp?.invoke()
            }
        }
        return true
    }
}

fun label(ctx: Context, text: String, sp: Float = 14f, color: Int = C.INK, bold: Boolean = false) =
    TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
