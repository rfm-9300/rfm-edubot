package com.rfm.edubot.mobile.buildlogic

import com.android.build.gradle.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.multiplatform")
            apply("org.jetbrains.kotlin.plugin.serialization")
        }
        extensions.configure<KotlinMultiplatformExtension> {
            androidTarget {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
            iosArm64()
            iosSimulatorArm64()
            sourceSets.named("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
        extensions.configure<LibraryExtension> {
            namespace = "com.rfm.edubot.mobile" + target.path.replace(":", ".")
            compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()
            defaultConfig {
                minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}
