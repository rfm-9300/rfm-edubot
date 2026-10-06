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
            // `./gradlew :androidApp:installDebug -PapiBaseUrl=http://10.0.2.2:8080` points a
            // debug build at a backend on the host, so working on the app does not mean signing
            // in to production. The debug manifest already allows cleartext for this.
            val localApi = project.findProperty("apiBaseUrl") as String?
            if (localApi != null) buildConfigField("String", "API_BASE_URL", "\"$localApi\"")
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
