package ru.bondarenko.orientvibe.ng.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel

// ──────────────────────────────────────────────
// MapLoader — единый компонент для загрузки/отображения карты + камеры/галереи
// ──────────────────────────────────────────────

@Composable
fun MapLoader(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
    onMapLoaded: (uri: Uri?) -> Unit = {},
) {
    val mapState by viewModel.mapState.collectAsState()
    val context = LocalContext.current

    var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }

    // Загрузка из галереи — при появлении URI загружаем в viewModel
    androidx.compose.runtime.LaunchedEffect(pendingGalleryUri) {
        val uri = pendingGalleryUri ?: return@LaunchedEffect
        try {
            viewModel.loadImageFromUri(uri)
            onMapLoaded(uri)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            pendingGalleryUri = null
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { pendingGalleryUri = it }
    }

    // Камера
    var cameraLoaded by remember { mutableStateOf(false) }
    val camera = rememberCameraSource(
        context = context,
        onImageCaptured = { imageCapture ->
            if (!cameraLoaded) {
                viewModel.loadImageFromBitmap(imageCapture.bitmap, imageCapture.uri)
                onMapLoaded(imageCapture.uri)
                cameraLoaded = true
            }
        },
    )

    val isLoading by remember { derivedStateOf { mapState.bitmap == null } }

    Box(
        modifier = modifier
    ) {
        if (isLoading || mapState.bitmap == null) {
            // Overlay — выбор источника изображения
            EmptyMapPlaceholder(
                onCameraClick = { camera.launchCamera() },
                onGalleryClick = {
                    galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        } else {
            AsyncImage(
                model = mapState.imageUri?.toString(),
                contentDescription = "Map image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ──────────────────────────────────────────────
// Оставшиеся компоненты (для MainScreen)
// ──────────────────────────────────────────────

@Composable
fun MapDisplayArea(
    mapImageUri: String?,
    onCameraClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shadowElevation by animateDpAsState(
        targetValue = if (mapImageUri != null) 16.dp else 8.dp,
        animationSpec = tween(durationMillis = 300),
        label = "shadowElevation"
    )

    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(24.dp),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (mapImageUri != null) {
                AsyncImage(
                    model = mapImageUri,
                    contentDescription = "Map image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                EmptyMapPlaceholder(
                    onCameraClick = onCameraClick,
                    onGalleryClick = onGalleryClick,
                )
            }
        }
    }
}

@Composable
private fun EmptyMapPlaceholder(
    onCameraClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
        }

        Text(
            text = "Нет карты",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = "Загрузите или сфотографируйте карту",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmallActionChip(
                icon = Icons.Default.AddAPhoto,
                text = "Камера",
                onClick = onCameraClick
            )
            SmallActionChip(
                icon = Icons.Default.Image,
                text = "Галерея",
                onClick = onGalleryClick
            )
        }
    }
}

@Composable
private fun SmallActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(12.dp),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
