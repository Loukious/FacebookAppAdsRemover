plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "tn.loukious.facebookappadsremover"
    compileSdk = 36

    defaultConfig {
        applicationId = "tn.loukious.facebookappadsremover"
        minSdk = 27
        targetSdk = 35
        versionCode = 26
        versionName = "1.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // The ported hooks key their discovery caches by module version
    // (VERSION_NAME + VERSION_CODE), so the generated BuildConfig is needed.
    buildFeatures {
        buildConfig = true
        // Settings screen (ui/) is Jetpack Compose with Material 3 Expressive.
        compose = true
    }
    // Contributor snapshot for the About page (scripts/update_contributors.py).
    sourceSets["main"].assets.srcDir("contributors")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

base.archivesName.set("FacebookAppAdsRemover-v${android.defaultConfig.versionName}")

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")

    // Settings UI: Compose + Material 3 Expressive (large flexible app bar,
    // shape morphing, spring motion scheme).
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Modern Xposed API: compileOnly — provided by the framework in-process.
    compileOnly("io.github.libxposed:api:102.0.0")

    // Service library: the settings UI's write channel into the framework's
    // remote-preferences storage (Vector/LSPosed daemon DB). The hooked app
    // reads the same group read-only via XposedInterface.getRemotePreferences.
    // 101.0.0: matches the module's targetApiVersion=101; 102.0.0 would force
    // compileSdk 37.
    implementation("io.github.libxposed:service:101.0.0")

    // Runtime dex search for hook-point discovery (replaces the original mod's dexplore).
    implementation("org.luckypray:dexkit:2.2.0")

    // Downloader (role of the original mod's okhttp3/MeiOkHttp).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // MPD parse — the mod itself parses the DASH manifest with jsoup's XML
    // parser (libnc.so Rn0LbcxLisWuSI9YThk calls Jsoup.parse/Element.select/
    // Node.attr); bundling the same library keeps the port behavior-identical.
    implementation("org.jsoup:jsoup:1.17.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("javax.inject:javax.inject:1")
}
