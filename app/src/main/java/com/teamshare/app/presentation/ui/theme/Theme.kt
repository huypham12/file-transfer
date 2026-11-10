package com.teamshare.app.presentation.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.teamshare.app.core.platform.preference.ThemeMode

private val TeamShareLightColorScheme = lightColorScheme(
    primary = TeamShareBlueLight,
    onPrimary = TeamShareOnBlueLight,
    primaryContainer = TeamShareBlueContainerLight,
    onPrimaryContainer = TeamShareOnBlueContainerLight,
    secondary = TeamShareEmeraldLight,
    onSecondary = TeamShareOnEmeraldLight,
    secondaryContainer = TeamShareEmeraldContainerLight,
    onSecondaryContainer = TeamShareOnEmeraldContainerLight,
    tertiary = TeamShareTertiaryLight,
    onTertiary = TeamShareOnTertiaryLight,
    tertiaryContainer = TeamShareTertiaryContainerLight,
    onTertiaryContainer = TeamShareOnTertiaryContainerLight,
    background = TeamShareBackgroundLight,
    onBackground = TeamShareOnBackgroundLight,
    surface = TeamShareSurfaceLight,
    onSurface = TeamShareOnSurfaceLight,
    surfaceVariant = TeamShareSurfaceVariantLight,
    onSurfaceVariant = TeamShareOnSurfaceVariantLight,
    outline = TeamShareOutlineLight,
    outlineVariant = TeamShareOutlineVariantLight,
    error = TeamShareErrorLight,
    onError = TeamShareOnErrorLight,
    errorContainer = TeamShareErrorContainerLight,
    onErrorContainer = TeamShareOnErrorContainerLight
)

private val TeamShareDarkColorScheme = darkColorScheme(
    primary = TeamShareBlueDark,
    onPrimary = TeamShareOnBlueDark,
    primaryContainer = TeamShareBlueContainerDark,
    onPrimaryContainer = TeamShareOnBlueContainerDark,
    secondary = TeamShareEmeraldDark,
    onSecondary = TeamShareOnEmeraldDark,
    secondaryContainer = TeamShareEmeraldContainerDark,
    onSecondaryContainer = TeamShareOnEmeraldContainerDark,
    tertiary = TeamShareTertiaryDark,
    onTertiary = TeamShareOnTertiaryDark,
    tertiaryContainer = TeamShareTertiaryContainerDark,
    onTertiaryContainer = TeamShareOnTertiaryContainerDark,
    background = TeamShareBackgroundDark,
    onBackground = TeamShareOnBackgroundDark,
    surface = TeamShareSurfaceDark,
    onSurface = TeamShareOnSurfaceDark,
    surfaceVariant = TeamShareSurfaceVariantDark,
    onSurfaceVariant = TeamShareOnSurfaceVariantDark,
    outline = TeamShareOutlineDark,
    outlineVariant = TeamShareOutlineVariantDark,
    error = TeamShareErrorDark,
    onError = TeamShareOnErrorDark,
    errorContainer = TeamShareErrorContainerDark,
    onErrorContainer = TeamShareOnErrorContainerDark
)

@Composable
fun TeamShareTheme(
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
        darkTheme -> TeamShareDarkColorScheme
        else -> TeamShareLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TeamShareTypography,
        content = content
    )
}
