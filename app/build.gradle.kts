import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------- settings built into the app ----------
// Read from secrets.properties (in the project folder, next to settings.gradle.kts). That file is private:
// it is listed in .gitignore, so it is never shared with the code. A beekeeper never types a key or an address.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    "\"" + (secrets.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.ceranawatch"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.ceranawatch"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
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
}

androidComponents {
    onVariants { variant ->
        val fields = variant.buildConfigFields
        fields?.put("FIREBASE_URL", com.android.build.api.variant.BuildConfigField("String", secret("firebase.url"), "Firebase Realtime Database address"))
        fields?.put("AI_PROXY_URL", com.android.build.api.variant.BuildConfigField("String", secret("ai.proxy.url"), "our own small server that keeps the AI key"))
        fields?.put("AI_PROXY_TOKEN", com.android.build.api.variant.BuildConfigField("String", secret("ai.proxy.token"), "app token checked by that server"))
        fields?.put("GROQ_KEY", com.android.build.api.variant.BuildConfigField("String", secret("groq.key"), "only when no server is used"))
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.litert)                 // runs YAMNet for the bee-sound filter
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}