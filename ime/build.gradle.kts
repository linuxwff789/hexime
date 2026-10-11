import java.util.Base64

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

// 签名：CI 上用 Actions secrets 里那把**固定的** release keystore（HEXIME_KEYSTORE_B64，
// base64 的 .jks）。没有这些环境变量（本地/别人 clone）就退回 debug 签名。
//
// 为什么必须固定：AGP 的 debug 签名在临时 runner 上每次都不一样 → `pm install -r` 必报
// INSTALL_FAILED_UPDATE_INCOMPATIBLE，只能先卸载；卸载会清掉用户词表(custom_phrase.txt)和设置。
// 固定后可以直接覆盖升级。
val keystoreB64: String? = System.getenv("HEXIME_KEYSTORE_B64")

android {
    namespace = "com.hexime.ime"
    compileSdk = 35
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.hexime.ime"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (!keystoreB64.isNullOrBlank()) {
            create("release") {
                val ks = File(System.getProperty("java.io.tmpdir"), "hexime-signing.jks")
                ks.writeBytes(Base64.getDecoder().decode(keystoreB64))
                storeFile = ks
                storePassword = System.getenv("HEXIME_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HEXIME_KEY_ALIAS")
                keyPassword = System.getenv("HEXIME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有固定 keystore 就用它，否则退回 debug key
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
