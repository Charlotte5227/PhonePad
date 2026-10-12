package com.phonepad.app

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** 新しいバージョンの確認と更新 (GitHub の公開リリース) */
object Updater {
    const val API = "https://api.github.com/repos/Charlotte5227/PhonePad/releases/latest"
    const val SITE = "https://charlotte5227.github.io/PhonePad/"
    private const val APK_NAME = "PhonePad.apk"

    /** このアプリを開いている間に、自動の確認を済ませたか */
    private var checkedThisRun = false

    data class Release(val version: String, val notes: String, val apkUrl: String?)

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

    fun isNewer(a: String, b: String): Boolean {
        fun parse(v: String) = v.trim().removePrefix("v").split(".").map { p -> p.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun fetchLatest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "PhonePad-Android")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            var apk: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == APK_NAME) apk = a.optString("browser_download_url")
                }
            }
            Release(o.optString("tag_name").removePrefix("v"), o.optString("body"), apk)
        } catch (_: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** 起動時の自動確認 (1回だけ)。manual=true は設定画面の「更新を確認」 */
    fun check(act: Activity, manual: Boolean) {
        if (!manual && checkedThisRun) return
        checkedThisRun = true
        thread(isDaemon = true) {
            val r = fetchLatest()
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (r == null) {
                    if (manual) toast(act, "更新を確認できませんでした（インターネットにつながっているか確認してください）")
                    return@runOnUiThread
                }
                val appNew = isNewer(r.version, currentVersion(act))
                val pcVer = Link.pcVersion
                val pcNew = pcVer.isNotEmpty() && Link.state == Link.State.CONNECTED && isNewer(r.version, pcVer)
                if (!appNew && !pcNew) {
                    if (manual) toast(act, "最新のバージョンです（v${currentVersion(act)}）")
                    return@runOnUiThread
                }
                showDialog(act, r, appNew, pcNew)
            }
        }
    }

    private fun showDialog(act: Activity, r: Release, appNew: Boolean, pcNew: Boolean) {
        val what = when {
            appNew && pcNew -> "スマホのアプリとPCのソフトの両方"
            appNew -> "スマホのアプリ"
            else -> "PCのソフト"
        }
        val notes = r.notes.trim().let { if (it.length > 1200) it.take(1200) + "…" else it }
        val b = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("新しいバージョン v${r.version}")
            .setMessage("${what}に更新があります。\n\n【変更内容】\n${notes.ifEmpty { "（記載なし）" }}")
            .setNegativeButton("あとで", null)
            .setNeutralButton("サイトで見る") { _, _ ->
                act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE)))
            }
        b.setPositiveButton(if (appNew && pcNew) "両方を更新" else "更新する") { _, _ ->
            if (pcNew) {
                Link.send("UP")
                toast(act, "PCのソフトを更新しています（自動で再起動します）")
            }
            if (appNew) {
                val url = r.apkUrl
                if (url == null) toast(act, "更新用のファイルが見つかりません") else downloadAndInstall(act, url)
            }
        }
        b.show()
    }

    private fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

    private fun downloadAndInstall(act: Activity, url: String) {
        // 「提供元不明のアプリ」の許可がまだなら、先に設定を開く
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("インストールの許可")
                .setMessage("アプリを更新するには、PhonePad に「不明なアプリのインストール」を許可してください。許可したら、もう一度「更新する」を押してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    act.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")),
                    )
                    checkedThisRun = false
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val box = LinearLayout(act).apply {
            setPadding(act.dpi(24), act.dpi(16), act.dpi(24), act.dpi(8))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("ダウンロードしています…")
            .setView(box)
            .setCancelable(false)
            .show()
        thread(isDaemon = true) {
            val file = File(act.cacheDir, "update.apk")
            val ok = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                val total = c.contentLengthLong
                c.inputStream.use { ins ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                act.runOnUiThread { bar.progress = p }
                            }
                        }
                    }
                }
                c.disconnect()
                file.length() > 100_000
            } catch (_: Exception) {
                false
            }
            act.runOnUiThread {
                dlg.dismiss()
                if (!ok) {
                    toast(act, "ダウンロードに失敗しました")
                } else {
                    install(act, file)
                }
            }
        }
    }

    private fun install(ctx: Context, file: File) {
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("PhonePad", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val intent = Intent(ctx, InstallReceiver::class.java)
                val pending = PendingIntent.getBroadcast(
                    ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            toast(ctx, "インストールを開始できません: ${e.message}")
        }
    }
}

/** インストールの進み具合を受け取る (確認画面を出す) */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(confirm)
                } catch (_: Exception) {
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "更新できませんでした $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
