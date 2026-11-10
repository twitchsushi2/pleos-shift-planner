// AGP 9부터 Kotlin이 AGP에 내장되어 org.jetbrains.kotlin.android 플러그인은 쓰지 않는다.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
