package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import ru.bondarenko.orientvibe.ng.viewmodel.BoundingBox

@Composable
fun SubsamplingMapView(
    bitmap: Bitmap,
    boundingBoxes: List<BoundingBox> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Create a bitmap with bounding boxes drawn on it
    val bitmapWithBoxes = remember(bitmap, boundingBoxes) {
        drawBoundingBoxes(bitmap, boundingBoxes)
    }
    
    AndroidView(
        factory = {
            SubsamplingScaleImageView(context)
        },
        update = { imageView ->
            imageView.setImage(ImageSource.bitmap(bitmapWithBoxes))
        },
        modifier = modifier.fillMaxSize()
    )
}

private fun drawBoundingBoxes(bitmap: Bitmap, boxes: List<BoundingBox>): Bitmap {
    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)
    
    val circlePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    val fillPaint = Paint().apply {
        color = Color.RED
        alpha = 64
        style = Paint.Style.FILL
    }
    
    boxes.forEach { box ->
        // Calculate center of bounding box
        val centerX = (box.left + box.right) / 2 * bitmap.width
        val centerY = (box.top + box.bottom) / 2 * bitmap.height
        
        // Calculate radius based on box size
        val boxWidth = (box.right - box.left) * bitmap.width
        val boxHeight = (box.bottom - box.top) * bitmap.height
        val radius = minOf(boxWidth, boxHeight) / 2
        
        // Draw filled circle
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        
        // Draw circle outline
        canvas.drawCircle(centerX, centerY, radius, circlePaint)
    }
    
    return mutableBitmap
}
