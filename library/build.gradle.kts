@file:OptIn(ExperimentalWasmDsl::class)

import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.kotlin.benchmark)
    alias(libs.plugins.kotlin.binary.compatibility.validator)
}

group = "com.muedsa.js"
version = "0.0.1"

kotlin {
    jvm {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }

        compilations.create("benchmark") {
            associateWith(this@jvm.compilations.getByName("main"))
        }
    }
    androidLibrary {
        namespace = "com.muedsa.js"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    js {
        browser() // 浏览器环境
        nodejs() // Node.js 环境
    }
    wasmJs {
        browser()
        nodejs()
    }
    // Native 桌面/服务器目标
    linuxX64()   // Linux (x64)
    mingwX64()   // Windows (x64)
    macosX64()   // macOS (Intel)
    macosArm64() // macOS (Apple Silicon)

    // Apple 移动/嵌入式目标
    iosX64()     // iOS 模拟器 (x64)
    iosArm64()   // iOS 真机 (Arm64)
    iosSimulatorArm64() // iOS 模拟器 (Apple Silicon)
    tvosX64()    // tvOS 模拟器 (x64)
    tvosArm64()  // tvOS 真机 (Arm64)
    tvosSimulatorArm64() // tvOS 模拟器 (Apple Silicon)
    watchosX64() // watchOS 模拟器 (x64)
    watchosArm64() // watchOS 真机 (Arm64)
    watchosSimulatorArm64() // watchOS 模拟器 (Apple Silicon)

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation(libs.kotlin.serialization.json)
            implementation(libs.kotlin.benchmark.runtime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

benchmark {
    targets {
        register("jvmBenchmark")
    }
}