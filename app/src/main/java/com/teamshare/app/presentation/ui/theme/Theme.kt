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

private val TeamShareLightColorScheme = lightColorScheme(
    primary = TeamShareBlueLight,
    onPrimary = TeamShareOnBlueLight,
    primaryContainer = TeamShareBlueContainerLight,
    onPrimaryContainer = TeamShareOnBlueContainerLight,
    secondary = TeamShareTealLight,
    onSecondary = TeamShareOnTealLight,
    secondaryContainer = TeamShareTealContainerLight,
    onSecondaryContainer = TeamShareOnTealContainerLight,
    background = TeamShareBackgroundLight,
    onBackground = TeamShareOnBackgroundLight,
    surface = TeamShareSurfaceLight,
    onSurface = TeamShareOnSurfaceLight,
    surfaceVariant = TeamShareSurfaceVariantLight,
    onSurfaceVariant = TeamShareOnSurfaceVariantLight,
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
    secondary = TeamShareTealDark,
    onSecondary = TeamShareOnTealDark,
    secondaryContainer = TeamShareTealContainerDark,
    onSecondaryContainer = TeamShareOnTealContainerDark,
    background = TeamShareBackgroundDark,
    onBackground = TeamShareOnBackgroundDark,
    surface = TeamShareSurfaceDark,
    onSurface = TeamShareOnSurfaceDark,
    surfaceVariant = TeamShareSurfaceVariantDark,
    onSurfaceVariant = TeamShareOnSurfaceVariantDark,
    error = TeamShareErrorDark,
    onError = TeamShareOnErrorDark,
    errorContainer = TeamShareErrorContainerDark,
    onErrorContainer = TeamShareOnErrorContainerDark
)

@Composable
fun TeamShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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

