plugins {
    id("edubot.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.material.icons.extended)
        }
    }
}
