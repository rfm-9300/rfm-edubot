package com.rfm.edubot.mobile.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A module under `feature`: Compose plus the core modules every screen needs. Features depend on
 * `core:data` rather than `core:network`, which is what keeps HTTP and bearer tokens out of the UI.
 */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        pluginManager.apply("edubot.kmp.compose.library")
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") {
                dependencies {
                    implementation(project(":core:common"))
                    implementation(project(":core:data"))
                    implementation(project(":core:localization"))
                    implementation(project(":core:model"))
                    implementation(project(":core:ui"))
                    implementation(libs.findLibrary("compose-runtime").get())
                    implementation(libs.findLibrary("compose-foundation").get())
                    implementation(libs.findLibrary("compose-material3").get())
                    implementation(libs.findLibrary("compose-ui").get())
                    implementation(libs.findLibrary("coroutines-core").get())
                }
            }
            sourceSets.named("commonTest") {
                dependencies {
                    implementation(project(":core:testing"))
                }
            }
        }
    }
}
