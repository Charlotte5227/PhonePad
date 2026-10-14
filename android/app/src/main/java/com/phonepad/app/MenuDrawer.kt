package com.phonepad.app

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * ≡ のメニュー (左から引き出す)。
 * 機能を押すとそこへ移り、☆ を押すとキーボード画面にその機能のボタンを置く (お気に入り)。
 * 「並べ替え」で並びを変えられる。
 * onFavChanged: お気に入りを変えたとき (キーボード画面を作り直す)
 */
class MenuDrawer(private val act: Activity, private val onFavChanged: (() -> Unit)? = null) {
    private var root: FrameLayout? = null
    private var reorder = false
    private lateinit var list: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var modeBtn: KeyButton

    val isOpen get() = root != null

    fun open() {
        if (root != null) return
        val decor = act.window.decorView as ViewGroup
        val r = FrameLayout(act)
        root = r
        // 暗くした部分を押すと閉じる
        val dim = View(act).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { close() }
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }
        r.addView(dim, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.SURFACE)
            isClickable = true
        }
        // システムバーの分だけ空ける
        panel.setOnApplyWindowInsetsListener { v, ins ->
            val s = ins.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            v.setPadding(s.left + act.dpi(12), s.top + act.dpi(12), act.dpi(12), s.bottom + act.dpi(12))
            ins
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dpi(4), 0, 0, act.dpi(6))
        }
        val title = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label(act, "PhonePad", 18f, C.INK, true))
        val st = when (Link.state) {
            Link.State.CONNECTED -> "● ${Link.pcName}"
            Link.State.CONNECTING -> "PCを探しています…"
            Link.State.DISCONNECTED -> "未接続"
        }
        title.addView(label(act, st, 12f, if (Link.state == Link.State.CONNECTED) C.OK else C.SUB))
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(KeyButton(act, "✕", 16f).apply { onTap = { close() } }, LinearLayout.LayoutParams(act.dpi(44), act.dpi(40)))
        panel.addView(head)
        panel.addView(label(act, "☆ を押すと、キーボード画面にボタンとして置けます", 11f, C.SUB).apply {
            setPadding(act.dpi(4), 0, 0, act.dpi(8))
        })

        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(act).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        modeBtn = KeyButton(act, "並べ替え", 13f).apply {
            onTap = {
                reorder = !reorder
                render()
            }
        }
        val foot = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, act.dpi(8), 0, 0) }
        foot.addView(modeBtn, LinearLayout.LayoutParams(0, act.dpi(42), 1f))
        if (act !is ConnectActivity) {
            foot.addView(KeyButton(act, "接続", 13f).apply {
                onTap = {
                    close()
                    if (Link.state == Link.State.CONNECTED) StatusPanel(act).show() else act.startActivity(android.content.Intent(act, ConnectActivity::class.java))
                }
            }, LinearLayout.LayoutParams(0, act.dpi(42), 1f).apply { leftMargin = act.dpi(6) })
        }
        panel.addView(foot)

        val w = minOf((act.resources.displayMetrics.widthPixels * 0.82f).toInt(), act.dpi(360))
        r.addView(panel, FrameLayout.LayoutParams(w, -1, Gravity.START))
        decor.addView(r, ViewGroup.LayoutParams(-1, -1))
        panel.translationX = -w.toFloat()
        panel.animate().translationX(0f).setDuration(180).start()
        render()
        panel.requestApplyInsets()
    }

    fun close() {
        val r = root ?: return
        root = null
        panel.animate().translationX(-panel.width.toFloat()).setDuration(150).withEndAction {
            (r.parent as? ViewGroup)?.removeView(r)
        }.start()
    }

    private fun render() {
        list.removeAllViews()
        modeBtn.text = if (reorder) "並べ替えを終わる" else "並べ替え"
        modeBtn.active = if (reorder) 2 else 0
        val items = Features.ordered().toMutableList()
        for ((i, f) in items.withIndex()) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(act).apply {
                text = "${f.icon}　${f.label}"
                textSize = 15f
                setTextColor(C.INK)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dpi(10), 0, act.dpi(6), 0)
                background = rounded(0, act.dp(10f))
                if (!reorder) setOnClickListener {
                    close()
                    Features.launch(act, f.id)
                }
            }
            row.addView(name, LinearLayout.LayoutParams(0, act.dpi(48), 1f))
            if (reorder) {
                fun move(d: Int) {
                    val j = i + d
                    if (j < 0 || j >= items.size) return
                    items[i] = items[j].also { items[j] = items[i] }
                    Features.saveOrder(items)
                    render()
                }
                row.addView(KeyButton(act, "↑", 15f).apply { onTap = { move(-1) } }, LinearLayout.LayoutParams(act.dpi(44), act.dpi(40)))
                row.addView(KeyButton(act, "↓", 15f).apply { onTap = { move(1) } }, LinearLayout.LayoutParams(act.dpi(44), act.dpi(40)).apply { leftMargin = act.dpi(4) })
            } else if (f.id != "kb") {
                val fav = Features.isFav(f.id)
                row.addView(KeyButton(act, if (fav) "★" else "☆", 18f).apply {
                    setTextColor(if (fav) C.ACCENT else C.SUB)
                    background = rounded(0, act.dp(10f))
                    onTap = {
                        val on = Features.toggleFav(f.id)
                        Toast.makeText(act, if (on) "キーボード画面に「${f.label}」を置きました（✎ で場所を変えられます）" else "キーボード画面から「${f.label}」を外しました", Toast.LENGTH_SHORT).show()
                        onFavChanged?.invoke()
                        render()
                    }
                }, LinearLayout.LayoutParams(act.dpi(48), act.dpi(44)))
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = act.dpi(2) })
        }
        if (reorder) {
            list.addView(KeyButton(act, "最初の並びに戻す", 13f).apply {
                onTap = {
                    Prefs.menuOrder = ""
                    render()
                }
            }, LinearLayout.LayoutParams(-1, act.dpi(42)).apply { topMargin = act.dpi(8) })
        }
    }
}
