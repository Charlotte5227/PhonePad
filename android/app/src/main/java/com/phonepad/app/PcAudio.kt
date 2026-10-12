package com.phonepad.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** PCで鳴っている音をスマホで再生する */
object PcAudio {
    @Volatile
    private var running = false

    /** start/stop のたびに増やす (古い再生スレッドを確実に止めるため) */
    @Volatile
    private var gen = 0

    @Volatile
    private var socket: Socket? = null

    @Volatile
    var status = "停止中"
        private set

    var onStatus: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    private fun setStatus(s: String) {
        status = s
        main.post { onStatus?.invoke(s) }
    }

    fun start() {
        if (running) return
        running = true
        val g = ++gen
        thread(name = "pc-audio", isDaemon = true) {
            while (running && gen == g) {
                try {
                    play(g)
                } catch (_: Exception) {
                }
                if (!running || gen != g) break
                setStatus("PCを待っています…")
                try {
                    Thread.sleep(1500)
                } catch (_: InterruptedException) {
                }
            }
            if (gen == g) setStatus("停止中")
        }
    }

    fun stop() {
        gen++
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun readLine(ins: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) throw java.io.IOException("closed")
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
            if (sb.length > 200) throw java.io.IOException("bad header")
        }
    }

    private fun play(g: Int) {
        val t = Prefs.target ?: return
        val host = Link.host ?: t.hosts.firstOrNull() ?: return
        setStatus("接続しています…")
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, 47805), 3000)
        val ssl = Tls.factory(Tls.PinTrust(t.fp)).createSocket(raw, host, 47805, true) as SSLSocket
        socket = ssl
        var track: AudioTrack? = null
        try {
            ssl.soTimeout = 8000
            ssl.startHandshake()
            // m を付けると、聞いている間はPCのスピーカーから音を出さない
            val opt = if (Prefs.pcAudioMutePc) " m" else ""
            ssl.outputStream.write("AUDIO ${t.code}$opt\n".toByteArray())
            ssl.outputStream.flush()
            val ins = ssl.inputStream
            val head = readLine(ins).split(" ")
            if (head.firstOrNull() != "OK") {
                setStatus("PCの音を取得できません: " + head.drop(1).joinToString(" "))
                Thread.sleep(3000)
                return
            }
            val rate = head.getOrNull(1)?.toIntOrNull() ?: 48000
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, rate * 4 * 60 / 1000) // 約60ms
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track.play()
            setStatus("再生中")
            ssl.soTimeout = 0
            val buf = ByteArray(rate * 4 / 50) // 20ms
            var carry = 0
            val maxBacklog = rate * 4 / 4 // 250ms 以上たまったら捨てて追いつく
            while (running && gen == g) {
                val n = ins.read(buf, carry, buf.size - carry)
                if (n < 0) break
                val total = carry + n
                val whole = total - total % 4
                if (ins.available() > maxBacklog) {
                    // 遅れがたまっている: 今の分は捨てる
                    carry = 0
                    continue
                }
                if (whole > 0) track.write(buf, 0, whole)
                carry = total - whole
                if (carry > 0) System.arraycopy(buf, whole, buf, 0, carry)
            }
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
            try {
                ssl.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }
}
