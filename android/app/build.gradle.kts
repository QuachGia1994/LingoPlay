plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lingoplay.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lingoplay.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val translationApiBaseUrl = providers.gradleProperty("LINGOPLAY_TRANSLATION_API_BASE_URL").orNull.orEmpty()
        buildConfigField("String", "TRANSLATION_API_BASE_URL", "\"${translationApiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("deviceTest") {
            storeFile = file("../keystores/lingoplay-device-test.p12")
            storePassword = "android"
            keyAlias = "lingoplay-device-test"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("deviceTest")
        }
        release {
            isDebuggable = false
            optimization {
                enable = true
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // AGP 9.3+ keeps native libraries uncompressed and 16 KB ZIP-aligned.
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.android.billingclient:billing:9.1.0")

    val localSherpaAar = file("libs/sherpa-onnx-1.13.7.aar")
    if (localSherpaAar.exists()) {
        implementation(files(localSherpaAar))
    } else {
        implementation("com.github.k2-fsa:sherpa-onnx:v1.13.7") {
            // The Android AAR already contains the Kotlin/JVM API classes.
            // JitPack also declares sherpa-onnx-jvm transitively, which duplicates them on CI.
            exclude(group = "com.github.k2-fsa.sherpa-onnx", module = "sherpa-onnx-jvm")
        }
    }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
