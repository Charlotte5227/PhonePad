package com.phonepad.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 配置・ショートカットを PC とやりとりする (PC の画面から変えられるように)。
 *
 * ・つないだ直後に全部を送る (CFG)。スマホで変えてまだ伝えていないキーには印をつけておく
 * ・つないでいる間にスマホで変えたら、すぐ送る (CFK)
 * ・PC で変えた分 (CU) は保存して、画面を作り直す
 * ・PC で前に出ているアプリ用の配置 (CP) は、保存せずに一時的に使う。その間の変更は PC の配置に送る (CPK)
 *
 * PC とやりとりする値の形はブラウザ版と同じ。コントローラーの配置だけ、このアプリの保存の形と変換する。
 */
object ConfigSync {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** 前に出ているアプリ用の配置 (値は PC とやりとりする形) */
    private class Overlay(val id: String, val name: String, val items: MutableMap<String, String>)

    private var overlay: Overlay? = null

    @Volatile
    private var ready = false
    private val pending = LinkedHashMap<String, String>()

    /** 設定が外から変わったとき (画面を作り直す)。main スレッドで呼ぶ */
    val listeners = ArrayList<() -> Unit>()

    /** お知らせ (画面に短く出す) */
    var onNotice: ((String) -> Unit)? = null

    val KB_MODES = listOf("tp", "stick", "oneR", "oneL")
    private val KEYS = listOf("shortcuts", "kbCustom", "padLayout", "padAreas", "padPresets", "menuOrder") + KB_MODES.map { "kbLayout.$it" }

    fun isKey(k: String) = k in KEYS

    // ---------------------------------------------------------------- 読み書き (Prefs から使う)

    /** アプリごとの配置を使っている間は、その値 (このアプリの形) を返す。使っていなければ null */
    fun read(key: String): String? = synchronized(lock) {
        val v = overlay?.items?.get(key) ?: return null
        toLocal(key, v)
    }

    /** 保存する (アプリごとの配置を使っている間は、PC のその配置を変える) */
    fun write(key: String, localValue: String?) {
        val canon = toCanon(key, localValue ?: "")
        synchronized(lock) {
            val ov = overlay
            if (ov != null && ov.items.containsKey(key)) {
                ov.items[key] = canon
                Link.send("CPK " + Link.b64(JSONObject().put("id", ov.id).put("items", JSONObject().put(key, canon)).toString()))
                return
            }
        }
        Prefs.rawPut(key, localValue)
        val d = dirty()
        if (d.add(key)) setDirty(d)
        if (ready && Link.state == Link.State.CONNECTED) {
            synchronized(lock) { pending[key] = canon }
            main.removeCallbacks(flush)
            main.postDelayed(flush, 250)
        }
    }

    private val flush = Runnable {
        val items = JSONObject()
        synchronized(lock) {
            for ((k, v) in pending) items.put(k, v)
            pending.clear()
        }
        if (items.length() > 0) Link.send("CFK " + Link.b64(JSONObject().put("items", items).toString()))
    }

    // ---------------------------------------------------------------- 通信 (Link から呼ぶ)

    fun onConnected() {
        ready = true
        val items = JSONObject()
        for (k in KEYS) items.put(k, toCanon(k, Prefs.rawGet(k) ?: ""))
        val msg = JSONObject()
            .put("id", deviceId())
            .put("kind", "android")
            .put("name", android.os.Build.MODEL ?: "Android")
            .put("items", items)
            .put("dirty", JSONArray(dirty().toList()))
        Link.send("CFG " + Link.b64(msg.toString()))
    }

    fun onDisconnected() {
        ready = false
        val had = synchronized(lock) {
            val h = overlay != null
            overlay = null
            h
        }
        if (had) notifyChanged(null)
    }

    /** PC からの行を処理したら true */
    fun handle(line: String, unb64: (String) -> String): Boolean {
        when {
            line.startsWith("CA ") -> {
                try {
                    val a = JSONArray(unb64(line.removePrefix("CA ").trim()))
                    val d = dirty()
                    for (i in 0 until a.length()) d.remove(a.getString(i))
                    setDirty(d)
                } catch (_: Exception) {
                }
            }

            line.startsWith("CU ") -> {
                try {
                    val o = JSONObject(unb64(line.removePrefix("CU ").trim())).optJSONObject("items") ?: return true
                    for (k in o.keys()) {
                        if (!isKey(k)) continue
                        val v = o.optString(k)
                        Prefs.rawPut(k, if (v.isEmpty()) null else toLocal(k, v))
                    }
                    notifyChanged("PCの画面で変えた設定を受け取りました")
                } catch (_: Exception) {
                }
            }

            line == "CP" || line.startsWith("CP ") -> {
                val b = line.removePrefix("CP").trim()
                var notice: String? = null
                synchronized(lock) {
                    val before = overlay
                    if (b.isEmpty()) {
                        overlay = null
                        if (before != null) notice = "いつもの配置に戻しました"
                    } else {
                        try {
                            val o = JSONObject(unb64(b))
                            val its = HashMap<String, String>()
                            o.optJSONObject("items")?.let { io -> for (k in io.keys()) if (isKey(k)) its[k] = io.optString(k) }
                            val ov = Overlay(o.optString("id"), o.optString("name"), its)
                            overlay = ov
                            if (before?.id != ov.id) notice = "PCで使っているアプリに合わせて、「${ov.name}」の配置にしました"
                        } catch (_: Exception) {
                        }
                    }
                }
                notifyChanged(notice)
            }

            else -> return false
        }
        return true
    }

    private fun notifyChanged(notice: String?) {
        main.post {
            listeners.toList().forEach { it() }
            if (notice != null) onNotice?.invoke(notice)
        }
    }

    // ---------------------------------------------------------------- 小道具

    fun deviceId(): String {
        var id = Prefs.rawGet("deviceId") ?: ""
        if (id.length < 8) {
            id = "a-" + UUID.randomUUID().toString().replace("-", "")
            Prefs.rawPut("deviceId", id)
        }
        return id
    }

    private fun dirty(): MutableSet<String> =
        (Prefs.rawGet("syncDirty") ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()

    private fun setDirty(d: Set<String>) = Prefs.rawPut("syncDirty", if (d.isEmpty()) null else d.joinToString(","))

    /** このアプリの保存の形 → PC とやりとりする形 */
    fun toCanon(key: String, v: String): String {
        if (key != "padLayout" || v.isEmpty()) return v
        // "id:x,y,大きさ,形,隠す;..." → {"id":[x,y,大きさ,形,隠す]}
        val o = JSONObject()
        for (item in v.split(";")) {
            val kv = item.split(":")
            if (kv.size != 2) continue
            val a = JSONArray()
            kv[1].split(",").forEach { n -> n.toDoubleOrNull()?.let { a.put(it) } }
            if (a.length() >= 3) o.put(kv[0], a)
        }
        return o.toString()
    }

    /** PC とやりとりする形 → このアプリの保存の形 */
    fun toLocal(key: String, v: String): String {
        if (key != "padLayout" || v.isEmpty()) return v
        return try {
            val o = JSONObject(v)
            o.keys().asSequence().mapNotNull { id ->
                val a = o.optJSONArray(id) ?: return@mapNotNull null
                if (a.length() < 3) return@mapNotNull null
                val x = a.optDouble(0, 0.5).toFloat()
                val y = a.optDouble(1, 0.5).toFloat()
                val s = a.optDouble(2, 1.0).toFloat()
                val shape = a.optDouble(3, 0.0).toInt()
                val hidden = if (a.optDouble(4, 0.0) > 0.5) 1 else 0
                "$id:$x,$y,$s,$shape,$hidden"
            }.joinToString(";")
        } catch (_: Exception) {
            ""
        }
    }
}
