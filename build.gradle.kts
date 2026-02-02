plugins {
    alias(libs.plugins.kotlin.multiplatform) apply  false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.plugin.serialization) apply  false
    alias(libs.plugins.kotlin.benchmark) apply  false
    alias(libs.plugins.kotlin.binary.compatibility.validator) apply  false
}
