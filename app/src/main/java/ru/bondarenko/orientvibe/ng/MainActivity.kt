package ru.bondarenko.orientvibe.ng

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ru.bondarenko.orientvibe.ng.screen.AutoModeScreen
import ru.bondarenko.orientvibe.ng.ui.theme.OrientVibeTheme

/**
 * Главная точка входа Android-приложения OrientVibe.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Установка нативного экрана загрузки Android 12+ API
        installSplashScreen()

        super.onCreate(savedInstanceState)

        // Корректная обработка аппаратной кнопки "Назад" (сворачивание таска)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(false)
            }
        })

        // Инициализация Jetpack Compose контента
        setContent {
            OrientVibeTheme {
                AutoModeScreen()
            }
        }
    }
}
