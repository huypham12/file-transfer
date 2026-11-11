package com.teamshare.app.core.platform.preference

import android.content.Context
import com.teamshare.app.core.constant.AppConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Chế độ giao diện hiển thị của ứng dụng.
 */
enum class ThemeMode {
    SYSTEM, // Theo cấu hình hệ thống Android
    LIGHT,  // Chế độ sáng
    DARK    // Chế độ tối
}

/**
 * Trình quản lý cấu hình người dùng lưu trữ bền vững qua Android SharedPreferences.
 * Cung cấp StateFlow cho phép Jetpack Compose tự động recompose tức thời khi cấu hình thay đổi.
 */
class AppPreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _requireBiometricForOpen = MutableStateFlow(
        prefs.getBoolean(KEY_REQUIRE_BIOMETRIC, true)
    )
    val requireBiometricForOpen: StateFlow<Boolean> = _requireBiometricForOpen.asStateFlow()

    private val _maxSessionReceivers = MutableStateFlow(
        prefs.getInt(KEY_MAX_RECEIVERS, AppConstants.FlowControl.MAX_SESSION_RECEIVERS)
    )
    val maxSessionReceivers: StateFlow<Int> = _maxSessionReceivers.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setRequireBiometricForOpen(required: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_BIOMETRIC, required).apply()
        _requireBiometricForOpen.value = required
    }

    fun setMaxSessionReceivers(count: Int) {
        val validCount = count.coerceIn(1, 10)
        prefs.edit().putInt(KEY_MAX_RECEIVERS, validCount).apply()
        _maxSessionReceivers.value = validCount
    }

    private fun loadThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    companion object {
        private const val PREFS_NAME = AppConstants.Preferences.PREFS_APP
        private const val KEY_THEME_MODE = AppConstants.Preferences.KEY_THEME_MODE
        private const val KEY_REQUIRE_BIOMETRIC = AppConstants.Preferences.KEY_REQUIRE_BIOMETRIC
        private const val KEY_MAX_RECEIVERS = AppConstants.Preferences.KEY_MAX_RECEIVERS

        @Volatile
        private var instance: AppPreferencesManager? = null

        fun getInstance(context: Context): AppPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: AppPreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

