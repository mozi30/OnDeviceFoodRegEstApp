plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zideck.foodregest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zideck.foodregest"
        minSdk = 29 // "any current Android device"; the Pixel 8 Pro is the reference device
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-stage1"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug") // demo builds only
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += "tflite" // models are memory-mapped from the APK
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.arcore)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
