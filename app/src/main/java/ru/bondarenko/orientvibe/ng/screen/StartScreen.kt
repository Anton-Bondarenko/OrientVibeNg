package ru.bondarenko.orientvibe.ng.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BlueButtonColor = Color(0xFF1976D2)
private val GreenButtonColor = Color(0xFF388E3C)

@Composable
fun StartScreen(onManualMode: () -> Unit, onAutoMode: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "OrientVibe",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Button(
                onClick = onManualMode,
                colors = ButtonDefaults.buttonColors(containerColor = BlueButtonColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Ручной режим",
                    fontSize = 20.sp,
                    color = Color.White
                )
            }

            Button(
                onClick = onAutoMode,
                colors = ButtonDefaults.buttonColors(containerColor = GreenButtonColor),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Авто режим",
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        }
    }
}
