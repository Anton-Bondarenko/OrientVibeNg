package ru.bondarenko.orientvibe.ng.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bondarenko.orientvibe.ng.model.PanelButton
import ru.bondarenko.orientvibe.ng.model.PanelStep
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
import ru.bondarenko.orientvibe.ng.viewmodel.PlacingMode

// Fixed dark colors — independent of theme
private val DarkPanelBg = Color(0xFF1E1E1E)
private val DarkPanelBgVariant = Color(0xFF2A2A2A)
private val DarkDisabledBg = Color(0xFF2A2A2A)
private val DarkDisabledText = Color(0xFF888888)
private val DarkShadowColor = Color(0xFF6200EE)

@Composable
fun BottomButtonPanel(
    currentStep: PanelStep,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    modifier: Modifier = Modifier,
    placingMode: PlacingMode = PlacingMode.NONE
) {
    var navigationDirection by remember { mutableStateOf(0) } // 1 for forward, -1 for backward

    val slideInHorizontally = slideInHorizontally(
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    ) { fullWidth -> if (navigationDirection == 1) fullWidth else -fullWidth }

    val slideOutHorizontally = slideOutHorizontally(
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
    ) { fullWidth -> if (navigationDirection == 1) -fullWidth else fullWidth }

    AnimatedContent(
        targetState = currentStep,
        transitionSpec = {
            slideInHorizontally togetherWith slideOutHorizontally
        },
        label = "panelContent",
        modifier = modifier
    ) { step ->
        PanelContent(
            step = step,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onPreviousStep = {
                navigationDirection = -1
                onPreviousStep()
            },
            onNextStep = {
                navigationDirection = 1
                onNextStep()
            },
            placingMode = placingMode
        )
    }
}

@Composable
private fun PanelContent(
    step: PanelStep,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPreviousStep: () -> Unit,
    onNextStep: () -> Unit,
    placingMode: PlacingMode = PlacingMode.NONE
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = DarkShadowColor.copy(alpha = 0.3f),
                spotColor = DarkShadowColor.copy(alpha = 0.5f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkPanelBg
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left navigation arrow
            if (canGoBack) {
                NavigationButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    onClick = onPreviousStep,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(56.dp))
            }

            // Buttons
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                step.buttons.forEach { button ->
                    PanelButton(
                        button = button,
                        modifier = Modifier.weight(1f),
                        placingMode = placingMode
                    )
                }
            }

            // Right navigation arrow
            if (canGoForward) {
                NavigationButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    onClick = onNextStep,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Spacer(modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun PanelButton(
    button: PanelButton,
    modifier: Modifier = Modifier,
    placingMode: PlacingMode = PlacingMode.NONE
) {
    val scale by animateFloatAsState(
        targetValue = if (button.enabled) 1f else 0.95f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    // Background color does NOT change when active — only icon tint changes
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !button.enabled -> DarkDisabledBg
            else -> DarkPanelBg
        },
        animationSpec = tween(durationMillis = 200),
        label = "backgroundColor"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            !button.enabled -> DarkDisabledText
            else -> Color.White
        },
        animationSpec = tween(durationMillis = 200),
        label = "contentColor"
    )

    Button(
        onClick = button.onClick,
        enabled = button.enabled,
        modifier = modifier
            .height(56.dp)
            .shadow(
                elevation = if (button.enabled) 8.dp else 0.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = DarkShadowColor.copy(alpha = 0.3f)
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (button.icon != null && button.text.isNotEmpty()) {
                // Show both icon and text in a column
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Determine icon tint: calibrated = green, active = red, default = contentColor
                    val isThisButtonCalibrated = when (button.id) {
                        "here_start", "here_finish" -> button.isActive
                        else -> false
                    }
                    val isThisButtonActive = when (button.id) {
                        "start" -> placingMode == PlacingMode.PLACING_START
                        "finish" -> placingMode == PlacingMode.PLACING_FINISH
                        else -> false
                    }
                    val iconTint = when {
                        !button.enabled -> DarkDisabledText
                        isThisButtonCalibrated -> Color(0xFF4CAF50) // green
                        isThisButtonActive -> Color(ControlsRed)
                        else -> contentColor
                    }
                    Icon(
                        imageVector = button.icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = button.text,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        color = contentColor,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            } else if (button.icon != null) {
                // Show only icon
                val isThisButtonCalibrated = when (button.id) {
                    "here_start", "here_finish" -> button.isActive
                    else -> false
                }
                val isThisButtonActive = when (button.id) {
                    "start" -> placingMode == PlacingMode.PLACING_START
                    "finish" -> placingMode == PlacingMode.PLACING_FINISH
                    else -> false
                }
                val iconTint = when {
                    !button.enabled -> DarkDisabledText
                    isThisButtonCalibrated -> Color(0xFF4CAF50) // green
                    isThisButtonActive -> Color(ControlsRed)
                    else -> contentColor
                }
                Icon(
                    imageVector = button.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Text(
                    text = button.text,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun NavigationButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(
                color = DarkPanelBgVariant,
                shape = CircleShape
            )
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                ambientColor = DarkShadowColor.copy(alpha = 0.3f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}
