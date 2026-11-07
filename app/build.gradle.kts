plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun flag(name: String) = (project.findProperty(name) as String?)?.trim()?.toBoolean() ?: false
val useVoicevox = flag("rikka.engine.voicevox")
val useSherpa = flag("rikka.engine.sherpa")
val usePiper = flag("rikka.engine.piperplus")

android {
    namespace = "com.rikka.voice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rikka.voice"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("boolean", "HAS_VOICEVOX", useVoicevox.toString())
        buildConfigField("boolean", "HAS_SHERPA", useSherpa.toString())
        buildConfigField("boolean", "HAS_PIPERPLUS", usePiper.toString())
        if (useVoicevox || usePiper) {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DRIKKA_VV=" + (if (useVoicevox) "ON" else "OFF"),
                        "-DRIKKA_PIPER=" + (if (usePiper) "ON" else "OFF"),
                    )
                }
            }
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    sourceSets {
        getByName("main") {
            if (useVoicevox) java.srcDir("src/voicevox/kotlin")
            if (useSherpa) java.srcDir("src/sherpa/kotlin")
            if (usePiper) java.srcDir("src/piperplus/kotlin")
        }
    }

    if (useVoicevox || usePiper) {
        externalNativeBuild { cmake { path = file("src/native/CMakeLists.txt"); version = "3.22.1" } }
    }

    signingConfigs {
        create("rikka") {
            storeFile = file(System.getenv("RIKKA_KEYSTORE") ?: "../rikka-voice.p12")
            storePassword = System.getenv("RIKKA_KEYSTORE_PASS") ?: "rikka-build"
            keyAlias = System.getenv("RIKKA_KEY_ALIAS") ?: "rikka-voice"
            keyPassword = System.getenv("RIKKA_KEY_PASS") ?: "rikka-build"
        }
    }

    buildTypes {
        release {
            // 不要开混淆：原生库按字段名读取对象。必须开的话 proguard-rules.pro 里的 keep 规则不能删
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式签名：仓库根目录的 rikka-voice.p12（首版即固定，后续版本可覆盖装）
            signingConfig = signingConfigs.getByName("rikka")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx 与 piper-plus 可能都带 onnxruntime / c++_shared
            pickFirsts += listOf("**/libonnxruntime.so", "**/libc++_shared.so")
        }
        resources { excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES") }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // VOICEVOX：不再用 Java API / AAR；libvoicevox_core.so 与 libvoicevox_onnxruntime.so 放进 src/main/jniLibs/arm64-v8a/
    if (useSherpa) implementation(files("libs/sherpa-onnx.aar"))
    // piper-plus：libpiper_plus.so 等放进 src/main/jniLibs/arm64-v8a/（见构建说明）
}
