package ru.bondarenko.orientvibe.ng.ui.theme

import androidx.compose.ui.graphics.Color

val Green80 = Color(0xFF81C784)
val GreenGrey80 = Color(0xFFC8E6C9)
val LightGreen80 = Color(0xFFE8F5E9)

val Green40 = Color(0xFF4CAF50)
val GreenGrey40 = Color(0xFF81C784)
val LightGreen40 = Color(0xFFA5D6A7)

// ARGB color — kept as val (not const) because hex literal exceeds Int.MAX_VALUE; .toInt() truncates to RGB.
val ControlsRed = 0xFFA81253.toInt() // RGB: A81253