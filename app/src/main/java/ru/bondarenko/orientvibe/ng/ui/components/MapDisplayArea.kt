package ru.bondarenko.orientvibe.ng.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.image.ImageCapture
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel

/**
 * Загрузчик карты, управляющий состояниями Coil-рендеринга и вызова системной Камеры/Галереи.
 */
@Composable
fun MapLoader(
    viewModel: MapViewModel,
    modifier: Modifier = Modifier,
    onMapLoaded: (Uri?) -> Unit = {}
) {
    val context = LocalContext.current
    val mapState by viewModel.mapState.collectAsState()

    var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }
    var cameraLoaded by remember { mutableStateOf(false) }

    // Реакция на выбор файла из галереи устройства
    LaunchedEffect(pendingGalleryUri) {
        pendingGalleryUri?.let { uri ->
            onMapLoaded(uri)
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingGalleryUri = uri
        }
    }

    val camera = rememberCameraSource(context) { imageCapture ->
        if (!cameraLoaded) {
            viewModel.loadImageFromBitmap(imageCapture.bitmap, imageCapture.uri)
            onMapLoaded(imageCapture.uri)
            cameraLoaded = true
        }
    }

    val isLoading by remember { derivedStateOf { mapState.bitmap == null } }

    Box(modifier = modifier.fillMaxSize()) {
        if (isLoading || mapState.bitmap == null) {
            EmptyMapPlaceholder(
                onCameraClick = { camera.launchCamera() },
                onGalleryClick = {
                    galleryPicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            )
        } else {
            val imageUri = mapState.imageUri
            AsyncImage(
                model = imageUri?.toString(),
                contentDescription = "Map image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

/**
 * Контейнер отображения области предпросмотра карты.
 */
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

    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .shadow(
                elevation = shadowElevation,
                shape = RoundedCornerShape(24.dp),
                ambientColor = primaryColor.copy(alpha = 0.3f),
                spotColor = primaryColor.copy(alpha = 0.5f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                    onGalleryClick = onGalleryClick
                )
            }
        }
    }
}

/**
 * Заглушка-плейсхолдер с подробным гайдом и кнопками активации съемки/импорта для пустой карты.
 */
@Composable
private fun EmptyMapPlaceholder(
    onCameraClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val primaryContainerColor = MaterialTheme.colorScheme.primaryContainer

        Box(
            modifier = Modifier
                .size(120.dp)
                .background(
                    color = primaryContainerColor.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "Сфотографируйте карту",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "1. Найдите первый КП \r\n" +
                    "2. Откройте приложение и загрузите карту. Самый надёжный способ сфотографировать родной камерой телефона и потом загрузить из галереи. Сделайте снимок карты, севером кверху. Минимизируйте искажения, чтобы все стороны карты были параллельны границам экрана. После загрузки карты, если GPS система уже поймала сигнал можно сразу двигаться. Система сама найдёт КП с номером \"1\" и привяжет место, где вы запустили приложение.\r\n" +
                    "3. Найдите второе КП и откалибруйте масштаб и магнитное склонение, нажав вторую (зелёную) кнопку. Дальше всё должно переключаться само\r\n" +
                    "В любой момент вы можете вручную выбрать текущий КП и сделать привязку или калибровку нажав на соответствующую кнопку",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * Компактный чип-переключатель для быстрых медиа-действий с эффектом тени Material 3.
 */
@Composable
private fun SmallActionChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(12.dp),
                clip = false,
                ambientColor = primaryColor.copy(alpha = 0.2f)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
