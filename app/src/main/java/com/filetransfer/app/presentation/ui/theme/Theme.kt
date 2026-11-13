package com.filetransfer.app.presentation.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.filetransfer.app.core.platform.preference.ThemeMode

private val filetransferLightColorScheme = lightColorScheme(
    primary = filetransferBlueLight,
    onPrimary = filetransferOnBlueLight,
    primaryContainer = filetransferBlueContainerLight,
    onPrimaryContainer = filetransferOnBlueContainerLight,
    secondary = filetransferEmeraldLight,
    onSecondary = filetransferOnEmeraldLight,
    secondaryContainer = filetransferEmeraldContainerLight,
    onSecondaryContainer = filetransferOnEmeraldContainerLight,
    tertiary = filetransferTertiaryLight,
    onTertiary = filetransferOnTertiaryLight,
    tertiaryContainer = filetransferTertiaryContainerLight,
    onTertiaryContainer = filetransferOnTertiaryContainerLight,
    background = filetransferBackgroundLight,
    onBackground = filetransferOnBackgroundLight,
    surface = filetransferSurfaceLight,
    onSurface = filetransferOnSurfaceLight,
    surfaceVariant = filetransferSurfaceVariantLight,
    onSurfaceVariant = filetransferOnSurfaceVariantLight,
    outline = filetransferOutlineLight,
    outlineVariant = filetransferOutlineVariantLight,
    error = filetransferErrorLight,
    onError = filetransferOnErrorLight,
    errorContainer = filetransferErrorContainerLight,
    onErrorContainer = filetransferOnErrorContainerLight
)

private val filetransferDarkColorScheme = darkColorScheme(
    primary = filetransferBlueDark,
    onPrimary = filetransferOnBlueDark,
    primaryContainer = filetransferBlueContainerDark,
    onPrimaryContainer = filetransferOnBlueContainerDark,
    secondary = filetransferEmeraldDark,
    onSecondary = filetransferOnEmeraldDark,
    secondaryContainer = filetransferEmeraldContainerDark,
    onSecondaryContainer = filetransferOnEmeraldContainerDark,
    tertiary = filetransferTertiaryDark,
    onTertiary = filetransferOnTertiaryDark,
    tertiaryContainer = filetransferTertiaryContainerDark,
    onTertiaryContainer = filetransferOnTertiaryContainerDark,
    background = filetransferBackgroundDark,
    onBackground = filetransferOnBackgroundDark,
    surface = filetransferSurfaceDark,
    onSurface = filetransferOnSurfaceDark,
    surfaceVariant = filetransferSurfaceVariantDark,
    onSurfaceVariant = filetransferOnSurfaceVariantDark,
    outline = filetransferOutlineDark,
    outlineVariant = filetransferOutlineVariantDark,
    error = filetransferErrorDark,
    onError = filetransferOnErrorDark,
    errorContainer = filetransferErrorContainerDark,
    onErrorContainer = filetransferOnErrorContainerDark
)

@Composable
fun filetransferTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    },
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> filetransferDarkColorScheme
        else -> filetransferLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = filetransferTypography,
        content = content
    )
}
