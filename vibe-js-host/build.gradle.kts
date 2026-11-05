@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

// group/version 由根 build.gradle.kts 统一设置（0.0.0-SNAPSHOT）

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

/**
 * GitHub Packages 发布配置。
 *
 * - 版本由根项目统一为 `0.0.0-SNAPSHOT`，每次分支推送由 CI 覆盖发布；
 * - 仓库地址取 CI 的 `GITHUB_REPOSITORY`（owner/repo，需小写），
 *   本地未设置该变量时不注册仓库，避免误发布；
 * - 凭据使用工作流注入的 `GITHUB_ACTOR` / `GITHUB_TOKEN`。
 */
publishing {
    repositories {
        val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY").orNull
        if (!githubRepository.isNullOrBlank()) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
                credentials {
                    username = providers.environmentVariable("GITHUB_ACTOR").orNull
                    password = providers.environmentVariable("GITHUB_TOKEN").orNull
                }
            }
        }
    }
}
