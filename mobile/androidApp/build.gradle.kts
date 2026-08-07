plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.rfm.edubot.mobile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.rfm.edubot"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE_URL", "\"https://thebotslab.pt\"")
        buildConfigField("String", "DEBUG_LOGIN_EMAIL", "\"\"")
        buildConfigField("String", "DEBUG_LOGIN_PASSWORD", "\"\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEBUG_LOGIN_EMAIL", "\"review@thebotslab.pt\"")
            buildConfigField("String", "DEBUG_LOGIN_PASSWORD", "\"review@thebotslab.pt\"")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
}
