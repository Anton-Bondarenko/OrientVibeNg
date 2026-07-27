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
private val DataGreen = Color(0xFF4CAF50)

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
    currentDistanceFromStart: Double? = null, // meters from start point
    mapScale: Double? = null,       // meters per pixel
    magneticBearing: Float? = null, // current magnetic heading from GPS
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left third: main title
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isProcessing && progressMessage != null) progressMessage else message,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Start
                )

                // Progress bar when processing
                if (isProcessing) {
                    Spacer(modifier = Modifier.height(4.dp))
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

            Spacer(modifier = Modifier.width(8.dp))

            // Right two-thirds: two columns of data
            Row(
                modifier = Modifier.weight(2f),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Left data column: azimuth, GPS
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // True bearing (from GPS, relative to true north)
                    val trueBearing = magneticBearing ?: gpsState?.currentFix?.bearing
                    if (trueBearing != null) {
                        Text(
                            text = "Курс: ${String.format("%.0f", trueBearing)}°",
                            color = DataGreen,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // Route azimuth
                    azimuth?.let { az ->
                        Text(
                            text = "Азимут: ${String.format("%.1f", az)}°",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    // Current distance from start point
                    currentDistanceFromStart?.let { dist ->
                        Text(
                            text = "Дист: ${String.format("%.0f", dist)} м",
                            color = DataGreen,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }

                // Right data column: GPS accuracy, scale
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // GPS status
                    gpsState?.let { gps ->
                        val gpsText = when {
                            !gps.isGpsEnabled -> "GPS: выкл"
                            gps.accuracyLevel == AccuracyLevel.NO_FIX -> "GPS: —"
                            else -> "GPS: ${String.format("%.0f", gps.currentFix!!.accuracy)} м"
                        }
                        val gpsColor = when {
                            !gps.isGpsEnabled -> Color.Gray
                            gps.accuracyLevel == AccuracyLevel.NO_FIX -> GpsRed
                            gps.accuracyLevel == AccuracyLevel.LOW_ACCURACY -> GpsYellow
                            else -> GpsGreen
                        }
                        Text(
                            text = gpsText,
                            color = gpsColor,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    // Azimuth placeholder to keep columns balanced
                    if (azimuth != null && currentDistanceFromStart == null) {
                        Text(
                            text = " ",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}