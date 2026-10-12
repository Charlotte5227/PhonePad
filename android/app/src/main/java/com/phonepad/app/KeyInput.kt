package com.phonepad.app

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/** 修飾キー (Ctrl/Shift/Alt/Win) の状態。タップで1回だけ有効、長押しで固定。 */
class Modifiers(private val onChange: () -> Unit) {
    companion object {
        const val CTRL = 1
        const val SHIFT = 2
        const val ALT = 4
        const val WIN = 8
    }

    var oneShot = 0
        private set
    var locked = 0
        private set
    val mask get() = oneShot or locked

    fun tap(bit: Int) {
        when {
            locked and bit != 0 -> locked = locked and bit.inv()
            oneShot and bit != 0 -> oneShot = oneShot and bit.inv()
            else -> oneShot = oneShot or bit
        }
        onChange()
    }

    fun lock(bit: Int) {
        locked = locked or bit
        oneShot = oneShot and bit.inv()
        onChange()
    }

    /** 今の修飾キーを返し、1回だけのものは解除する */
    fun consume(): Int {
        val m = mask
        if (oneShot != 0) {
            oneShot = 0
            onChange()
        }
        return m
    }

    /** Ctrl/Alt/Win のどれかが有効 (文字を「ショートカット」として送る必要がある) */
    fun hasCommand() = mask and (CTRL or ALT or WIN) != 0

    fun state(bit: Int) = when {
        locked and bit != 0 -> 2
        oneShot and bit != 0 -> 1
        else -> 0
    }
}

/**
 * キーボード(IME)が「確定」した文字をそのままPCへ送る。
 * 入力欄の中身を比べる方式だと、漢字変換中のカーソル移動で順番が崩れるため、
 * IMEから届く確定・削除・Enterの操作を直接受け取って送る。
 */
class ImeSender(
    private val mods: Modifiers,
    /** 送った内容の表示用 (text=null は1文字削除、"\n" はEnter) */
    private val echo: (String?) -> Unit,
) {
    fun commit(s: String) {
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                Link.send("TX " + Link.b64(buf.toString()))
                echo(buf.toString())
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code -> {
                    flush(); enter()
                }

                cp == '\r'.code -> {}

                mods.hasCommand() -> {
                    // Ctrl+C などのショートカット
                    flush()
                    Link.send("KY C%x %d".format(cp, mods.consume()))
                }

                else -> {
                    buf.appendCodePoint(cp)
                    if (mods.oneShot != 0) mods.consume()
                }
            }
        }
        flush()
    }

    fun backspace(n: Int) {
        repeat(n) {
            Link.send("KY BACK ${mods.consume()}")
            echo(null)
        }
    }

    /** Enter単体 → Enter、Shiftを押してから → Shift+Enter */
    fun enter() {
        Link.send("KY ENTER ${mods.consume()}")
        echo("\n")
    }
}

/**
 * 入力欄。変換中の文字だけを表示し、確定した文字はPCへ送ってから欄から消す。
 */
@SuppressLint("ViewConstructor")
class KeyInputView(ctx: Context) : EditText(ctx) {
    var sender: ImeSender? = null
    private var cleanupPosted = false

    fun applyMode(latinOnly: Boolean) {
        // 予測変換をオフにすると、英字が1文字ずつすぐ確定して送られる
        inputType = if (latinOnly) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        maxLines = 3
        isSingleLine = false
    }

    private fun composingText(): String {
        val t = text ?: return ""
        val s = BaseInputConnection.getComposingSpanStart(t)
        val e = BaseInputConnection.getComposingSpanEnd(t)
        if (s < 0 || e < 0) return ""
        val a = minOf(s, e).coerceIn(0, t.length)
        val b = maxOf(s, e).coerceIn(0, t.length)
        return t.subSequence(a, b).toString()
    }

    private fun hasComposing(): Boolean {
        val t = text ?: return false
        return BaseInputConnection.getComposingSpanStart(t) >= 0
    }

    /** カーソルの前にある (変換中でない) 文字 */
    private fun textBeforeCursor(): String {
        val t = text ?: return ""
        val end = selectionStart.coerceIn(0, t.length)
        return t.subSequence(0, end).toString()
    }

    /** 確定済みの文字が欄に残っていたら消す (送信済みなので) */
    private fun scheduleCleanup() {
        if (cleanupPosted) return
        cleanupPosted = true
        post {
            cleanupPosted = false
            if (!hasComposing() && !text.isNullOrEmpty()) text?.clear()
        }
    }

    /** ×ボタン: 欄を空にする (PCには何も送らない) */
    fun clearLocal() {
        text?.clear()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : InputConnectionWrapper(ic, true) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val s = text?.toString() ?: ""
                val r = super.commitText(text, newCursorPosition)
                if (s.isNotEmpty()) sender?.commit(s)
                scheduleCleanup()
                return r
            }

            override fun finishComposingText(): Boolean {
                val c = composingText()
                val r = super.finishComposingText()
                if (c.isNotEmpty()) sender?.commit(c)
                scheduleCleanup()
                return r
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && !hasComposing()) {
                    val local = textBeforeCursor()
                    val n = if (local.isEmpty()) {
                        beforeLength.coerceAtMost(50)
                    } else {
                        val from = (local.length - beforeLength).coerceAtLeast(0)
                        local.codePointCount(from, local.length)
                    }
                    sender?.backspace(n)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0 && !hasComposing()) sender?.backspace(beforeLength.coerceAtMost(50))
                return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> if (!hasComposing()) {
                        if (event.action == KeyEvent.ACTION_DOWN) sender?.backspace(1)
                        return true
                    }

                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.action == KeyEvent.ACTION_DOWN) sender?.enter()
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                sender?.enter()
                return true
            }
        }
    }

    // 物理キーボードをつないだ場合
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                sender?.backspace(1); return true
            }

            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                sender?.enter(); return true
            }
        }
        val ch = event.unicodeChar
        if (ch != 0) {
            sender?.commit(String(Character.toChars(ch)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
