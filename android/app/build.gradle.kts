plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val feedUrl = providers.gradleProperty("sunnyside.feedUrl").get()
// Where feedback is sent (a form service that accepts JSON, e.g. Formspree). CI passes the
// FEEDBACK_URL Actions variable; when empty, feedback opens a pre-filled GitHub issue.
val feedbackUrl = providers.environmentVariable("SUNNYSIDE_FEEDBACK_URL")
    .orElse(providers.gradleProperty("sunnyside.feedbackUrl")).getOrElse("")
val repo = providers.gradleProperty("sunnyside.repo").get()
// Google Play needs a higher versionCode for every upload. CI passes one derived from the
// time (minutes since 1970), so builds always increase; local builds use 5.
val appVersionCode = providers.environmentVariable("SUNNYSIDE_VERSION_CODE").map { it.toInt() }.getOrElse(5)

android {
    namespace = "app.sunnyside.news"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sunnyside.news"
        minSdk = 26
        // Google Play requires new apps and updates to target Android 16 (API 36) from 31 Aug 2026.
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.5.0"
        buildConfigField("String", "FEED_URL", "\"$feedUrl\"")
        buildConfigField("String", "FEEDBACK_URL", "\"$feedbackUrl\"")
        buildConfigField("String", "REPO", "\"$repo\"")
    }

    // Release signing comes from environment variables (see .github/workflows/ci.yml). Without them the
    // release APK is signed with the debug key, which is fine for trying it out but changes per machine.
    val keystore = System.getenv("SUNNYSIDE_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SUNNYSIDE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SUNNYSIDE_KEY_ALIAS")
                keyPassword = System.getenv("SUNNYSIDE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
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
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
