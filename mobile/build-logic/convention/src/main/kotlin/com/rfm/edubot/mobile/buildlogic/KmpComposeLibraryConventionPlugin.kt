package com.rfm.edubot.mobile.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        with(pluginManager) {
            apply("edubot.kmp.library")
            apply("org.jetbrains.compose")
            apply("org.jetbrains.kotlin.plugin.compose")
        }
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.named("commonMain") {
                dependencies {
                    implementation(libs.findLibrary("lifecycle-viewmodel").get())
                    implementation(libs.findLibrary("lifecycle-viewmodel-compose").get())
                }
            }
        }
    }
}
