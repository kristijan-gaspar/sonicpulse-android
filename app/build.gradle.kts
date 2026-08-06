import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

/** Escapes a raw string into a valid Kotlin/Java string literal (including the surrounding quotes). */
fun String.toKotlinStringLiteral(): String {
    val escaped = buildString {
        this@toKotlinStringLiteral.forEach { c ->
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\b' -> append("\\b")
                c.code == 0x0C -> append("\\f")
                else -> append(c)
            }
        }
    }
    return "\"$escaped\""
}

val apiBaseUrl = localProperties.getProperty("API_BASE_URL", "https://example.invalid/")
require(apiBaseUrl.startsWith("http://") || apiBaseUrl.startsWith("https://")) {
    "API_BASE_URL must start with http:// or https:// (was: $apiBaseUrl)"
}
require(apiBaseUrl.endsWith("/")) {
    "API_BASE_URL must end with a trailing slash (was: $apiBaseUrl)"
}
val apiKey = localProperties.getProperty("API_KEY", "")

android {
    namespace = "hr.sonicpulse.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "hr.sonicpulse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "API_BASE_URL", apiBaseUrl.toKotlinStringLiteral())
        buildConfigField("String", "API_KEY", apiKey.toKotlinStringLiteral())
        // Testing-only session-log export (see hr.sonicpulse.app.observability): on by default so
        // every non-release build type (debug, and any future QA type) gets it without repeating
        // the field; release turns it off explicitly below — the single place this is decided.
        buildConfigField("boolean", "ENABLE_SESSION_LOGGING", "true")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            buildConfigField("boolean", "ENABLE_SESSION_LOGGING", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // JVM unit tests have no Robolectric shadow for android.* platform calls — a handful
            // of classes call android.util.Log for best-effort diagnostics (e.g.
            // DefaultPermissionRequestHistory, SettingsViewModel) and would otherwise throw
            // "not mocked" on every such call. Returning defaults (a no-op for Log) is the
            // standard AGP option for exactly this, without pulling in Robolectric.
            isReturnDefaultValues = true
        }
    }
}

dependencies {

    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    implementation(libs.play.services.location)

    implementation(libs.maplibre.compose)
    implementation(libs.maplibre.compose.gms)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    // Per-app language switching (Settings §3): AppCompatDelegate.setApplicationLocales() is the
    // AndroidX-recommended mechanism across the app's minSdk range — it uses the Android 13+
    // platform API automatically and falls back to its own implementation below that, without
    // requiring AppCompatActivity.
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
