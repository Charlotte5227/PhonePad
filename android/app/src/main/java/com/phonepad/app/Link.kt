package com.phonepad.app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * PCとの通信。1行ずつのテキストで送受信する。
 * 一度つながった後に切れた場合は自動で再接続し続ける。
 */
object Link {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    @Volatile
    var state = State.DISCONNECTED
        private set

    @Volatile
    var pcName = ""
        private set

    /** つながっているPCのソフトのバージョン ("" = 不明) */
    @Volatile
    var pcVersion = ""
        private set

    /** 最後に切断された理由 (表示用) */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var foreground = true

    /** PC側でコントローラーが使えなかったときの理由 */
    var onGamepadError: ((String) -> Unit)? = null

    /** PCから音量の状態 (JSON) が届いたとき */
    var onVolumeInfo: ((String) -> Unit)? = null

    /** PCで入力中の欄の状態 (JSON) */
    var onField: ((String) -> Unit)? = null

    /** PCのクリップボードの文字 */
    var onClipText: ((String) -> Unit)? = null

    /** スマホ→PCのクリップボード送信が終わった */
    var onClipSent: (() -> Unit)? = null

    /** PCからのエラー表示 */
    var onError: ((String) -> Unit)? = null

    /** ゲームの振動 (大きいモーター, 小さいモーター 0〜255) */
    var onRumble: ((Int, Int) -> Unit)? = null

    /** PCの自動起動の状態 */
    var onAutostart: ((Boolean) -> Unit)? = null

    /** PCのアプリ一覧 (JSON) */
    var onApps: ((String) -> Unit)? = null

    /** PCの状態 (JSON) */
    var onSysInfo: ((String) -> Unit)? = null

    /** 今つながっているPCのアドレス (画面表示で使う) */
    @Volatile
    var host: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State) -> Unit>()
    private val queue = LinkedBlockingQueue<String>(2000)

    @Volatile
    private var generation = 0

    @Volatile
    private var socket: Socket? = null

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    private fun setState(s: State) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun b64(s: String): String = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun unb64(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        s
    }

    /** 1行送る (未接続なら捨てる) */
    fun send(line: String) {
        if (state == State.CONNECTED) queue.offer(line)
    }

    /**
     * 接続を開始する。cb は最初の接続結果 (成功ならnull、失敗ならエラーメッセージ) を画面側スレッドで受け取る。
     * 一度成功した後に切れた場合は自動で再接続する。
     */
    /**
     * persistent=true のときは、つながるまで探し続ける
     * (PCの起動を待つ・PCのIPアドレスが変わっていたら同じWi-Fi内から探し直す)
     */
    fun connect(t0: Target, cb: ((String?) -> Unit)?, persistent: Boolean = false) {
        val gen = ++generation
        closeSocket()
        lastError = null
        thread(name = "link-$gen", isDaemon = true) {
            var everConnected = false
            var pendingCb = cb
            var t = t0
            while (gen == generation) {
                setState(State.CONNECTING)
                val r = open(t)
                if (gen != generation) {
                    r.sock?.close(); return@thread
                }
                if (r.sock == null) {
                    if (!r.fatal && (persistent || everConnected)) {
                        // 同じWi-Fi内で同じ名前のPCを探して、アドレスを更新する
                        val found = discover(t.name)
                        if (gen != generation) return@thread
                        val fresh = found.filter { it !in t.hosts }
                        if (found.isNotEmpty()) {
                            t = t.copy(hosts = found + t.hosts.filter { it !in found })
                            if (fresh.isNotEmpty()) continue
                        }
                        lastError = "PCを探しています…"
                        try {
                            Thread.sleep(if (foreground) 2000 else 6000)
                        } catch (_: InterruptedException) {
                        }
                        continue
                    }
                    if (!everConnected || r.fatal) {
                        // 初回の失敗、またはコード違い: あきらめる
                        lastError = r.error
                        setState(State.DISCONNECTED)
                        val c = pendingCb
                        pendingCb = null
                        if (c != null) main.post { c(r.error) }
                        return@thread
                    }
                    Thread.sleep(if (foreground) 1500 else 4000)
                    continue
                }
                everConnected = true
                // つながったアドレスを先頭にして保存
                val hosts = listOf(r.host!!) + t.hosts.filter { it != r.host }
                // 初めてつながったPCの証明書の指紋を覚える
                t = t.copy(hosts = hosts, name = pcName.ifEmpty { t.name }, fp = r.fp ?: t.fp)
                Prefs.target = t
                val c = pendingCb
                pendingCb = null
                if (c != null) main.post { c(null) }

                runSession(r, gen)
                if (gen != generation) return@thread
                lastError = "接続が切れました。再接続しています…"
                Thread.sleep(800)
            }
        }
    }

    /** 同じWi-Fi内のPhonePad PCを探す (名前が一致するもののアドレス) */
    fun discover(name: String, timeoutMs: Int = 900): List<String> {
        val out = ArrayList<String>()
        var sock: java.net.DatagramSocket? = null
        try {
            sock = java.net.DatagramSocket()
            sock.broadcast = true
            sock.soTimeout = 200
            val ping = "PHONEPAD?".toByteArray()
            sock.send(java.net.DatagramPacket(ping, ping.size, java.net.InetAddress.getByName("255.255.255.255"), 47801))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < end) {
                try {
                    val pk = java.net.DatagramPacket(buf, buf.size)
                    sock.receive(pk)
                    val f = String(pk.data, 0, pk.length, Charsets.UTF_8).split("|")
                    if (f.size >= 3 && f[0] == "PHONEPAD!" && f[2] == name) {
                        pk.address.hostAddress?.let { if (it !in out) out.add(it) }
                    }
                } catch (_: java.net.SocketTimeoutException) {
                }
            }
        } catch (_: Exception) {
        } finally {
            sock?.close()
        }
        return out
    }

    fun disconnect() {
        generation++
        closeSocket()
        setState(State.DISCONNECTED)
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private class OpenResult(
        val sock: Socket? = null,
        val reader: BufferedReader? = null,
        val writer: BufferedWriter? = null,
        val host: String? = null,
        val error: String? = null,
        val fatal: Boolean = false,
        val fp: String? = null,
    )

    private fun open(t: Target): OpenResult {
        val err = "PCが見つかりません。PC側のPhonePadが起動しているか、同じWi-Fiにつながっているか確認してください。"
        for (host in t.hosts) {
            var s: Socket? = null
            val trust = Tls.PinTrust(t.fp)
            try {
                val raw = Socket()
                s = raw
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(host, t.port), 2500)
                raw.soTimeout = 5000
                // 通信を暗号化する (PCの証明書の指紋を確認)
                val ssl = Tls.factory(trust).createSocket(raw, host, t.port, true) as javax.net.ssl.SSLSocket
                s = ssl
                ssl.soTimeout = 5000
                ssl.startHandshake()
                val w = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                val rd = s.getInputStream().bufferedReader(Charsets.UTF_8)
                val dev = Build.MODEL ?: "Android"
                w.write("HELLO ${t.code} ${b64(dev)} 1\n")
                w.flush()
                val line = rd.readLine() ?: throw IOException("closed")
                val f = line.split(" ")
                when {
                    f[0] == "OK" -> {
                        pcName = if (f.size > 1) unb64(f[1]) else t.name
                        pcVersion = f.getOrElse(3) { "" }
                        return OpenResult(sock = s, reader = rd, writer = w, host = host, fp = trust.seen)
                    }

                    line.startsWith("NG code") -> {
                        s.close()
                        return OpenResult(error = "接続コードが違います。PCに表示されているQRコードを読み取り直してください。", fatal = true)
                    }

                    line.startsWith("NG blocked") -> {
                        s.close()
                        return OpenResult(error = "失敗が続いたため、1分ほど待ってから試してください。", fatal = true)
                    }

                    else -> s.close()
                }
            } catch (e: Exception) {
                try {
                    s?.close()
                } catch (_: Exception) {
                }
                if (trust.mismatch) {
                    return OpenResult(
                        error = "PCの証明書が以前と違います。安全のため接続を止めました。PCの画面のQRコードを読み取り直してください。",
                        fatal = true,
                    )
                }
            }
        }
        return OpenResult(error = err)
    }

    private fun runSession(r: OpenResult, gen: Int) {
        val s = r.sock!!
        socket = s
        host = r.host
        queue.clear()
        setState(State.CONNECTED)

        val alive = AtomicBoolean(true)
        val writer = thread(name = "link-w", isDaemon = true) {
            val w = r.writer!!
            try {
                while (alive.get()) {
                    val first = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    w.write(first); w.write("\n")
                    // たまっている分はまとめて送る
                    var n = 0
                    while (n < 200) {
                        val next = queue.poll() ?: break
                        w.write(next); w.write("\n"); n++
                    }
                    w.flush()
                }
            } catch (_: Exception) {
            }
            alive.set(false)
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        val pinger = thread(name = "link-p", isDaemon = true) {
            while (alive.get()) {
                queue.offer("PING")
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            val rd = r.reader!!
            while (alive.get() && gen == generation) {
                val line = rd.readLine() ?: break
                when {
                    line == "PONG" -> {}
                    line.startsWith("MI ") -> {
                        // 電源を入れるためのアドレスを覚える
                        val mac = line.removePrefix("MI ").trim()
                        Prefs.target?.let { if (it.mac != mac) Prefs.target = it.copy(mac = mac) }
                    }

                    line.startsWith("AP ") -> {
                        val json = unb64(line.removePrefix("AP ").trim())
                        main.post { onApps?.invoke(json) }
                    }

                    line.startsWith("SI ") -> {
                        val json = unb64(line.removePrefix("SI ").trim())
                        main.post { onSysInfo?.invoke(json) }
                    }

                    line.startsWith("TF ") -> {
                        val json = unb64(line.removePrefix("TF ").trim())
                        main.post { onField?.invoke(json) }
                    }

                    line.startsWith("CT") -> {
                        val txt = unb64(line.removePrefix("CT").trim())
                        main.post { onClipText?.invoke(txt) }
                    }

                    line == "CK" -> main.post { onClipSent?.invoke() }

                    line.startsWith("ER ") -> {
                        val msg = unb64(line.removePrefix("ER ").trim())
                        main.post { onError?.invoke(msg) }
                    }

                    line.startsWith("RU ") -> {
                        val f = line.split(" ")
                        val l = f.getOrNull(1)?.toIntOrNull() ?: 0
                        val sm = f.getOrNull(2)?.toIntOrNull() ?: 0
                        main.post { onRumble?.invoke(l, sm) }
                    }

                    line.startsWith("AI ") -> {
                        val on = line.removePrefix("AI ").trim() == "1"
                        main.post { onAutostart?.invoke(on) }
                    }

                    line.startsWith("VI ") -> {
                        val json = unb64(line.removePrefix("VI ").trim())
                        main.post { onVolumeInfo?.invoke(json) }
                    }

                    line.startsWith("GP ERR") -> {
                        val msg = unb64(line.removePrefix("GP ERR").trim())
                        main.post { onGamepadError?.invoke(msg) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        alive.set(false)
        try {
            s.close()
        } catch (_: Exception) {
        }
        writer.interrupt()
        pinger.interrupt()
        if (socket === s) socket = null
        if (gen == generation) setState(State.CONNECTING)
    }
}
