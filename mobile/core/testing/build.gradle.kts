plugins {
    id("edubot.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:model"))
            api(project(":core:network"))
            implementation(libs.coroutines.core)
        }
    }
}
