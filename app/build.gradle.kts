import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val githubUpdateRepo: String = localProps.getProperty("GITHUB_UPDATE_REPO", "Rowan-rh/moji-ai").trim()
val githubUpdateToken: String = localProps.getProperty("GITHUB_UPDATE_TOKEN", "").trim()
fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

// 正式签名：从 local.properties 或同名环境变量读取，证书与密码绝不提交进仓库。
// 四项齐全时 release 包使用该证书签名；缺任一项时 release 包不签名，scripts/release.sh 会拒绝发布。
fun releaseSigningValue(key: String): String =
    (localProps.getProperty(key) ?: System.getenv(key)).orEmpty().trim()
val releaseStoreFile: String = releaseSigningValue("MOJI_RELEASE_STORE_FILE")
val releaseStorePassword: String = releaseSigningValue("MOJI_RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String = releaseSigningValue("MOJI_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String = releaseSigningValue("MOJI_RELEASE_KEY_PASSWORD")
val hasReleaseSigning: Boolean = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it.isNotEmpty() }

android {
    namespace = "com.moji.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moji.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("Boolean", "IS_QA_BUILD", "false")
        buildConfigField("String", "GITHUB_UPDATE_REPO", "\"${escapeForBuildConfig(githubUpdateRepo)}\"")
        buildConfigField("String", "GITHUB_UPDATE_TOKEN", "\"${escapeForBuildConfig(githubUpdateToken)}\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("debug")
            buildConfigField("Boolean", "IS_QA_BUILD", "true")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Room Local Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager for Periodic/Recurring Transactions
    implementation(libs.androidx.work.runtime.ktx)

    // Network & JSON Serialization for AI Providers (OpenAI-compatible & Gemini)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Bundled Offline Chinese OCR for Android 11+ Accessibility takeScreenshot fallback
    implementation(libs.mlkit.text.recognition.chinese)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
