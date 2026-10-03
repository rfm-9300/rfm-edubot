plugins {
    id("edubot.kmp.compose.library")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "EduBotShared"
            isStatic = true
            binaryOption("bundleId", "com.rfm.edubot.shared")
            export(project(":core:common"))
            export(project(":core:data"))
            export(project(":core:localization"))
            export(project(":core:model"))
            export(project(":core:network"))
            export(project(":core:ui"))
            export(libs.coroutines.core.get())
            transitiveExport = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:data"))
            api(project(":core:localization"))
            api(project(":core:model"))
            api(project(":core:network"))
            api(project(":core:ui"))
            // The feature modules are an implementation detail of the shell: Swift and Kotlin/JVM
            // callers only need DashboardApp and MobileGraph.
            implementation(project(":feature:agents"))
            implementation(project(":feature:assistant"))
            implementation(project(":feature:auth"))
            implementation(project(":feature:bookings"))
            implementation(project(":feature:contacts"))
            implementation(project(":feature:crm"))
            implementation(project(":feature:inbox"))
            implementation(project(":feature:notifications"))
            implementation(project(":feature:overview"))
            implementation(project(":feature:persona"))
            implementation(project(":feature:settings"))
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
