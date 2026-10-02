plugins {
    id("edubot.kmp.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Agent action previews arrive as a raw JsonObject; their shape depends on the action.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
