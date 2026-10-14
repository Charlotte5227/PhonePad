package com.phonepad.app

import android.Manifest
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import org.json.JSONObject
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** スマホに出すお知らせ */
object Notifier {
    const val CH_PC = "pc"
    const val CH_RING = "ring"
    const val CH_FILES = "files"
    const val CH_LINK = "link"
    private var nextId = 100

    fun setup(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_PC, "PCからのお知らせ", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "PCから送られたお知らせ・ページ"
        })
        nm.createNotificationChannel(NotificationChannel(CH_FILES, "PCから届いたファイル", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_RING, "スマホを探す", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null) // 音はアプリで鳴らす
        })
        nm.createNotificationChannel(NotificationChannel(CH_LINK, "PCとの接続", NotificationManager.IMPORTANCE_LOW).apply {
            description = "アプリを閉じてもPCとつないでおくときの表示"
            setShowBadge(false)
        })
    }

    fun allowed(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun builder(ctx: Context, ch: String) = Notification.Builder(ctx, ch)
        .setSmallIcon(R.drawable.ic_notify)
        .setColor(C.ACCENT)

    fun show(ctx: Context, n: Notification, id: Int = nextId++): Int {
        if (!allowed(ctx)) return id
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
        return id
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    fun launchIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, ConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** スマホを鳴らす (マナーモードでもアラームの音量で) */
object Ringer {
    private const val NOTIFY_ID = 7
    private val h = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var oldVolume = -1
    private var dialog: AlertDialog? = null

    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun start(ctx: Context) {
        if (player != null) return
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: Exception) {
            }
        }
        val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
        try {
            vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        } catch (_: Exception) {
        }
        val stop = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.STOP_RING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notifier.builder(ctx, Notifier.CH_RING)
            .setContentTitle("PCから呼ばれています")
            .setContentText("スマホを探しているときの音です。押すと止まります")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stop)
            .addAction(Notification.Action.Builder(null, "止める", stop).build())
            .build()
        Notifier.show(ctx, n, NOTIFY_ID)
        // アプリを開いているときは、画面にも出す
        PhonePadApp.current?.let { a ->
            if (!a.isFinishing) {
                dialog = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("PCから呼ばれています")
                    .setMessage("スマホを探しているときの音です。")
                    .setPositiveButton("止める") { _, _ -> stop(ctx) }
                    .setCancelable(false)
                    .show()
            }
        }
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ stop(ctx) }, 60_000)
    }

    fun stop(ctx: Context) {
        h.removeCallbacksAndMessages(null)
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        try {
            vibrator(ctx)?.cancel()
        } catch (_: Exception) {
        }
        if (oldVolume >= 0) {
            try {
                ctx.getSystemService(AudioManager::class.java)?.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
            } catch (_: Exception) {
            }
            oldVolume = -1
        }
        Notifier.cancel(ctx, NOTIFY_ID)
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }
}

/** お知らせの「止める」など */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val STOP_RING = "com.phonepad.app.STOP_RING"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == STOP_RING) Ringer.stop(ctx)
    }
}

/**
 * PC から届くもの: お知らせ (NT)・ページ (OU)・スマホを探す (RING)・ファイル (FS/FD/FE/FX)・
 * ファイルの受け取り結果 (FK)・クリップボード (CTA)
 */
object PcActions {
    private val main = Handler(Looper.getMainLooper())
    private val ctx: Context get() = PhonePadApp.app

    private class Incoming(val uri: Uri, val out: OutputStream, val name: String, var got: Long = 0)

    private val incoming = ConcurrentHashMap<String, Incoming>()

    /** スマホ → PC のファイルの返事を待つ */
    class Ack {
        val latch = CountDownLatch(1)

        @Volatile
        var result = ""
    }

    val acks = ConcurrentHashMap<String, Ack>()

    fun handle(line: String, unb64: (String) -> String): Boolean {
        val sp = line.indexOf(' ')
        val cmd = if (sp < 0) line else line.substring(0, sp)
        val rest = if (sp < 0) "" else line.substring(sp + 1)
        when (cmd) {
            "NT" -> try {
                val o = JSONObject(unb64(rest))
                notice(o.optString("title").ifEmpty { "PCからのお知らせ" }, o.optString("text"))
            } catch (_: Exception) {
            }

            "OU" -> {
                val u = unb64(rest).trim()
                if (u.startsWith("http://") || u.startsWith("https://")) main.post { openUrl(u) }
            }

            "RING" -> main.post { if (rest.trim() == "1") Ringer.start(ctx) else Ringer.stop(ctx) }
            "FS" -> fileStart(rest, unb64)
            "FD" -> fileData(rest)
            "FE" -> fileEnd(rest.trim())
            "FX" -> fileAbort(rest.trim())
            "FK" -> {
                val f = rest.trim().split(" ")
                val a = acks[f[0]] ?: return true
                a.result = if (f.getOrNull(1) == "ok") "ok" else unb64(f.getOrElse(1) { "" }).ifEmpty { "PCで保存できませんでした" }
                a.latch.countDown()
            }

            "CTA" -> {
                val t = unb64(rest)
                main.post { ClipSync.fromPc(t) }
            }

            else -> return false
        }
        return true
    }

    fun onDisconnected() {
        for (id in incoming.keys.toList()) fileAbort(id)
        for (a in acks.values) {
            a.result = "切断されました"
            a.latch.countDown()
        }
    }

    private fun notice(title: String, text: String) {
        main.post {
            PhonePadApp.current?.let { Toast.makeText(it, "$title：$text", Toast.LENGTH_LONG).show() }
            val n = Notifier.builder(ctx, Notifier.CH_PC)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(Notifier.launchIntent(ctx))
                .build()
            Notifier.show(ctx, n)
        }
    }

    /** アプリを開いていればすぐ開き、閉じていればお知らせから開けるようにする */
    private fun openUrl(u: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a = PhonePadApp.current
        if (a != null) {
            try {
                a.startActivity(view)
                return
            } catch (_: Exception) {
            }
        }
        val pi = PendingIntent.getActivity(ctx, u.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notifier.builder(ctx, Notifier.CH_PC)
            .setContentTitle("PCから届いたページ（押すと開きます）")
            .setContentText(u)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        Notifier.show(ctx, n)
    }

    // ---------------------------------------------------------------- PC → スマホ のファイル (ダウンロード/PhonePad に保存)

    private fun fileStart(rest: String, unb64: (String) -> String) {
        val f = rest.split(" ")
        if (f.size < 2) return
        val id = f[0]
        try {
            val o = JSONObject(unb64(f[1]))
            val name = o.optString("n").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").ifBlank { "file" }
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PhonePad")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("保存場所を作れません")
            val out = ctx.contentResolver.openOutputStream(uri) ?: throw IllegalStateException("保存できません")
            incoming[id] = Incoming(uri, out, name)
            main.post { PhonePadApp.current?.let { Toast.makeText(it, "PCからファイルを受け取っています: $name", Toast.LENGTH_SHORT).show() } }
        } catch (e: Exception) {
            Link.send("FK $id " + Link.b64("スマホに保存できません: ${e.message}"))
        }
    }

    private fun fileData(rest: String) {
        val sp = rest.indexOf(' ')
        if (sp < 0) return
        val id = rest.substring(0, sp)
        val inc = incoming[id] ?: return
        try {
            val b = Base64.decode(rest.substring(sp + 1), Base64.DEFAULT)
            inc.out.write(b)
            inc.got += b.size
        } catch (e: Exception) {
            fileAbort(id)
            Link.send("FK $id " + Link.b64("スマホに保存できません: ${e.message}"))
        }
    }

    private fun fileEnd(id: String) {
        val inc = incoming.remove(id) ?: return
        try {
            inc.out.close()
            ctx.contentResolver.update(inc.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Link.send("FK $id ok")
        } catch (e: Exception) {
            Link.send("FK $id " + Link.b64("スマホに保存できません: ${e.message}"))
            return
        }
        main.post {
            val mime = ctx.contentResolver.getType(inc.uri) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(inc.uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, inc.uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notifier.builder(ctx, Notifier.CH_FILES)
                .setContentTitle("PCからファイルが届きました")
                .setContentText("${inc.name}（ダウンロード/PhonePad）")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            Notifier.show(ctx, n)
            PhonePadApp.current?.let { Toast.makeText(it, "保存しました: ダウンロード/PhonePad/${inc.name}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun fileAbort(id: String) {
        val inc = incoming.remove(id) ?: return
        try {
            inc.out.close()
        } catch (_: Exception) {
        }
        try {
            ctx.contentResolver.delete(inc.uri, null, null)
        } catch (_: Exception) {
        }
    }
}

/** クリップボードの自動共有 */
object ClipSync {
    private var lastFromPc = ""
    private var lastSent = ""
    private var lastStamp = 0L

    /** PC でコピーした文字を、スマホのクリップボードに入れる */
    fun fromPc(t: String) {
        if (!Prefs.clipAuto || t.isEmpty()) return
        lastFromPc = t
        val cm = PhonePadApp.app.getSystemService(ClipboardManager::class.java) ?: return
        try {
            cm.setPrimaryClip(ClipData.newPlainText("PC", t))
            lastStamp = cm.primaryClipDescription?.timestamp ?: lastStamp
        } catch (_: Exception) {
        }
    }

    /** スマホでコピーした文字を PC へ (アプリが画面に出ているときだけ読める) */
    fun check() {
        if (!Prefs.clipAuto || Link.state != Link.State.CONNECTED) return
        val cm = PhonePadApp.app.getSystemService(ClipboardManager::class.java) ?: return
        try {
            // 中身を読む前に、変わったかどうかを時刻で確かめる (読むたびにお知らせが出る機種があるため)
            val stamp = cm.primaryClipDescription?.timestamp ?: return
            if (stamp == lastStamp) return
            lastStamp = stamp
            val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(PhonePadApp.app)?.toString() ?: return
            if (t.isEmpty() || t == lastFromPc || t == lastSent || t.length > 60000) return
            lastSent = t
            Link.send("CB " + Link.b64(t))
        } catch (_: Exception) {
        }
    }
}
