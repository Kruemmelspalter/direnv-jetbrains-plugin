import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
        id("org.jetbrains.intellij.platform.module")
        alias(libs.plugins.kotlin)
    }

kotlin { jvmToolchain(21) }

dependencies {
        implementation(project(":core"))

        intellijPlatform {
               create(IntelliJPlatformType.RustRover, providers.gradleProperty("platformVersion")) {
                        useInstaller = false
                    }
                plugin("com.jetbrains.rust:261.26222.73")
            }
    }