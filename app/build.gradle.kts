import java.util.Base64
import java.util.Calendar
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "xa.refile"
    compileSdk = 35

    defaultConfig {
        applicationId = "xa.refile"
        minSdk = 26
        targetSdk = 35
        // 版本号格式: versionName=YY.M.D，versionCode=YYMMDDHHt（9 位，t=十分钟位 0-5）
        // 年份取后两位，使用中国时区(Asia/Shanghai)
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val now = Calendar.getInstance(tz)
        val year = now.get(Calendar.YEAR) % 100
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val tenMin = now.get(Calendar.MINUTE) / 10 // 0-5，每 10 分钟递增一位
        // 2026 年最大 261231235 ≈ 2.6e8，远低于 int32 上限(2.1e9)
        versionCode = year * 10000000 + month * 100000 + day * 1000 + hour * 10 + tenMin
        versionName = "$year.$month.$day"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Room schema 导出到 app/schemas，提交到 VCS 便于迁移测试与 CI 校验
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        // Release keystore 通过环境变量注入（不保留在源码中）：
        //   KEYSTORE_BASE64  → Base64 编码的 keystore 文件（CI 注入，解码到临时文件）
        //   KEYSTORE_PASSWORD
        //   KEY_ALIAS
        //   KEY_PASSWORD
        // 本地开发可用 keystore.properties 覆盖：
        //   storeFile=/path/to/keystore
        //   storePassword=***
        //   keyAlias=***
        //   keyPassword=***
        val keystoreProperties = Properties()
        val keystoreFile = rootProject.file("keystore.properties")
        if (keystoreFile.exists()) {
            keystoreProperties.load(keystoreFile.inputStream())
        }

        // Base64 keystore 注入：解码到临时文件，构建结束后由 Gradle 自动清理
        val decodedKeystore: File? = System.getenv("KEYSTORE_BASE64")?.let { b64 ->
            val tmp = File.createTempFile("refile-keystore-", ".jks")
            tmp.deleteOnExit()
            tmp.writeBytes(Base64.getDecoder().decode(b64))
            tmp
        }

        create("release") {
            storeFile = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                ?: decodedKeystore
                ?: file("unset.keystore")
            storePassword = keystoreProperties.getProperty("storePassword")
                ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = keystoreProperties.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS") ?: ""
            keyPassword = keystoreProperties.getProperty("keyPassword")
                ?: System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    // 仅 release 构建时校验签名配置（避免 :core:test 等非 release 任务在配置阶段报错）
    gradle.taskGraph.whenReady {
        if (hasTask(":app:assembleRelease") || hasTask(":app:bundleRelease")) {
            val cfg = signingConfigs.getByName("release")
            check(cfg.storeFile?.exists() == true) {
                "Release keystore not found at ${cfg.storeFile}. " +
                    "Set KEYSTORE_BASE64 env var or storeFile in keystore.properties."
            }
            check(!cfg.storePassword.isNullOrBlank()) {
                "Release keystore password not set. " +
                    "Set KEYSTORE_PASSWORD env var or storePassword in keystore.properties."
            }
            check(!cfg.keyAlias.isNullOrBlank()) {
                "Release key alias not set. " +
                    "Set KEY_ALIAS env var or keyAlias in keystore.properties."
            }
            check(!cfg.keyPassword.isNullOrBlank()) {
                "Release key password not set. " +
                    "Set KEY_PASSWORD env var or keyPassword in keystore.properties."
            }
        }
    }

    fun hasTask(name: String): Boolean =
        gradle.taskGraph.allTasks.any { it.path == name || it.name == name }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // debug 不混淆以保持编译速度
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // Workaround: NonNullableMutableLiveDataDetector crashes with IncompatibleClassChangeError
        // on current Kotlin/AGP combination (b/382758614). Safe to disable — project does not use LiveData.
        disable += "NullSafeMutableLiveData"
    }

    packaging {
        // 跳过 native .so 的 debug symbol strip，避免对预编译 AndroidX 库的 strip 警告。
        // 项目不依赖 native crash symbolication，strip 仅节省约 1-2 秒，收益可忽略。
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }
}

dependencies {
    implementation(project(":core")) {
        // Android 框架已提供 org.xmlpull.v1.XmlPullParser，排除 dav4jvm 传递依赖的
        // xpp3 / xmlpull，避免 R8 报 "Library class implements program class" 错误。
        exclude(group = "org.ogce", module = "xpp3")
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "net.sf.kxml", module = "kxml2")
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    parallel = true
}
