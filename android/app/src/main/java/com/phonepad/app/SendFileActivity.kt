package com.phonepad.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ファイルを PC へ送る。
 * ・写真などの「共有」→「PhonePad」から (文字だけを共有したときは、PCのクリップボードへ)
 * ・★ →「ファイルをPCへ送る」から (ファイルを選ぶ画面を出す)
 * PC の「ダウンロード」の中の「PhonePad」フォルダに保存される。
 */
class SendFileActivity : Activity() {
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var close: KeyButton

    @Volatile
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(C.BG)
            setPadding(dpi(28), dpi(28), dpi(28), dpi(28))
        }
        title = label(this, "PCへ送る", 20f, C.INK, true)
        detail = label(this, "", 14f, C.SUB).apply { setPadding(0, dpi(10), 0, dpi(14)) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        close = KeyButton(this, "やめる", 15f).apply {
            onTap = {
                cancelled = true
                finish()
            }
        }
        root.addView(title)
        root.addView(detail)
        root.addView(bar, LinearLayout.LayoutParams(-1, dpi(24)))
        root.addView(close, LinearLayout.LayoutParams(-1, dpi(48)).apply { topMargin = dpi(24) })
        setContentView(root)

        if (savedInstanceState != null) return
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
                if (uris.isEmpty()) {
                    // 文字だけ (ページのアドレスなど) → PCのクリップボードへ
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrEmpty()) {
                        withLink { sendText(text) }
                        return
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
            }
        }
        if (uris.isEmpty()) {
            // ファイルを選ぶ画面
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(pick, 1)
            } catch (_: Exception) {
                fail("ファイルを選ぶ画面を開けませんでした")
            }
            return
        }
        withLink { sendFiles(uris) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            finish()
            return
        }
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isEmpty()) {
            finish()
            return
        }
        withLink { sendFiles(uris) }
    }

    private fun fail(msg: String) {
        title.text = "送れませんでした"
        detail.text = msg
        close.text = "閉じる"
    }

    /** PC とつながるのを少し待ってから送る */
    private fun withLink(job: () -> Unit) {
        detail.text = "PCとつないでいます…"
        thread(name = "send-file", isDaemon = true) {
            if (Link.state != Link.State.CONNECTED) {
                val t = Prefs.target
                if (t == null) {
                    runOnUiThread { fail("まだPCとつないだことがありません。アプリを開いてPCとつないでください。") }
                    return@thread
                }
                if (Link.state == Link.State.DISCONNECTED) runOnUiThread { Link.connect(t, null, persistent = true) }
                val until = System.currentTimeMillis() + 15000
                while (Link.state != Link.State.CONNECTED && System.currentTimeMillis() < until && !cancelled) Thread.sleep(100)
                if (Link.state != Link.State.CONNECTED) {
                    runOnUiThread { fail("PCが見つかりません。PCで PhonePad が起動しているか、同じWi-Fiにつながっているか確認してください。") }
                    return@thread
                }
            }
            job()
        }
    }

    private fun sendText(text: String) {
        Link.send("CB " + Link.b64(text))
        runOnUiThread {
            Toast.makeText(this, "PCのクリップボードに入れました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun sendFiles(uris: List<Uri>) {
        var ok = 0
        val errors = ArrayList<String>()
        for ((i, uri) in uris.withIndex()) {
            if (cancelled) return
            var name = "file"
            var size = -1L
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) {
            }
            val head = if (uris.size > 1) "（${i + 1}/${uris.size}）" else ""
            runOnUiThread {
                title.text = "PCへ送っています$head"
                detail.text = name
                bar.progress = 0
            }
            val id = "a" + System.currentTimeMillis().toString(36) + i
            val ack = PcActions.Ack()
            PcActions.acks[id] = ack
            try {
                Link.send("FS $id " + Link.b64(JSONObject().put("n", name).put("s", size).toString()))
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(32 * 1024)
                    var sent = 0L
                    while (!cancelled) {
                        val n = input.readNBytesCompat(buf)
                        if (n <= 0) break
                        if (!Link.sendBlocking("FD $id " + Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))) throw IllegalStateException("切断されました")
                        sent += n
                        if (size > 0) {
                            val p = (sent * 1000 / size).toInt()
                            runOnUiThread { bar.progress = p }
                        }
                    }
                } ?: throw IllegalStateException("ファイルを開けません")
                if (cancelled) {
                    Link.send("FX $id")
                    return
                }
                Link.sendBlocking("FE $id")
                if (!ack.latch.await(60, TimeUnit.SECONDS)) throw IllegalStateException("PCから返事がありません")
                if (ack.result != "ok") throw IllegalStateException(ack.result)
                ok++
            } catch (e: Exception) {
                errors.add("$name: ${e.message}")
            } finally {
                PcActions.acks.remove(id)
            }
        }
        runOnUiThread {
            bar.progress = 1000
            if (errors.isEmpty()) {
                title.text = "送りました"
                detail.text = "${ok} 個のファイルを、PCの「ダウンロード」の中の「PhonePad」フォルダに保存しました。"
                Toast.makeText(this, "PCへ送りました", Toast.LENGTH_SHORT).show()
            } else {
                title.text = if (ok > 0) "一部を送れませんでした" else "送れませんでした"
                detail.text = errors.joinToString("\n")
            }
            close.text = "閉じる"
        }
    }

    override fun onDestroy() {
        cancelled = true
        super.onDestroy()
    }
}

/** buf がいっぱいになるか終わりまで読む */
private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
    var total = 0
    while (total < buf.size) {
        val n = read(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
