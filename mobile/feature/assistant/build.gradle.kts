plugins {
    id("edubot.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.material.icons.extended)
            // A proposed change's preview arrives as a raw JsonObject; its shape depends on the tool.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
