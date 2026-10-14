package com.phonepad.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.File

class PhonePadApp : Application() {
    companion object {
        lateinit var app: PhonePadApp
            private set

        /** いま前に出ている画面 (なければ null) */
        @Volatile
        var current: Activity? = null
            private set

        /** 保存された強制終了の記録を取り出して消す */
        fun takeCrashLog(ctx: Context): String? {
            val f = File(ctx.filesDir, "crash.txt")
            if (!f.exists()) return null
            return try {
                f.readText().also { f.delete() }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private var started = 0

    // スマホでコピーしたら PC へ (クリップボードの自動共有。アプリが前に出ている間だけ読める)
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener { ClipSync.check() }
    private val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) ClipSync.check() }

    override fun onCreate() {
        super.onCreate()
        app = this
        Prefs.init(this)
        Notifier.setup(this)

        // 強制終了したときは原因をファイルに残し、次回起動時に表示する
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.txt").writeText(
                    "PhonePad ${packageManager.getPackageInfo(packageName, 0).versionName} / " +
                        "Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MODEL}\n" +
                        "thread=${t.name}\n" + e.stackTraceToString(),
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
        Haptics.init(this)

        // PCとつながったら、PCのソフトの更新も確認する
        Link.addListener { st -> if (st == Link.State.CONNECTED) Updater.onConnected() }

        // アプリが画面から消えたら、押しっぱなしの入力をPC側で全部離す
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                Link.foreground = true
                if (started == 1) {
                    getSystemService(android.content.ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipListener)
                    // アプリを開いたら、閉じても PC とつないでおく準備をする (設定でオンのとき)
                    LinkService.update(this@PhonePadApp)
                }
                if (started == 1 && Prefs.pcAudio) PcAudio.start()
            }

            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    Link.foreground = false
                    getSystemService(android.content.ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
                    Link.send("RA")
                    PcAudio.stop()
                    Updater.onAppBackground()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            // 開いたときに新しいバージョンがあれば知らせる
            override fun onActivityResumed(a: Activity) {
                current = a
                a.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener(focusListener)
                Updater.onResumed(a)
            }

            override fun onActivityPaused(a: Activity) {
                if (current === a) current = null
                a.window?.decorView?.viewTreeObserver?.removeOnWindowFocusChangeListener(focusListener)
                Updater.onPaused(a)
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
