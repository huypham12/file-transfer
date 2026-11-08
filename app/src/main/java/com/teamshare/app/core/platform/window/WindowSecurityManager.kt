package com.teamshare.app.core.platform.window

import android.app.Activity
import android.view.WindowManager

/**
 * Quản lý chính sách an ninh cửa sổ hiển thị (Window Security Policy) theo SRS FR-DEV-02.
 * Thiết lập cờ FLAG_SECURE ngăn chặn chụp ảnh màn hình (Screenshot), quay video màn hình,
 * và che khuất nội dung nhạy cảm trên màn hình chuyển đổi ứng dụng gần đây (Recent Tasks / Task Switcher).
 */
object WindowSecurityManager {

    const val FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE

    /**
     * Kích hoạt cờ an ninh FLAG_SECURE trên Activity.
     */
    fun applyFlagSecure(activity: Activity) {
        try {
            activity.window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } catch (_: Throwable) {
            // Fallback trong môi trường test thuần JVM
        }
    }

    /**
     * Gỡ bỏ cờ an ninh FLAG_SECURE.
     */
    fun clearFlagSecure(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
            // Fallback trong môi trường test thuần JVM
        }
    }

    /**
     * Kiểm tra Activity hiện tại có đang được bảo vệ bởi FLAG_SECURE hay không.
     */
    fun isFlagSecureApplied(activity: Activity): Boolean {
        return try {
            val flags = activity.window.attributes.flags
            isFlagSecureBitSet(flags)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Kiểm tra bit FLAG_SECURE trong tập hợp cờ của Window.
     */
    fun isFlagSecureBitSet(flags: Int): Boolean {
        return (flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
    }
}
