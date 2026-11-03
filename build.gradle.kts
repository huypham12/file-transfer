// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Tự động cấu hình Git hooks cho dự án Android Kotlin
tasks.register<Exec>("installGitHooks") {
    description = "Cấu hình core.hooksPath trỏ tới thư mục .githooks"
    group = "git"
    commandLine("git", "config", "core.hooksPath", ".githooks")
    doLast {
        println("✅ Đã kích hoạt Git hook (.githooks) cho dự án SecureShare!")
    }
}
