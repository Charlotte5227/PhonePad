package com.phonepad.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** 通信の暗号化: PCの証明書の指紋を確認する (初回は覚える) */
object Tls {
    fun fingerprint(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    class PinTrust(private val expected: String) : X509TrustManager {
        @Volatile
        var seen: String? = null

        @Volatile
        var mismatch = false

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val c = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val f = fingerprint(c)
            seen = f
            if (expected.isNotEmpty() && !f.equals(expected, ignoreCase = true)) {
                mismatch = true
                throw CertificateException("PCの証明書が以前と違います")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("not used")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun factory(tm: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory
    }
}

/** スマホからPCの電源を入れる (Wake on LAN のマジックパケット) */
object Wol {
    fun parseMac(mac: String): ByteArray? {
        val parts = mac.split(":", "-")
        if (parts.size != 6) return null
        return try {
            ByteArray(6) { parts[it].toInt(16).toByte() }
        } catch (_: Exception) {
            null
        }
    }

    fun wake(mac: String, lastHost: String?) {
        val m = parseMac(mac) ?: return
        val pk = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) pk[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(m, 0, pk, 6 + i * 6, 6)
        thread(isDaemon = true) {
            val targets = ArrayList<String>()
            targets.add("255.255.255.255")
            // 前回のPCのアドレスから、同じネットワークの一斉送信アドレスを作る (例: 192.168.1.255)
            lastHost?.split(".")?.takeIf { it.size == 4 }?.let { targets.add("${it[0]}.${it[1]}.${it[2]}.255") }
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(3) {
                        for (t in targets) for (port in intArrayOf(9, 7)) {
                            try {
                                s.send(DatagramPacket(pk, pk.size, InetAddress.getByName(t), port))
                            } catch (_: Exception) {
                            }
                        }
                        Thread.sleep(150)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
