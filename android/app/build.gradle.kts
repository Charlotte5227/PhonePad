plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonepad.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonepad.app"
        minSdk = 30
        targetSdk = 35
        // 公開版は GitHub の自動ビルドで -Pvn=1.2.3 -Pvc=10203 が渡される
        versionName = (findProperty("vn") as String?) ?: "1.4.0"
        versionCode = (findProperty("vc") as String?)?.toInt() ?: 10400
    }

    // 署名: 公開版は GitHub の秘密情報に置いた鍵、手元のビルドはこのPCのデバッグ用の鍵
    // 鍵の場所: 環境変数 PHONEPAD_KEYSTORE、なければ android フォルダの PhonePad-release.jks
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() }
    val ksFile = env("PHONEPAD_KEYSTORE")?.let { file(it) } ?: rootProject.file("PhonePad-release.jks")
    val ksPass = env("PHONEPAD_KEYSTORE_PASSWORD")?.trim()
        ?: rootProject.file("PhonePad-release.password").takeIf { it.exists() }?.readText()?.trim()
    signingConfigs {
        if (ksFile.exists() && ksPass != null) {
            create("release") {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = env("PHONEPAD_KEY_ALIAS") ?: "phonepad"
                keyPassword = ksPass
            }
        }
    }

    // 対応している一番古い Android (minSdk) で使えない機能を使ったら、組み立てを失敗させる
    lint {
        fatal += "NewApi"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // QRコード読み取り (Google Play開発者サービスのスキャナー。カメラ権限が不要)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
