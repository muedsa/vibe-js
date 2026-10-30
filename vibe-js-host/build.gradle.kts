@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "com.muedsa.js"
version = "0.0.1"

/**
 * 宿主环境模块：提供 console / setTimeout / window 等浏览器风格能力。
 *
 * 该模块只依赖核心库与 Kotlin 通用 API，不绑定任何平台原生接口。
 */
kotlin {
    jvm {
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
    macosArm64() // macOS (Apple Silicon)

    // Apple 移动/嵌入式目标
    iosX64()     // iOS 模拟器 (x64)
    iosArm64()   // iOS 真机 (Arm64)
    iosSimulatorArm64() // iOS 模拟器 (Apple Silicon)
    tvosArm64()  // tvOS 真机 (Arm64)
    tvosSimulatorArm64() // tvOS 模拟器 (Apple Silicon)
    watchosArm64() // watchOS 真机 (Arm64)
    watchosSimulatorArm64() // watchOS 模拟器 (Apple Silicon)

    sourceSets {
        commonMain.dependencies {
            api(project(":vibe-js-core"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
