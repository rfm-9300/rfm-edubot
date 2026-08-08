plugins {
    `kotlin-dsl`
}

group = "com.rfm.edubot.mobile.buildlogic"

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.kotlin.compose.compiler.gradle.plugin)
    compileOnly(libs.kotlin.serialization.gradle.plugin)
    compileOnly(libs.compose.multiplatform.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "edubot.kmp.library"
            implementationClass = "com.rfm.edubot.mobile.buildlogic.KmpLibraryConventionPlugin"
        }
        register("kmpComposeLibrary") {
            id = "edubot.kmp.compose.library"
            implementationClass = "com.rfm.edubot.mobile.buildlogic.KmpComposeLibraryConventionPlugin"
        }
    }
}
