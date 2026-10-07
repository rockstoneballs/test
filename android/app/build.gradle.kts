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

// Accounts, ads and the ad-free subscription. CI passes these from the repository's Actions
// variables (see android/play/MONETISATION.md). Without the Firebase ones, sign-in is hidden; without
// the AdMob ones, release builds show no ads and debug builds show Google's test ads.
fun setting(name: String, default: String = ""): String =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() } ?: default
val firebaseProjectId = setting("FIREBASE_PROJECT_ID")
val firebaseAppId = setting("FIREBASE_ANDROID_APP_ID")
val firebaseApiKey = setting("FIREBASE_API_KEY")
val firebaseWebClientId = setting("FIREBASE_WEB_CLIENT_ID")
val admobConfigured = setting("ADMOB_APP_ID").isNotBlank() && setting("ADMOB_FEED_AD_UNIT").isNotBlank()
val admobAppId = setting("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")
val admobFeedUnit = setting("ADMOB_FEED_AD_UNIT", "ca-app-pub-3940256099942544/9214589741")

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
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"$firebaseProjectId\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$firebaseAppId\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"$firebaseApiKey\"")
        buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"$firebaseWebClientId\"")
        buildConfigField("String", "ADMOB_FEED_AD_UNIT", "\"$admobFeedUnit\"")
        manifestPlaceholders["admobAppId"] = admobAppId
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
        debug {
            buildConfigField("boolean", "ADS_ENABLED", "true") // Google's test ads until real ids are set
        }
        release {
            // Releases show ads only once AdMob is set up: no "Test Ad" banners for real readers.
            buildConfigField("boolean", "ADS_ENABLED", "$admobConfigured")
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
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.functions)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.billing)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
