// Top-level build file. Plugins declared with apply false so they are
// resolvable only when a subproject applies them. The :app (Android) module
// is built in CI; the :core (pure Kotlin JVM) module is the locally verified unit.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kapt) apply false
    alias(libs.plugins.hilt) apply false
}
