package com.phonepad.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * アプリの機能の一覧。≡ のメニューに並び、☆ でキーボード画面にボタンとして置ける (お気に入り)。
 * ID はブラウザ版・PC の画面と同じ。
 */
object Features {
    class Feature(val id: String, val icon: String, val label: String)

    val ALL = listOf(
        Feature("kb", "⌨", "キーボード・マウス"),
        Feature("pad", "🎮", "コントローラー"),
        Feature("screen", "🖥", "PCの画面"),
        Feature("shortcuts", "★", "ショートカット"),
        Feature("volume", "🔊", "音量・音"),
        Feature("clipboard", "📋", "クリップボード"),
        Feature("files", "📎", "ファイルをPCへ送る"),
        Feature("power", "⏻", "PCの電源"),
        Feature("pcinfo", "📊", "PCの状態"),
        Feature("kbedit", "✎", "キーボード画面の配置"),
        Feature("set_mouse", "🖱", "マウスの設定"),
        Feature("set_kb", "⌨", "キーボードの設定"),
        Feature("set_pad", "🎮", "コントローラーの設定"),
        Feature("set_screen", "🖥", "PCの画面の設定"),
        Feature("set_receive", "📥", "PCから受け取る"),
        Feature("set_other", "⚙", "その他の設定"),
        Feature("help", "？", "使い方"),
    )

    fun get(id: String) = ALL.firstOrNull { it.id == id }

    /** メニューの並び (自分で並べ替えたもの + 新しく増えた機能) */
    fun ordered(): List<Feature> {
        val ids = try {
            val a = JSONArray(Prefs.menuOrder)
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        val out = ids.mapNotNull { get(it) }.toMutableList()
        for (f in ALL) if (out.none { it.id == f.id }) out.add(f)
        return out
    }

    fun saveOrder(list: List<Feature>) {
        Prefs.menuOrder = JSONArray(list.map { it.id }).toString()
    }

    // ---------------------------------------------------------------- お気に入り (キーボード画面のボタン)

    private fun customs(): JSONArray = try {
        JSONArray(Prefs.kbCustom)
    } catch (_: Exception) {
        JSONArray()
    }

    fun isFav(id: String): Boolean {
        val a = customs()
        for (i in 0 until a.length()) if (a.optJSONObject(i)?.optString("f") == id) return true
        return false
    }

    /** お気に入りに入れる・外す。入れたら true */
    fun toggleFav(id: String): Boolean {
        val f = get(id) ?: return false
        val a = customs()
        val keep = JSONArray()
        var removed = false
        var favCount = 0
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("f") == id) {
                removed = true
                continue
            }
            if (o.optString("f").isNotEmpty()) favCount++
            keep.put(o)
        }
        if (!removed) {
            // マウスの面の上の方に、3つずつ並べて置く (✎ で好きな場所へ動かせる)
            val n = favCount
            val rect = JSONArray().put(0.04 + (n % 3) * 0.31).put(0.33 + (n / 3) % 6 * 0.075).put(0.29).put(0.06).put(0)
            keep.put(JSONObject().put("id", "f_$id").put("l", "${f.icon} ${f.label}").put("k", "").put("m", 0).put("r", "")
                .put("f", id).put("rect", rect))
        }
        Prefs.kbCustom = keep.toString()
        return !removed
    }

    // ---------------------------------------------------------------- 開く

    fun launch(act: Activity, id: String) {
        when (id) {
            "kb" -> if (act !is RemoteActivity) {
                act.startActivity(Intent(act, RemoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }

            "kbedit" -> if (act is RemoteActivity) act.startEditing() else {
                act.startActivity(Intent(act, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_EDIT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }

            "pad" -> if (act !is ControllerActivity) act.startActivity(Intent(act, ControllerActivity::class.java))
            "screen" -> if (act !is ScreenActivity) act.startActivity(Intent(act, ScreenActivity::class.java))
            "shortcuts" -> ShortcutPanel(act, ShortcutPanel.Part.SHORTCUTS).show()
            "clipboard" -> ShortcutPanel(act, ShortcutPanel.Part.CLIPBOARD).show()
            "power" -> ShortcutPanel(act, ShortcutPanel.Part.POWER).show()
            "volume" -> VolumePanel(act).show()
            "pcinfo" -> if (Link.state == Link.State.CONNECTED) StatusPanel(act).show() else notConnected(act)
            "files" -> act.startActivity(Intent(act, SendFileActivity::class.java))
            "help" -> act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.SITE + "guide.html")))
            else -> if (id.startsWith("set_")) {
                act.startActivity(Intent(act, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, id.removePrefix("set_")))
            }
        }
    }

    private fun notConnected(act: Activity) =
        Toast.makeText(act, "PCとつながっているときに使えます", Toast.LENGTH_SHORT).show()
}
