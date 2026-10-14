package com.phonepad.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * アプリを閉じても PC とつないでおく (PC からのお知らせ・ファイル・スマホを探すを受け取るため)。
 * 設定でオンにしたときだけ動き、お知らせ欄に「PCとつながっています」と出す。
 */
class LinkService : Service() {
    companion object {
        private const val ID = 3

        fun update(ctx: Context) {
            val i = Intent(ctx, LinkService::class.java)
            if (Prefs.keepAlive && Prefs.target != null) {
                try {
                    ctx.startForegroundService(i)
                } catch (_: Exception) {
                    // 画面に出ていないときは始められないことがある (次に開いたときに始める)
                }
            } else {
                ctx.stopService(i)
            }
        }
    }

    private val listener: (Link.State) -> Unit = { st -> show(st) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        show(Link.state, first = true)
        Link.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.keepAlive) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Link.state == Link.State.DISCONNECTED) Prefs.target?.let { Link.connect(it, null, persistent = true) }
        return START_STICKY
    }

    private fun show(st: Link.State, first: Boolean = false) {
        val text = when (st) {
            Link.State.CONNECTED -> "${Link.pcName} とつながっています"
            Link.State.CONNECTING -> "PCを探しています"
            Link.State.DISCONNECTED -> "PCとつながっていません"
        }
        val n = Notifier.builder(this, Notifier.CH_LINK)
            .setContentTitle("PhonePad")
            .setContentText("$text（PCからのお知らせ・ファイルを受け取ります）")
            .setOngoing(true)
            .setContentIntent(Notifier.launchIntent(this))
            .build()
        if (first) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else Notifier.show(this, n, ID)
    }

    override fun onDestroy() {
        Link.removeListener(listener)
        super.onDestroy()
    }
}
