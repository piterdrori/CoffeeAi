import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Registration bootstrap key for POST /v1/devices/register (and legacy compatibility). Sourced from
// an UNTRACKED local build source so it is never hardcoded in git. Precedence: Gradle project
// property -> environment variable -> android/local.properties. Missing -> empty string (never the
// old dev default): the app then works fully offline and device registration fails safely.
// NOTE: this ends up in BuildConfig and is APK-extractable — it is a bootstrap value, NOT a true
// secret. The Supabase service-role key must never be placed in the Android app.
val coffeeaiBootstrapKey: String = run {
    val fromProperty = (project.findProperty("COFFEEAI_BOOTSTRAP_KEY") as String?)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv("COFFEEAI_BOOTSTRAP_KEY")?.takeIf { it.isNotBlank() }
    val fromLocalProps = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { FileInputStream(file).use { load(it) } }
            .getProperty("COFFEEAI_BOOTSTRAP_KEY")?.takeIf { it.isNotBlank() }
    }
    fromProperty ?: fromEnv ?: fromLocalProps ?: ""
}

android {
    namespace = "com.personaledge.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.personaledge.ai"
        minSdk = 31
        targetSdk = 34
        versionCode = 17
        versionName = "1.4.12"

        buildConfigField("String", "CLOUD_URL", "\"https://personal-edge-ai.vercel.app\"")
        // Escape backslashes and quotes so arbitrary key characters are safe inside the generated
        // Java string literal. Same source for debug and release (defined in defaultConfig).
        val escapedBootstrapKey = coffeeaiBootstrapKey.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "CLOUD_API_KEY", "\"$escapedBootstrapKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        noCompress += listOf("litertlm", "onnx", "bin", "fst", "far")
    }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libonnxruntime.so", "**/libsherpa-onnx-jni.so")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    val roomVersion = "2.7.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    implementation(project(":whisper"))

    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.2-1")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
