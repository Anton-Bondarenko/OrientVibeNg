package ru.bondarenko.orientvibe.ng.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Темная цветовая схема для Material 3.
 */
private val DarkColorScheme = darkColorScheme(
    primary = OrientVibeColors.Green80,
    onPrimary = Color.White,
    primaryContainer = OrientVibeColors.GreenGrey80,
    onPrimaryContainer = Color.White,
    secondary = OrientVibeColors.LightGreen80,
    onSecondary = Color.White,
    background = Color(0xFF000000), // Восстановлено из 4279983648L
    onBackground = Color.White,
    surface = Color(0xFF388E3C),    // Восстановлено из 4281236786L
    onSurface = Color.White
)

/**
 * Светлая цветовая схема для Material 3.
 */
private val LightColorScheme = lightColorScheme(
    primary = OrientVibeColors.Green40,
    onPrimary = Color.White,
    primaryContainer = OrientVibeColors.GreenGrey40,
    onPrimaryContainer = Color.White,
    secondary = OrientVibeColors.LightGreen40,
    onSecondary = Color.White,
    background = Color(0xFFEFEF89), // Восстановлено из 4293457385L
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),    // Восстановлено из 4294967295L
    onSurface = Color(0xFF000000)
)

/**
 * Основная Composable-тема оформления для всего Android-приложения OrientVibe.
 */
@Composable
fun OrientVibeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Динамические цвета Android 12+ (Material You)
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Определение цветовой схемы на основе версии Android и предпочтений темы
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Инициализация MaterialTheme с кастомными схемами и шрифтами
    MaterialTheme(
        colorScheme = colorScheme,
        typography = OrientVibeTypography, // Связывание со шрифтами проекта (Type.kt)
        content = content
    )
}
