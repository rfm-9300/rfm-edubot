plugins {
    id("edubot.kmp.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:localization"))
            implementation(project(":core:model"))
            implementation(project(":core:network"))
            implementation(project(":core:ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.coroutines.core)
        }
    }
}
