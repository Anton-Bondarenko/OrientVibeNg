package ru.bondarenko.orientvibe.ng.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.bondarenko.orientvibe.ng.model.AccuracyLevel
import ru.bondarenko.orientvibe.ng.model.GpsState
import java.util.Locale

// Восстановленные HEX-цвета из метаданных и байт-кода
private val GpsRed = Color(0xFFFFA4A5)    // 4293212469L
private val GpsYellow = Color(0xFFFFC100) // 4294956544L
private val GpsGreen = Color(0xFF4CAF50)  // 4283215696L
private val DataGreen = Color(0xFF4CAF50) // 4283215696L
private val BasePanelColor = Color(0xFF1E1E1E) // 4280163870L

/**
 * Верхняя информационная панель телеметрии и навигации (TopInfoPanel).
 */
@Composable
fun TopInfoPanel(
    message: String,
    isVisible: Boolean = true,
    progress: Double = 0.0,
    isProcessing: Boolean = false,
    progressMessage: String? = null,
    azimuth: Double? = null,
    gpsState: GpsState? = null,
    routeDistance: Double? = null,
    currentDistanceFromStart: Double? = null,
    mapScale: Double? = null,
    magneticBearing: Double? = null,
    modifier: Modifier = Modifier
) {
    // Анимация прозрачности появления панели
    val animatedAlpha by animateFloatAsState(
        targetValue = if (isVisible) 0.9f else 0.0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    // Анимация цвета фона
    val backgroundColor by animateColorAsState(
        targetValue = BasePanelColor.copy(alpha = animatedAlpha),
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "backgroundColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(16.dp),
                clip = false,
                ambientColor = Color(0xFF424242).copy(alpha = 0.3f),
                spotColor = Color(0xFF424242).copy(alpha = 0.5f)
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(16.dp)
            )
            .alpha(animatedAlpha),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {

            // Левая колонка: Основные текстовые уведомления и прогресс-бар
            Column(
                modifier = Modifier.weight(1.0f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = if (isProcessing && progressMessage != null) progressMessage else message,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.bodyLarge
                )

                if (isProcessing) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Правая колонка: Метрики навигации (Курс, Азимут, Дистанция)
            Row(
                modifier = Modifier.weight(2.0f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    val courseValue = magneticBearing ?: gpsState?.currentFix?.bearing
                    if (courseValue != null) {
                        Text(
                            text = "Курс: ${String.format(Locale.ROOT, "%.0f", courseValue)}°",
                            color = DataGreen,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    if (azimuth != null) {
                        Text(
                            text = "Азимут: ${String.format(Locale.ROOT, "%.1f", azimuth)}°",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    if (currentDistanceFromStart != null) {
                        Text(
                            text = "Дист: ${String.format(Locale.ROOT, "%.0f", currentDistanceFromStart)} м",
                            color = DataGreen,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }

                // Правая колонка: Состояние и точность GPS
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    if (gpsState != null) {
                        val (gpsText, gpsColor) = when {
                            !gpsState.isGpsEnabled -> Pair("GPS: выкл", Color.Gray)
                            gpsState.accuracyLevel == AccuracyLevel.NO_FIX -> Pair("GPS: —", GpsRed)
                            gpsState.accuracyLevel == AccuracyLevel.LOW_ACCURACY -> {
                                val acc = gpsState.currentFix?.accuracy ?: 0.0
                                Pair("GPS: ${String.format(Locale.ROOT, "%.0f", acc)} м", GpsYellow)
                            }
                            else -> {
                                val acc = gpsState.currentFix?.accuracy ?: 0.0
                                Pair("GPS: ${String.format(Locale.ROOT, "%.0f", acc)} м", GpsGreen)
                            }
                        }

                        Text(
                            text = gpsText,
                            color = gpsColor,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    if (azimuth != null && currentDistanceFromStart == null) {
                        Text(
                            text = " ",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}
