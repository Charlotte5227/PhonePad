package com.phonepad.app

import android.content.Context
import android.content.SharedPreferences

/** 接続先 */
/** 接続先。fp=PCの証明書の指紋 (暗号化の確認用)、mac=電源を入れるためのアドレス */
data class Target(
    val hosts: List<String>,
    val port: Int,
    val code: String,
    val name: String,
    val fp: String = "",
    val mac: String = "",
)

/** 設定値の保存場所 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("phonepad", Context.MODE_PRIVATE)
    }

    private fun f(key: String, def: Float) = sp.getFloat(key, def)
    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def

    // ---- マウス ----
    var mouseSpeed: Float
        get() = f("mouseSpeed", 1.5f)
        set(v) = sp.edit().putFloat("mouseSpeed", v).apply()
    var mouseAccel: Float
        get() = f("mouseAccel", 0.6f)
        set(v) = sp.edit().putFloat("mouseAccel", v).apply()
    var scrollSpeed: Float
        get() = f("scrollSpeed", 1.0f)
        set(v) = sp.edit().putFloat("scrollSpeed", v).apply()
    var invertTwoFinger: Boolean
        get() = b("invertTwoFinger", false)
        set(v) = sp.edit().putBoolean("invertTwoFinger", v).apply()
    var stickSpeed: Float
        get() = f("stickSpeed", 1.0f)
        set(v) = sp.edit().putFloat("stickSpeed", v).apply()

    /** マウスのスティック: true=触れた位置に出る  false=真ん中に固定 */
    var mouseStickFloating: Boolean
        get() = b("mouseStickFloating", true)
        set(v) = sp.edit().putBoolean("mouseStickFloating", v).apply()

    /** "trackpad" または "stick" */
    var padMode: String
        get() = s("padMode", "trackpad")
        set(v) = sp.edit().putString("padMode", v).apply()

    /** マウスの片手操作 (oneHandLeft=true で左手用) */
    var oneHand: Boolean
        get() = b("oneHand", false)
        set(v) = sp.edit().putBoolean("oneHand", v).apply()
    var oneHandLeft: Boolean
        get() = b("oneHandLeft", false)
        set(v) = sp.edit().putBoolean("oneHandLeft", v).apply()

    // ---- キーボード ----
    var latinOnly: Boolean
        get() = b("latinOnly", false)
        set(v) = sp.edit().putBoolean("latinOnly", v).apply()

    /** 以前の設定「PCの入力欄を常に表示」。今はキーボード画面の配置の初期値にだけ使う */
    val fieldAlways: Boolean
        get() = b("fieldAlways", false)

    /** キーボード画面の配置 (JSON: {部品: [x, y, 幅, 高さ, 隠す]})。mode = tp / stick / oneR / oneL */
    fun kbLayout(mode: String): String = s("kbLayout.$mode", "")
    fun setKbLayout(mode: String, v: String?) {
        if (v == null) sp.edit().remove("kbLayout.$mode").apply() else sp.edit().putString("kbLayout.$mode", v).apply()
    }

    /** キーボード画面に自分で追加したキー (JSON 配列) */
    var kbCustom: String
        get() = s("kbCustom", "[]")
        set(v) = sp.edit().putString("kbCustom", v).apply()

    // ---- コントローラー ----
    var turboRate: Int
        get() = i("turboRate", 15)
        set(v) = sp.edit().putInt("turboRate", v).apply()

    /** true: Switchの配置どおり(右=A→XboxのB)  false: 文字どおり(A→XboxのA) */
    var positionalAbxy: Boolean
        get() = b("positionalAbxy", false)
        set(v) = sp.edit().putBoolean("positionalAbxy", v).apply()

    /** コントローラーのスティック: true=触れた位置に出る */
    var padStickFloating: Boolean
        get() = b("padStickFloating", true)
        set(v) = sp.edit().putBoolean("padStickFloating", v).apply()

    /** コントローラーの配置 "id:x,y,s;..." (空なら初期配置) */
    var padLayout: String
        get() = s("padLayout", "")
        set(v) = sp.edit().putString("padLayout", v).apply()

    /** ゲームの振動をスマホで再現する */
    var rumble: Boolean
        get() = b("rumble", true)
        set(v) = sp.edit().putBoolean("rumble", v).apply()

    /** スティックの遊び (0〜0.4) */
    var padDeadzone: Float
        get() = f("padDeadzone", 0.08f)
        set(v) = sp.edit().putFloat("padDeadzone", v).apply()

    /** スティックの感度 (1=標準) */
    var padSensitivity: Float
        get() = f("padSensitivity", 1.0f)
        set(v) = sp.edit().putFloat("padSensitivity", v).apply()

    /** スティックの反応カーブ (1=まっすぐ、大きいほど小さく倒したとき細かい) */
    var padCurve: Float
        get() = f("padCurve", 1.0f)
        set(v) = sp.edit().putFloat("padCurve", v).apply()

    // ---- ショートカット ----
    /** JSON配列 [{"l":名前,"k":キー,"m":修飾}] (空なら初期のもの) */
    var shortcuts: String
        get() = s("shortcuts", "")
        set(v) = sp.edit().putString("shortcuts", v).apply()

    // ---- 画面表示 ----
    /** 0=低画質(速い) 1=標準 2=高画質 */
    var screenQuality: Int
        get() = i("screenQuality", 1)
        set(v) = sp.edit().putInt("screenQuality", v).apply()

    var screenControl: Boolean
        get() = b("screenControl", true)
        set(v) = sp.edit().putBoolean("screenControl", v).apply()

    /** PC画面表示で文字を入力するとき、最後にタップした場所を拡大する */
    var screenAutoZoom: Boolean
        get() = b("screenAutoZoom", true)
        set(v) = sp.edit().putBoolean("screenAutoZoom", v).apply()

    /** PC画面表示で1本指でなぞったとき: true=マウスカーソルを動かす / false=スクロール */
    var screenDragCursor: Boolean
        get() = b("screenDragCursor", false)
        set(v) = sp.edit().putBoolean("screenDragCursor", v).apply()

    /** PCの音をスマホで聞く */
    var pcAudio: Boolean
        get() = b("pcAudio", false)
        set(v) = sp.edit().putBoolean("pcAudio", v).apply()

    /** スマホで聞いている間は PCのスピーカーから音を出さない */
    var pcAudioMutePc: Boolean
        get() = b("pcAudioMutePc", false)
        set(v) = sp.edit().putBoolean("pcAudioMutePc", v).apply()

    /** 矢印キーなどを長押ししたときの連続入力 (回/秒) と、始まるまでの時間 (ミリ秒) */
    var keyRepeatRate: Int
        get() = sp.getInt("keyRepeatRate", 18)
        set(v) = sp.edit().putInt("keyRepeatRate", v).apply()
    var keyRepeatDelay: Int
        get() = sp.getInt("keyRepeatDelay", 380)
        set(v) = sp.edit().putInt("keyRepeatDelay", v).apply()

    /** 配置のプリセット (JSON: {名前: 配置文字列}) */
    /** コントローラーの自由な形のエリア (JSON) */
    var padAreas: String
        get() = s("padAreas", "[]")
        set(v) = sp.edit().putString("padAreas", v).apply()

    var padPresets: String
        get() = s("padPresets", "{}")
        set(v) = sp.edit().putString("padPresets", v).apply()

    // ---- 共通 ----
    var vibrate: Boolean
        get() = b("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    var target: Target?
        get() {
            val hosts = s("t.hosts", "")
            val code = s("t.code", "")
            if (hosts.isEmpty() || code.isEmpty()) return null
            return Target(
                hosts.split(",").filter { it.isNotBlank() }, i("t.port", 47800), code, s("t.name", "PC"),
                s("t.fp", ""), s("t.mac", ""),
            )
        }
        set(t) {
            val e = sp.edit()
            if (t == null) {
                e.remove("t.hosts").remove("t.code").remove("t.fp")
            } else {
                e.putString("t.hosts", t.hosts.joinToString(","))
                    .putInt("t.port", t.port)
                    .putString("t.code", t.code)
                    .putString("t.name", t.name)
                    .putString("t.fp", t.fp)
                    .putString("t.mac", t.mac)
                // PC名ごとにコードを覚えておく (自動検出で選んだときに再入力しなくて済む)
                e.putString("code." + t.name, t.code)
            }
            e.apply()
        }

    fun savedCodeFor(pcName: String): String? = sp.getString("code.$pcName", null)
}
