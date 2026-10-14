package com.phonepad.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** 接続画面: QRコード読み取り / 自動検出 / 手入力 */
class ConnectActivity : Activity() {

    companion object {
        const val EXTRA_MANUAL = "manual"
    }

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var foundList: LinearLayout
    private lateinit var foundEmpty: TextView

    @Volatile
    private var discovering = false
    private val found = LinkedHashMap<String, Pair<Int, String>>() // ip -> (port, name)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        buildUi()
        showLastCrash()

        val manual = intent.getBooleanExtra(EXTRA_MANUAL, false)
        val saved = Prefs.target
        if (Link.state == Link.State.CONNECTED && !manual) {
            openRemote()
            return
        }
        if (saved != null && !manual) {
            // 前回のPCを自動で探してつなぐ (PCが後から起動してもつながる)
            setBusy("${saved.name} を探しています…（PCで PhonePad が起動すると自動でつながります）", true)
            Link.connect(saved, { err ->
                if (isFinishing || isDestroyed) return@connect
                if (err == null) openRemote() else setBusy(err, false, true)
            }, persistent = true)
        }
    }

    override fun onStart() {
        super.onStart()
        startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        discovering = false
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            val p = dpi(20)
            setPadding(p, p, p, p)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C.BG)
            addView(root)
            setOnApplyWindowInsetsListener { v, ins ->
                val s = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(s.left, s.top, s.right, s.bottom)
                WindowInsets.CONSUMED
            }
        }

        root.addView(label(this, "PhonePad", 30f, C.ACCENT, true))
        root.addView(label(this, "スマホをPCのキーボード・マウス・コントローラーにします", 14f, C.SUB).apply {
            setPadding(0, dpi(4), 0, dpi(24))
        })

        root.addView(KeyButton(this, "QRコードで接続", 18f).apply {
            onTap = { scanQr() }
            layoutParams = LinearLayout.LayoutParams(-1, dpi(64))
        })
        root.addView(label(this, "PCで PhonePad.exe を起動すると、ブラウザにQRコードが表示されます。", 13f, C.SUB).apply {
            setPadding(dpi(4), dpi(8), dpi(4), dpi(24))
        })

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dpi(24), dpi(24)).apply { rightMargin = dpi(10) }
        }
        status = label(this, "", 14f, C.INK)
        statusRow.addView(progress)
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(statusRow)

        root.addView(label(this, "同じWi-Fiで見つかったPC", 15f, C.INK, true).apply {
            setPadding(0, dpi(24), 0, dpi(8))
        })
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foundEmpty = label(this, "探しています…（見つからない場合はQRコードを使ってください）", 13f, C.SUB)
        foundList.addView(foundEmpty)
        root.addView(foundList)

        Prefs.target?.takeIf { it.mac.isNotEmpty() }?.let { t ->
            root.addView(KeyButton(this, "⏻ ${t.name} の電源を入れる", 15f).apply {
                onTap = {
                    Wol.wake(t.mac, t.hosts.firstOrNull())
                    setBusy("電源を入れる合図を送りました。PCが起動して PhonePad が動くと自動でつながります。", true)
                    Link.connect(t, { err ->
                        if (isFinishing || isDestroyed) return@connect
                        if (err == null) openRemote() else setBusy(err, false, true)
                    }, persistent = true)
                }
                layoutParams = LinearLayout.LayoutParams(-1, dpi(52)).apply { topMargin = dpi(20) }
            })
        }

        root.addView(KeyButton(this, "IPアドレスを手入力して接続", 14f).apply {
            onTap = { manualDialog() }
            layoutParams = LinearLayout.LayoutParams(-1, dpi(48)).apply { topMargin = dpi(28) }
        })

        // ---- PCにつながなくてもできること ----
        root.addView(label(this, "PCにつながなくてもできること", 15f, C.INK, true).apply {
            setPadding(0, dpi(28), 0, dpi(4))
        })
        root.addView(label(this, "配置や設定は、このスマホに保存されます。PCの電源が入っていなくても変えておけます。", 13f, C.SUB).apply {
            setPadding(0, 0, 0, dpi(8))
        })
        fun offlineBtn(t: String, f: () -> Unit) = root.addView(KeyButton(this, t, 14f).apply { onTap = f },
            LinearLayout.LayoutParams(-1, dpi(48)).apply { bottomMargin = dpi(6) })
        offlineBtn("⌨ キーボード・マウスの配置を変える") {
            startActivity(Intent(this, RemoteActivity::class.java).putExtra(RemoteActivity.EXTRA_EDIT, true))
            finish()
        }
        offlineBtn("🎮 コントローラーの配置を変える") {
            // 戻るとキーボード画面になるように、下にキーボード画面を置いておく
            startActivities(arrayOf(
                Intent(this, RemoteActivity::class.java),
                Intent(this, ControllerActivity::class.java).putExtra(ControllerActivity.EXTRA_EDIT, true),
            ))
            finish()
        }
        offlineBtn("★ ショートカットを編集") { ShortcutPanel(this).show() }
        offlineBtn("⚙ 設定") { startActivity(Intent(this, SettingsActivity::class.java)) }

        setContentView(scroll)
    }

    private fun setBusy(text: String, busy: Boolean, error: Boolean = false) {
        status.text = text
        status.setTextColor(if (error) C.WARN else C.INK)
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun connectTo(t: Target) {
        setBusy("${t.name} に接続しています…", true)
        Link.connect(t, { err ->
            if (isFinishing || isDestroyed) return@connect
            if (err == null) {
                setBusy("接続しました", false)
                openRemote()
            } else {
                setBusy(err, false, true)
            }
        })
    }

    private fun openRemote() {
        startActivity(Intent(this, RemoteActivity::class.java))
        finish()
    }

    // ---- QRコード ----

    private fun scanQr() {
        try {
            val opts = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(this, opts).startScan()
                .addOnSuccessListener { b -> onQrText(b.rawValue ?: "") }
                .addOnFailureListener { e ->
                    setBusy(
                        "QRコードを読み取れませんでした（${e.message ?: e.javaClass.simpleName}）。" +
                            "下の一覧から選ぶか、IPアドレスを手入力してください。",
                        false, true,
                    )
                }
        } catch (e: Throwable) {
            setBusy("QRコードの読み取りを開始できませんでした（${e.message ?: e.javaClass.simpleName}）", false, true)
        }
    }

    private fun onQrText(text: String) {
        if (isFinishing || isDestroyed) return
        val t = parseQr(text)
        if (t == null) {
            setBusy("PhonePadのQRコードではありません", false, true)
            return
        }
        connectTo(t)
    }

    /** 前回アプリが強制終了していたら、その原因を表示する */
    private fun showLastCrash() {
        val text = PhonePadApp.takeCrashLog(this) ?: return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("前回、アプリが強制終了しました")
            .setMessage("「コピー」を押して、Claude に貼り付けて伝えてください。\n\n" + text.take(1500))
            .setPositiveButton("コピー") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("PhonePad crash", text))
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    /** "PHONEPAD|port|code|ip1,ip2|PC名" */
    private fun parseQr(s: String): Target? {
        val f = s.split("|")
        if (f.size < 4 || f[0] != "PHONEPAD") return null
        val port = f[1].toIntOrNull() ?: return null
        val hosts = f[3].split(",").filter { it.isNotBlank() }
        if (hosts.isEmpty()) return null
        return Target(hosts, port, f[2], f.getOrElse(4) { "PC" }, fp = f.getOrElse(5) { "" })
    }

    // ---- 自動検出 ----

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        thread(name = "discovery", isDaemon = true) {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.broadcast = true
                sock.soTimeout = 400
                val ping = "PHONEPAD?".toByteArray()
                val bcast = InetAddress.getByName("255.255.255.255")
                var last = 0L
                val buf = ByteArray(512)
                while (discovering) {
                    val now = System.currentTimeMillis()
                    if (now - last > 2500) {
                        last = now
                        try {
                            sock.send(DatagramPacket(ping, ping.size, bcast, 47801))
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        val pk = DatagramPacket(buf, buf.size)
                        sock.receive(pk)
                        val msg = String(pk.data, 0, pk.length, Charsets.UTF_8)
                        val f = msg.split("|")
                        if (f.size >= 3 && f[0] == "PHONEPAD!") {
                            val ip = pk.address.hostAddress ?: continue
                            val port = f[1].toIntOrNull() ?: 47800
                            val name = f[2]
                            runOnUiThread { addFound(ip, port, name) }
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (_: Exception) {
            } finally {
                sock?.close()
                discovering = false
            }
        }
    }

    private fun addFound(ip: String, port: Int, name: String) {
        if (found[ip] == Pair(port, name)) return
        found[ip] = Pair(port, name)
        foundList.removeAllViews()
        for ((addr, v) in found) {
            foundList.addView(KeyButton(this, "${v.second}　($addr)", 15f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpi(16), 0, dpi(16), 0)
                layoutParams = LinearLayout.LayoutParams(-1, dpi(56)).apply { bottomMargin = dpi(8) }
                onTap = { pickFound(addr, v.first, v.second) }
            })
        }
    }

    private fun pickFound(ip: String, port: Int, name: String) {
        // 前回と同じPCなら、覚えている情報 (証明書の指紋など) を引き継ぐ
        Prefs.target?.takeIf { it.name == name }?.let { prev ->
            connectTo(prev.copy(hosts = listOf(ip) + prev.hosts.filter { it != ip }, port = port))
            return
        }
        val saved = Prefs.savedCodeFor(name)
        if (saved != null) {
            connectTo(Target(listOf(ip), port, saved, name))
            return
        }
        codeDialog("$name の接続コード（6桁）を入力してください") { code ->
            connectTo(Target(listOf(ip), port, code, name))
        }
    }

    private fun codeDialog(title: String, ok: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "123456"
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(wrap(et))
            .setPositiveButton("接続") { _, _ -> ok(et.text.toString().trim()) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun manualDialog() {
        val ip = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "例: 192.168.1.10"
        }
        val code = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "接続コード（6桁）"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ip)
            addView(code)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("IPアドレスとコードを入力")
            .setView(wrap(box))
            .setPositiveButton("接続") { _, _ ->
                val host = ip.text.toString().trim()
                if (host.isNotEmpty()) connectTo(Target(listOf(host), 47800, code.text.toString().trim(), host))
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun wrap(v: View) = LinearLayout(this).apply {
        val p = dpi(20)
        setPadding(p, dpi(8), p, 0)
        addView(v, LinearLayout.LayoutParams(-1, -2))
    }
}
