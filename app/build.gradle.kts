plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tn.loukious.facebookappadsremover"
    compileSdk = 36

    defaultConfig {
        applicationId = "tn.loukious.facebookappadsremover"
        minSdk = 27
        targetSdk = 35
        versionCode = 22
        versionName = "1.21"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
        // libxposed:service is published with newer Kotlin metadata than our
        // 1.9 compiler; we only call its simple public API.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

base.archivesName.set("FacebookAppAdsRemover-v${android.defaultConfig.versionName}")

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")

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
}
