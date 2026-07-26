package ru.bondarenko.orientvibe.ng.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bondarenko.orientvibe.ng.gps.AccuracyLevel
import ru.bondarenko.orientvibe.ng.gps.GpsState

private val GpsRed = Color(0xFFE53935)
private val GpsYellow = Color(0xFFFFD600)
private val GpsGreen = Color(0xFF4CAF50)

@Composable
fun TopInfoPanel(
    message: String,
    isVisible: Boolean = true,
    progress: Float = 0f,
    isProcessing: Boolean = false,
    progressMessage: String? = null,
    azimuth: Float? = null,
    gpsState: GpsState? = null,
    routeDistance: Double? = null,  // meters
    mapScale: Double? = null,       // meters per pixel
    modifier: Modifier = Modifier
) {
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 0.9f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "alpha"
    )

    val backgroundColor by animateColorAsState(
        targetValue = Color(0xFF1E1E1E).copy(alpha = alpha),
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
                ambientColor = Color(0xFF6200EE).copy(alpha = 0.3f),
                spotColor = Color(0xFF6200EE).copy(alpha = 0.5f)
            )
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(16.dp)
            )
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = if (isProcessing && progressMessage != null) progressMessage else message,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            azimuth?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Азимут: ${String.format("%.1f", it)}°",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }

            // GPS status line
            gpsState?.let { gps ->
                Spacer(modifier = Modifier.height(4.dp))
                val gpsText = when (gps.accuracyLevel) {
                    AccuracyLevel.NO_FIX -> "GPS: —"
                    AccuracyLevel.LOW_ACCURACY -> "GPS: ${String.format("%.0f", gps.currentFix!!.accuracy)} м"
                    AccuracyLevel.HIGH_ACCURACY -> "GPS: ${String.format("%.0f", gps.currentFix!!.accuracy)} м"
                }
                val gpsColor = when (gps.accuracyLevel) {
                    AccuracyLevel.NO_FIX -> GpsRed
                    AccuracyLevel.LOW_ACCURACY -> GpsYellow
                    AccuracyLevel.HIGH_ACCURACY -> GpsGreen
                }
                Text(
                    text = if (gps.isGpsEnabled) gpsText else "GPS: выкл",
                    color = if (gps.isGpsEnabled) gpsColor else Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Route distance and map scale
            routeDistance?.let { dist ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Расстояние: ${String.format("%.0f", dist)} м",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            if (isProcessing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                )
            }
        }
    }
}