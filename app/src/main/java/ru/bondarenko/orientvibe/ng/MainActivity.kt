package ru.bondarenko.orientvibe.ng

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.screen.AutoModeScreen
import ru.bondarenko.orientvibe.ng.screen.MainScreen
import ru.bondarenko.orientvibe.ng.screen.StartScreen
import ru.bondarenko.orientvibe.ng.ui.theme.OrientVibeTheme
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val navVm: NavModeViewModel = ViewModelProvider(
            this,
            NavModeViewModelFactory()
        )[NavModeViewModel::class.java]

        setContent {
            OrientVibeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val mode by navVm.currentMode.collectAsState()

                    when (mode) {
                        "start" -> StartScreen(
                            onManualMode = { navVm.setCurrentMode("manual") },
                            onAutoMode = { navVm.setCurrentMode("auto") }
                        )
                        "manual" -> MainScreenWithCtx(LocalContext.current)
                        "auto" -> AutoModeScreen()
                    }
                }
            }
        }

        // Intercept Android system back button for non-start modes
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navVm.handleBack()) {
                    isEnabled = false // Let system default back go (start screen only)
                }
            }
        })
    }
}

@Composable
private fun MainScreenWithCtx(context: Context) {
    val mapVm = viewModel<MapViewModel>(factory = MapViewModel.Factory(context))
    val navVc = viewModel<NavViewModel>(factory = NavViewModel.Factory(context))
    MainScreen(viewModel = mapVm, navViewModel = navVc)
}

/** Manages app-level navigation mode state across Activity lifecycle. */
class NavModeViewModel : ViewModel() {
    private val _currentMode = MutableStateFlow<String>("start")
    val currentMode: StateFlow<String> = _currentMode.asStateFlow()

    fun setCurrentMode(mode: String) {
        if (mode in listOf("start", "manual", "auto")) {
            _currentMode.value = mode
        }
    }

    /** Returns true if back was handled, false if default behavior should apply. */
    fun handleBack(): Boolean {
        return when (_currentMode.value) {
            "manual" -> { _currentMode.value = "start"; true }
            "auto" -> { _currentMode.value = "start"; true }
            else -> false
        }
    }
}

class NavModeViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = NavModeViewModel() as T
}
