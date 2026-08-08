plugins {
    id("edubot.kmp.compose.library")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "EduBotShared"
            isStatic = true
            binaryOption("bundleId", "com.rfm.edubot.shared")
            export(project(":core:model"))
            export(project(":core:network"))
            export(project(":core:common"))
            export(project(":core:localization"))
            export(project(":core:ui"))
            export(project(":feature:auth"))
            export(project(":feature:overview"))
            export(project(":feature:inbox"))
            export(project(":feature:contacts"))
            export(project(":feature:assistant"))
            export(project(":feature:crm"))
            export(project(":feature:persona"))
            export(project(":feature:settings"))
            export(libs.coroutines.core.get())
            transitiveExport = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            api(project(":core:network"))
            api(project(":core:common"))
            api(project(":core:localization"))
            api(project(":core:ui"))
            api(project(":feature:auth"))
            api(project(":feature:overview"))
            api(project(":feature:inbox"))
            api(project(":feature:contacts"))
            api(project(":feature:assistant"))
            api(project(":feature:crm"))
            api(project(":feature:persona"))
            api(project(":feature:settings"))
            api(libs.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
        }
    }
}
