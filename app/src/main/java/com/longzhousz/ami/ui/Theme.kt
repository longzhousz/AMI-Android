package com.longzhousz.ami.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF315BD6), onPrimary = Color.White, primaryContainer = Color(0xFFE6EDFF), onPrimaryContainer = Color(0xFF193F9C),
    background = Color(0xFFF8F9FD), onBackground = Color(0xFF1B2234), surface = Color(0xFFF8F9FD),
    surfaceContainer = Color(0xFFEEF1F8), surfaceContainerLow = Color.White, surfaceContainerHigh = Color(0xFFE6EAF4),
    onSurface = Color(0xFF1B2234), onSurfaceVariant = Color(0xFF727B90), outlineVariant = Color(0xFFE0E5F0),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFAFC4FF), onPrimary = Color(0xFF12307A), primaryContainer = Color(0xFF25395F), onPrimaryContainer = Color(0xFFD9E3FF),
    background = Color(0xFF10141E), onBackground = Color(0xFFE8EDFA), surface = Color(0xFF10141E),
    surfaceContainer = Color(0xFF1C2230), surfaceContainerLow = Color(0xFF171C28), surfaceContainerHigh = Color(0xFF293044),
    onSurface = Color(0xFFE8EDFA), onSurfaceVariant = Color(0xFFA0AABE), outlineVariant = Color(0xFF343E52),
)
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
)
@Composable fun AmiTheme(preference: String, content: @Composable () -> Unit) {
    val dark = when (preference) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val activity = view.context as? Activity
        if (activity != null) WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
