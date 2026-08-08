plugins {
    id("edubot.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
        }
    }
}
