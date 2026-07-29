import java.util.Calendar
import java.util.Properties
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "xa.refile"
    compileSdk = 34

    defaultConfig {
        applicationId = "xa.refile"
        minSdk = 26
        targetSdk = 34
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
    }

    signingConfigs {
        // B6: Release keystore 凭据不再明文硬编码。CI 默认用仓库内 keystore（明文回退值
        // 保证 CI 不破）；本地可用 rootProject 目录下的 keystore.properties 覆盖：
        //   storeFile=/path/to/keystore
        //   storePassword=***
        //   keyAlias=***
        //   keyPassword=***
        // （keystore.properties 应加入 .gitignore，不要提交。）
        val keystoreProperties = Properties()
        val keystoreFile = rootProject.file("keystore.properties")
        if (keystoreFile.exists()) {
            keystoreProperties.load(keystoreFile.inputStream())
        }
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "${rootProject.projectDir}/app/keystore/refile.keystore"))
            storePassword = keystoreProperties.getProperty("storePassword", "refile123")
            keyAlias = keystoreProperties.getProperty("keyAlias", "refile")
            keyPassword = keystoreProperties.getProperty("keyPassword", "refile123")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    kapt(libs.hilt.work.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

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
