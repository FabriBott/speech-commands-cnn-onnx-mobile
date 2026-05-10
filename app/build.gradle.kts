plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.voicesmarthome"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.voicesmarthome"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:latest.release")
    //implementation("com.github.JorenSix:TarsosDSP:2.4")
    //implementation("be.tarsos.dsp:core:2.5")
    //implementation("be.tarsos.dsp:jvm:2.5")
    implementation(files("libs/TarsosDSP-Android-latest.jar"))
    implementation(files("libs/jlibrosa-1.1.8-SNAPSHOT-jar-with-dependencies.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}