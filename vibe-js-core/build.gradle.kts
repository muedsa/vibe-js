@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.benchmark)
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
    android {
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
        nodejs {
            testTask {
                // 深递归/大语料用例在 CI 负载下可能超过 mocha 默认 2s 超时
                useMocha { timeout = "120s" }
            }
        }
    }
    wasmJs {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "120s" }
            }
        }
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
            // 协程用于实现 async/await 与事件循环（KMP 通用）
            implementation(libs.kotlinx.coroutines.core)
        }

        val jvmBenchmark by getting {
            dependencies {
                // 基准测试注解不应进入发布库的 runtime classpath
                implementation(libs.kotlin.benchmark.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        named("main") {
            // 允许 -PbenchmarkFilter=CollectionBenchmark 只跑某个基准类，避免全量基准耗时过长
            project.findProperty("benchmarkFilter")?.let { include(it.toString()) }
        }
    }
}

/**
 * 记录基准运行环境。
 *
 * 基准数字只有配合 JDK/OS/CPU/内存/GC 信息才能跨机器比较；输出与
 * kotlinx-benchmark 的 JSON 放在同一目录，便于一起归档。
 */
// 在脚本层读取版本：任务配置块内的接收者是 Task，直接访问 libs 会解析失败；
// 版本目录访问器在脚本中不可直接链式调用，改用显式 API。
val benchmarkKotlinVersion = project.extensions
    .getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
    .named("libs")
    .findVersion("kotlin")
    .get()
    .requiredVersion

val benchmarkEnvironment by tasks.registering {
    group = "verification"
    description = "记录基准环境（JDK/OS/CPU/内存/GC/Gradle/Kotlin）"
    val reportDir = layout.buildDirectory.dir("reports/benchmarks")
    val gradleVersion = gradle.gradleVersion
    val kotlinVersion = benchmarkKotlinVersion
    outputs.dir(reportDir)
    // 环境信息随每次运行刷新，不参与增量判定
    outputs.upToDateWhen { false }
    doLast {
        val directory = reportDir.get().asFile
        directory.mkdirs()
        val runtime = Runtime.getRuntime()
        val gcNames = ManagementFactory.getGarbageCollectorMXBeans()
            .joinToString(", ") { it.name }
        File(directory, "environment.md").writeText(
            buildString {
                appendLine("# 基准环境")
                appendLine()
                appendLine("| 项 | 值 |")
                appendLine("|---|---|")
                appendLine("| 生成时间 | ${Instant.now()} |")
                appendLine(
                    "| OS | ${System.getProperty("os.name")} " +
                        "${System.getProperty("os.version")} / ${System.getProperty("os.arch")} |"
                )
                appendLine("| CPU 逻辑核 | ${runtime.availableProcessors()} |")
                appendLine(
                    "| JVM | ${System.getProperty("java.vm.name")} " +
                        "${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) |"
                )
                appendLine("| 最大堆 | ${runtime.maxMemory() / (1024 * 1024)} MB |")
                appendLine("| GC | $gcNames |")
                appendLine("| Gradle | $gradleVersion |")
                appendLine("| Kotlin | $kotlinVersion |")
            }
        )
        println("基准环境已写入 ${directory.absolutePath}/environment.md")
    }
}

tasks.matching { it.name == "jvmBenchmarkBenchmark" }.configureEach {
    dependsOn(benchmarkEnvironment)
}
