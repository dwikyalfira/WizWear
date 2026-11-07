plugins {
    // AGP 9 applies Kotlin itself — do NOT also apply org.jetbrains.kotlin.android.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
