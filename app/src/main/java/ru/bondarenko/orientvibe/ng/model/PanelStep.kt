package ru.bondarenko.orientvibe.ng.model

data class PanelButton(
    val id: String,
    val text: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val isActive: Boolean = false
)

data class PanelStep(
    val id: String,
    val buttons: List<PanelButton>,
    val title: String = ""
)
