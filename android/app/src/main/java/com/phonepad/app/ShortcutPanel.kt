package com.phonepad.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 登録したボタン。key は "C<16進>"(文字キー) または特殊キーの名前。
 * run が空でなければ、キー操作の代わりにPCでアプリ・ファイル・URLを開く。
 */
data class Shortcut(var label: String, var key: String, var mods: Int, var run: String = "")

object Shortcuts {
    private val DEFAULTS = listOf(
        Shortcut("コピー", "C63", Modifiers.CTRL),
        Shortcut("貼り付け", "C76", Modifiers.CTRL),
        Shortcut("切り取り", "C78", Modifiers.CTRL),
        Shortcut("元に戻す", "C7a", Modifiers.CTRL),
        Shortcut("やり直し", "C79", Modifiers.CTRL),
        Shortcut("全て選択", "C61", Modifiers.CTRL),
        Shortcut("保存", "C73", Modifiers.CTRL),
        Shortcut("検索", "C66", Modifiers.CTRL),
        Shortcut("ウィンドウ切替", "TAB", Modifiers.ALT),
        Shortcut("閉じる", "F4", Modifiers.ALT),
        Shortcut("エクスプローラー", "C65", Modifiers.WIN),
        Shortcut("デスクトップ", "C64", Modifiers.WIN),
        Shortcut("スクリーンショット", "C73", Modifiers.WIN or Modifiers.SHIFT),
        Shortcut("タスクマネージャー", "ESC", Modifiers.CTRL or Modifiers.SHIFT),
        Shortcut("新しいタブ", "C74", Modifiers.CTRL),
        Shortcut("タブを閉じる", "C77", Modifiers.CTRL),
    )

    /** 特殊キーの選択肢 (表示名 → 送る名前)。先頭は「文字キー」 */
    val SPECIAL = listOf(
        "文字キー（下に入力）" to "", "Enter" to "ENTER", "Esc" to "ESC", "Tab" to "TAB", "Space" to "SPACE",
        "Backspace" to "BACK", "Delete" to "DEL", "Insert" to "INS", "Home" to "HOME", "End" to "END",
        "PageUp" to "PGUP", "PageDown" to "PGDN", "↑" to "UP", "↓" to "DOWN", "←" to "LEFT", "→" to "RIGHT",
    ) + (1..12).map { "F$it" to "F$it" } + listOf(
        "PrintScreen" to "PRTSC", "半角/全角" to "KANJI", "Winキーだけ" to "WIN", "アプリキー" to "MENU",
        "音量＋" to "VOLUP", "音量－" to "VOLDOWN", "消音" to "VOLMUTE", "再生/一時停止" to "MPLAY",
    )

    fun load(): MutableList<Shortcut> {
        val raw = Prefs.shortcuts
        if (raw.isEmpty()) return DEFAULTS.map { it.copy() }.toMutableList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Shortcut(o.optString("l"), o.optString("k"), o.optInt("m"), o.optString("r"))
            }.toMutableList()
        } catch (_: Exception) {
            DEFAULTS.map { it.copy() }.toMutableList()
        }
    }

    fun save(list: List<Shortcut>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("l", it.label).put("k", it.key).put("m", it.mods).put("r", it.run)) }
        Prefs.shortcuts = a.toString()
    }

    fun reset() {
        Prefs.shortcuts = ""
    }

    fun keyName(key: String): String {
        if (key.startsWith("C") && key.length > 1) {
            val cp = key.substring(1).toIntOrNull(16) ?: return key
            return String(Character.toChars(cp)).uppercase()
        }
        return SPECIAL.firstOrNull { it.second == key }?.first ?: key
    }

    /** 「Ctrl + Shift + Esc」のような表示 */
    fun describe(s: Shortcut): String {
        if (s.run.isNotEmpty()) {
            val t = s.run
            return if (t.startsWith("http")) "サイトを開く" else "アプリを開く"
        }
        val parts = ArrayList<String>()
        if (s.mods and Modifiers.CTRL != 0) parts.add("Ctrl")
        if (s.mods and Modifiers.SHIFT != 0) parts.add("Shift")
        if (s.mods and Modifiers.ALT != 0) parts.add("Alt")
        if (s.mods and Modifiers.WIN != 0) parts.add("Win")
        if (s.key == "WIN") {
            if (parts.isEmpty()) return "Win"
        } else {
            parts.add(keyName(s.key))
        }
        return parts.joinToString(" + ")
    }

    fun send(s: Shortcut) {
        if (s.run.isNotEmpty()) Link.send("RUN " + Link.b64(s.run)) else Link.send("KY ${s.key} ${s.mods}")
    }
}

/** ★ パネル: ショートカット・クリップボード・電源 */
class ShortcutPanel(private val act: Activity) {
    private val dialog = Dialog(act)
    private val list = Shortcuts.load()
    private var editing = false
    private lateinit var grid: GridLayout
    private lateinit var editBtn: KeyButton
    private lateinit var hint: TextView

    fun show() {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, act.dp(18f))
            setPadding(act.dpi(16), act.dpi(14), act.dpi(16), act.dpi(14))
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label(act, "★ ショートカット", 17f, C.INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        editBtn = KeyButton(act, "編集", 13f).apply {
            setPadding(act.dpi(14), 0, act.dpi(14), 0)
            onTap = {
                editing = !editing
                refresh()
            }
        }
        head.addView(editBtn, LinearLayout.LayoutParams(-2, act.dpi(38)))
        // 閉じるは上に置く (下に置くと、すぐ上の再起動などを押し間違えやすいため)
        head.addView(KeyButton(act, "✕ 閉じる", 13f).apply {
            setPadding(act.dpi(14), 0, act.dpi(14), 0)
            onTap = { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, act.dpi(38)).apply { leftMargin = act.dpi(6) })
        root.addView(head)
        hint = label(act, "", 12f, C.SUB).apply { setPadding(0, act.dpi(4), 0, act.dpi(4)) }
        root.addView(hint)

        grid = GridLayout(act).apply { columnCount = 3 }
        root.addView(grid)

        // 電源
        section(root, "PC")
        root.addView(smallBtn("📊 PCの状態を見る") { StatusPanel(act).show() },
            LinearLayout.LayoutParams(-1, act.dpi(46)).apply { bottomMargin = act.dpi(6) })
        val p1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        p1.addView(smallBtn("🔒 ロック") { power("LOCK", null) }, weightLp())
        p1.addView(smallBtn("画面オフ") { power("MONOFF", null) }, weightLp())
        p1.addView(smallBtn("スリープ") { power("SLEEP", "PCをスリープしますか？") }, weightLp())
        root.addView(p1)
        val p2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, act.dpi(6), 0, 0) }
        p2.addView(smallBtn("再起動") { power("RESTART", "PCを再起動しますか？\n保存していない作業は失われます。") }, weightLp())
        p2.addView(smallBtn("シャットダウン") { power("SHUTDOWN", "PCをシャットダウンしますか？\n保存していない作業は失われます。") }, weightLp())
        root.addView(p2)

        // クリップボード
        section(root, "クリップボード")
        val clip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        clip.addView(smallBtn("スマホ → PC") { phoneToPc(false) }, weightLp())
        clip.addView(smallBtn("PC → スマホ") { Link.send("CQ") }, weightLp())
        root.addView(clip)
        root.addView(smallBtn("スマホでコピーした内容をPCに貼り付け") { phoneToPc(true) },
            LinearLayout.LayoutParams(-1, act.dpi(46)).apply { topMargin = act.dpi(6) })


        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(ScrollView(act).apply { addView(root) })
        dialog.window?.let {
            it.setBackgroundDrawable(rounded(0, 0f))
            it.setLayout((act.resources.displayMetrics.widthPixels * 0.94f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        Link.onClipText = { t ->
            act.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("PC", t))
            toast(if (t.isEmpty()) "PCのクリップボードに文字がありません" else "PCの内容をスマホにコピーしました")
        }
        Link.onClipSent = { toast("PCにコピーしました") }
        dialog.setOnDismissListener {
            Link.onClipText = null
            Link.onClipSent = null
            Link.onApps = null
        }
        refresh()
        dialog.show()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

    private fun section(root: LinearLayout, t: String) {
        root.addView(label(act, t, 14f, C.ACCENT, true).apply { setPadding(0, act.dpi(16), 0, act.dpi(6)) })
    }

    private fun weightLp() = LinearLayout.LayoutParams(0, act.dpi(46), 1f).apply {
        setMargins(act.dpi(3), 0, act.dpi(3), 0)
    }

    private fun smallBtn(t: String, f: () -> Unit) = KeyButton(act, t, 13f).apply { onTap = f }

    private fun refresh() {
        editBtn.text = if (editing) "完了" else "編集"
        editBtn.active = if (editing) 2 else 0
        hint.text = if (editing) "変更したいボタンに触れてください" else "タップでPCに送ります"
        grid.removeAllViews()
        for (sc in list) {
            val b = KeyButton(act, "${sc.label}\n${Shortcuts.describe(sc)}", 12f).apply {
                setPadding(act.dpi(4), act.dpi(4), act.dpi(4), act.dpi(4))
                maxLines = 3
                active = if (editing) 1 else 0
                onTap = { if (editing) editShortcut(sc) else Shortcuts.send(sc) }
            }
            grid.addView(b, cell())
        }
        if (editing) {
            grid.addView(KeyButton(act, "＋ 追加", 13f).apply {
                onTap = { editShortcut(null) }
            }, cell())
            grid.addView(KeyButton(act, "初期に戻す", 12f).apply {
                onTap = {
                    AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("ショートカットを最初の状態に戻しますか？")
                        .setPositiveButton("戻す") { _, _ ->
                            Shortcuts.reset()
                            list.clear(); list.addAll(Shortcuts.load())
                            refresh()
                        }
                        .setNegativeButton("キャンセル", null).show()
                }
            }, cell())
        }
    }

    private fun cell() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply {
        width = 0
        height = act.dpi(58)
        setMargins(act.dpi(3), act.dpi(3), act.dpi(3), act.dpi(3))
    }

    private fun phoneToPc(paste: Boolean) {
        val cm = act.getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
        if (t.isNullOrEmpty()) {
            toast("スマホのクリップボードに文字がありません")
            return
        }
        Link.send("CB " + Link.b64(t))
        if (paste) Link.send("KY C76 ${Modifiers.CTRL}")
    }

    private fun power(action: String, confirm: String?) {
        if (confirm == null) {
            Link.send("PW $action")
            return
        }
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(confirm)
            .setPositiveButton("実行") { _, _ -> Link.send("PW $action") }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * ショートカットの追加・変更 (sc=null なら追加)。
     * onSave を渡すと、★の一覧ではなくそちらに渡す (キーボード画面に置くキーなど)
     */
    fun editShortcut(sc: Shortcut?, title: String? = null, onSave: ((Shortcut) -> Unit)? = null) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dpi(20), act.dpi(8), act.dpi(20), 0)
        }
        val name = EditText(act).apply {
            hint = "ボタンの名前（例: エクスプローラー）"
            setText(sc?.label ?: "")
        }
        box.addView(name)

        // 種類: キー操作 / アプリ・サイトを開く
        var isRun = !sc?.run.isNullOrEmpty()
        val typeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, act.dpi(10), 0, 0) }
        val keyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val runBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val typeKey = KeyButton(act, "キー操作", 13f)
        val typeRun = KeyButton(act, "アプリ・サイトを開く", 13f)
        fun applyType() {
            typeKey.active = if (isRun) 0 else 2
            typeRun.active = if (isRun) 2 else 0
            keyBox.visibility = if (isRun) View.GONE else View.VISIBLE
            runBox.visibility = if (isRun) View.VISIBLE else View.GONE
        }
        typeKey.onTap = { isRun = false; applyType() }
        typeRun.onTap = { isRun = true; applyType() }
        typeRow.addView(typeKey, LinearLayout.LayoutParams(0, act.dpi(42), 1f).apply { rightMargin = act.dpi(4) })
        typeRow.addView(typeRun, LinearLayout.LayoutParams(0, act.dpi(42), 1.4f))
        box.addView(typeRow)
        box.addView(keyBox)
        box.addView(runBox)

        // ---- アプリ・サイト ----
        val runField = EditText(act).apply {
            hint = "URL（https://…）またはPC上のファイルの場所"
            setText(sc?.run ?: "")
            textSize = 14f
        }
        runBox.addView(label(act, "開くもの", 13f, C.SUB).apply { setPadding(0, act.dpi(10), 0, act.dpi(4)) })
        runBox.addView(KeyButton(act, "PCのアプリ一覧から選ぶ", 13f).apply {
            onTap = {
                pickApp { appName, path ->
                    runField.setText(path)
                    if (name.text.isNullOrBlank()) name.setText(appName)
                }
            }
        }, LinearLayout.LayoutParams(-1, act.dpi(44)))
        runBox.addView(runField)

        // ---- キー操作 ----
        var mods = sc?.mods ?: 0
        keyBox.addView(label(act, "一緒に押すキー", 13f, C.SUB).apply { setPadding(0, act.dpi(10), 0, act.dpi(4)) })
        val modRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val preview = label(act, "", 14f, C.ACCENT, true).apply { setPadding(0, act.dpi(10), 0, 0) }
        var keyName = if (isRun) "" else (sc?.key ?: "")
        val charField = EditText(act).apply {
            hint = "文字キー（例: E）"
            filters = arrayOf(android.text.InputFilter.LengthFilter(1))
        }

        fun currentKey(): String {
            if (keyName.isNotEmpty()) return keyName
            val t = charField.text?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return ""
            return "C%x".format(t.lowercase().codePointAt(0))
        }

        fun updatePreview() {
            val k = currentKey()
            preview.text = if (k.isEmpty()) "キーを選んでください" else "送る操作: " + Shortcuts.describe(Shortcut("", k, mods))
        }
        for ((lab, bit) in listOf("Ctrl" to Modifiers.CTRL, "Shift" to Modifiers.SHIFT, "Alt" to Modifiers.ALT, "Win" to Modifiers.WIN)) {
            modRow.addView(KeyButton(act, lab, 13f).apply {
                active = if (mods and bit != 0) 2 else 0
                onTap = {
                    mods = mods xor bit
                    active = if (mods and bit != 0) 2 else 0
                    updatePreview()
                }
            }, LinearLayout.LayoutParams(0, act.dpi(42), 1f).apply { setMargins(act.dpi(2), 0, act.dpi(2), 0) })
        }
        keyBox.addView(modRow)

        keyBox.addView(label(act, "キー", 13f, C.SUB).apply { setPadding(0, act.dpi(10), 0, act.dpi(4)) })
        val spinner = Spinner(act)
        spinner.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, Shortcuts.SPECIAL.map { it.first })
        val isChar = keyName.isEmpty() || keyName.startsWith("C")
        spinner.setSelection(if (isChar) 0 else Shortcuts.SPECIAL.indexOfFirst { it.second == keyName }.coerceAtLeast(0))
        keyBox.addView(spinner)
        if (isChar && keyName.startsWith("C")) charField.setText(Shortcuts.keyName(keyName))
        charField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updatePreview()
        })
        keyBox.addView(charField)
        if (isChar) keyName = ""
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                keyName = Shortcuts.SPECIAL[pos].second
                charField.visibility = if (keyName.isEmpty()) View.VISIBLE else View.GONE
                updatePreview()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        keyBox.addView(preview)
        updatePreview()
        applyType()

        val b = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title ?: if (sc == null) "ボタンを追加" else "ボタンを変更")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val item: Shortcut
                if (isRun) {
                    val target = runField.text.toString().trim()
                    if (target.isEmpty()) {
                        toast("開くものを選んでください")
                        return@setPositiveButton
                    }
                    val lab = name.text.toString().trim().ifEmpty { target.substringAfterLast('\\').substringBeforeLast('.') }
                    item = Shortcut(lab, "", 0, target)
                } else {
                    val k = currentKey()
                    if (k.isEmpty()) {
                        toast("キーを選んでください")
                        return@setPositiveButton
                    }
                    val lab = name.text.toString().trim().ifEmpty { Shortcuts.describe(Shortcut("", k, mods)) }
                    item = Shortcut(lab, k, mods)
                }
                if (onSave != null) {
                    onSave(item)
                    return@setPositiveButton
                }
                if (sc == null) {
                    list.add(item)
                } else {
                    sc.label = item.label; sc.key = item.key; sc.mods = item.mods; sc.run = item.run
                }
                Shortcuts.save(list)
                refresh()
            }
            .setNegativeButton("キャンセル", null)
        if (sc != null && onSave == null) {
            b.setNeutralButton("削除") { _, _ ->
                list.remove(sc)
                Shortcuts.save(list)
                refresh()
            }
        }
        b.show()
    }

    /** PCのスタートメニューにあるアプリから選ぶ */
    private fun pickApp(onPick: (String, String) -> Unit) {
        if (Link.state != Link.State.CONNECTED) {
            toast("PCとつながっていません")
            return
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(act.dpi(16), act.dpi(8), act.dpi(16), 0)
        }
        val filter = EditText(act).apply { hint = "名前で絞り込み"; isSingleLine = true }
        box.addView(filter)
        val listView = android.widget.ListView(act)
        box.addView(listView, LinearLayout.LayoutParams(-1, (act.resources.displayMetrics.heightPixels * 0.5f).toInt()))
        val names = ArrayList<String>()
        val paths = HashMap<String, String>()
        val adapter = ArrayAdapter(act, android.R.layout.simple_list_item_1, names)
        listView.adapter = adapter
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("PCのアプリ（読み込み中…）")
            .setView(box)
            .setNegativeButton("キャンセル", null)
            .create()
        listView.setOnItemClickListener { _, _, pos, _ ->
            val n = adapter.getItem(pos) ?: return@setOnItemClickListener
            onPick(n, paths[n] ?: return@setOnItemClickListener)
            dlg.dismiss()
        }
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = adapter.filter.filter(s)
        })
        Link.onApps = { json ->
            try {
                val a = JSONArray(json)
                names.clear(); paths.clear()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val n = o.optString("n")
                    names.add(n); paths[n] = o.optString("p")
                }
                adapter.notifyDataSetChanged()
                dlg.setTitle("PCのアプリ（${names.size}個）")
            } catch (_: Exception) {
            }
        }
        dlg.setOnDismissListener { Link.onApps = null }
        dlg.show()
        Link.send("AL")
    }
}
