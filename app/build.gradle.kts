plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jel.handgesture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jel.handgesture"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    // v1 손 제스처 앱 소스 (이전 시선 추적 코드는 src/main/java 에 보관, 빌드 제외)
    sourceSets {
        getByName("main").java.setSrcDirs(listOf("src/main/hand"))
    }
    androidResources {
        // MediaPipe가 모델을 메모리 매핑하려면 압축되지 않아야 함
        noCompress += "task"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// 손 랜드마크 모델(약 7.5MB)을 빌드 시 자동 다운로드
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val downloadHandModel by tasks.registering {
    val out = file("src/main/assets/hand_landmarker.task")
    outputs.file(out)
    doLast {
        if (!out.exists() || out.length() == 0L) {
            out.parentFile.mkdirs()
            uri(handModelUrl).toURL().openStream().use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            println("Downloaded hand_landmarker.task (${out.length()} bytes)")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
