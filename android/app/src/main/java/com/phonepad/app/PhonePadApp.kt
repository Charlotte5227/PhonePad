package com.phonepad.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.io.File

class PhonePadApp : Application() {
    companion object {
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

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Haptics.init(this)

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

        // アプリが画面から消えたら、押しっぱなしの入力をPC側で全部離す
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                Link.foreground = true
                if (started == 1 && Prefs.pcAudio) PcAudio.start()
            }

            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    Link.foreground = false
                    Link.send("RA")
                    PcAudio.stop()
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
