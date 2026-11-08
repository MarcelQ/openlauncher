plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Short git sha baked into BuildConfig.GIT_SHA so the in-app updater can tell two
 *  builds with the same versionCode apart. Falls back to "unknown" off a git tree. */
fun gitShortSha(): String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(File(projectDir.absolutePath))
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (p.waitFor() == 0 && out.matches(Regex("[0-9a-fA-F]{7,40}"))) out else "unknown"
}.getOrDefault("unknown")

android {
    namespace  = "com.openlauncher.app"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId  = "com.openlauncher.app"
        minSdk         = 21
        targetSdk      = 36
        versionCode    = 6
        versionName    = "0.0.5"
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
    }

    // Shared debug signing key. The SAME keystore is used by CI (decoded from the
    // DEBUG_KEYSTORE_B64 Actions secret into keystore/debug.keystore by build-apk.yml)
    // and local builds — Android refuses to update an APK across different signatures,
    // so this is what makes the in-app updater + rolling releases actually installable
    // on top of each other. Debug-only: release signing is unchanged. Regenerate (and
    // update the Actions secret) with:
    //   keytool -genkeypair -keystore keystore/debug.keystore -alias androiddebugkey \
    //     -storepass android -keypass android -keyalg RSA -keysize 2048 \
    //     -sigalg SHA256withRSA -validity 10950 -dname "CN=Android Debug, O=Android, C=US"
    val sharedDebugKeystore = rootProject.file("keystore/debug.keystore")
    if (sharedDebugKeystore.exists()) {
        signingConfigs {
            create("sharedDebug") {
                storeFile     = sharedDebugKeystore
                storePassword = "android"
                keyAlias      = "androiddebugkey"
                keyPassword   = "android"
            }
        }
    }

    buildTypes {
        debug {
            // Default signing config for normal device testing (restores app visibility)
            if (sharedDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("sharedDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.VERSION_CODE/VERSION_NAME/GIT_SHA for the updater
    }
}

// CI embeds this line into the rolling-release NOTES so the in-app updater can
// compare remote versionCode/commit against its own BuildConfig (UpdateChecker.kt).
val verCode = android.defaultConfig.versionCode ?: 0
val verName = android.defaultConfig.versionName ?: "0"
tasks.register("printVersionInfo") {
    description = "prints `versionCode=N versionName=X` for the release-notes marker"
    doLast { println("versionCode=$verCode versionName=$verName") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network — weather
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-gson:2.12.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // JSON serialization
    implementation("com.google.code.gson:gson:2.13.1")

    // USB host serial for the TPMS receiver (FTDI / CH34x / CP210x / CDC-ACM). MIT licensed.
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // Unit tests — first test source set in this project (protocol decoder only, no device needed)
    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
