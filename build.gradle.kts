plugins {
    alias(libs.plugins.kotlin.multiplatform) apply  false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.benchmark) apply  false
}

// 项目暂不发布正式版：所有模块统一使用快照版本，由 CI 推送到 GitHub Packages。
allprojects {
    group = "com.muedsa.js"
    version = "0.0.0-SNAPSHOT"
}
