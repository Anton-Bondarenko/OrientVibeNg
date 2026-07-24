package ru.bondarenko.orientvibe.ng.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
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
import ru.bondarenko.orientvibe.ng.viewmodel.RoutePoint

class OverlayMapView(
    context: Context,
    private var boundingBoxes: List<BoundingBox> = emptyList(),
    private var startPoint: RoutePoint? = null,
    private var finishPoint: RoutePoint? = null,
    private var tapListener: MapTapListener? = null,
    private var dragListener: MapDragListener? = null
) : SubsamplingScaleImageView(context) {

    val northIndicator = NorthIndicator()
    val routeOverlay = RouteOverlay()

    private val controlCirclePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val controlFillPaint = Paint().apply {
        color = Color.RED
        alpha = 64
        style = Paint.Style.FILL
    }

    fun updateBoundingBoxes(boxes: List<BoundingBox>) {
        boundingBoxes = boxes
        invalidate()
    }

    fun updateStartPoint(point: RoutePoint?) {
        routeOverlay.startPoint = point
        invalidate()
    }

    fun updateFinishPoint(point: RoutePoint?) {
        routeOverlay.finishPoint = point
        invalidate()
    }

    fun setTapListener(listener: MapTapListener?) {
        routeOverlay.tapListener = listener
    }

    fun setDragListener(listener: MapDragListener?) {
        routeOverlay.dragListener = listener
    }

    private fun updateRouteOverlayCoords() {
        val sWidth = getSWidth().toFloat()
        val sHeight = getSHeight().toFloat()
        routeOverlay.imageDimensions = Pair(sWidth, sHeight)
        routeOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
        routeOverlay.viewToSourceCoord = { x, y -> viewToSourceCoord(x, y) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateRouteOverlayCoords()

        // Let NorthIndicator try first
        if (northIndicator.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        // Let RouteOverlay try
        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boundingBoxes.isEmpty() && startPoint == null && finishPoint == null) return

        val sWidth = getSWidth().toFloat()
        val sHeight = getSHeight().toFloat()
        if (sWidth <= 0 || sHeight <= 0) return

        updateRouteOverlayCoords()

        // Draw control point circles
        for (box in boundingBoxes) {
            val centerRelX = (box.left + box.right) / 2
            val centerRelY = (box.top + box.bottom) / 2
            val boxRelWidth = box.right - box.left
            val boxRelHeight = box.bottom - box.top

            val centerX = centerRelX * sWidth
            val centerY = centerRelY * sHeight
            val boxWidth = boxRelWidth * sWidth
            val boxHeight = boxRelHeight * sHeight
            val radius = minOf(boxWidth, boxHeight) / 2

            val viewCenter = sourceToViewCoord(centerX, centerY) ?: continue
            val viewEdge = sourceToViewCoord(centerX + radius, centerY) ?: continue
            val viewRadius = (viewEdge.x - viewCenter.x).coerceAtLeast(4f)

            canvas.drawCircle(viewCenter.x, viewCenter.y, viewRadius, controlFillPaint)
            canvas.drawCircle(viewCenter.x, viewCenter.y, viewRadius, controlCirclePaint)
        }

        // Draw route overlay (start, finish, line, arrow)
        routeOverlay.draw(canvas)

        // Draw north indicator
        northIndicator.draw(canvas)
    }
}

@Composable
fun SubsamplingMapView(
    bitmap: Bitmap,
    boundingBoxes: List<BoundingBox> = emptyList(),
    startPoint: RoutePoint? = null,
    finishPoint: RoutePoint? = null,
    tapListener: MapTapListener? = null,
    dragListener: MapDragListener? = null,
    northAngle: Float = 0f,
    onNorthAngleChanged: ((Float) -> Unit)? = null,
    onNorthAngleReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val overlayView = remember { OverlayMapView(context) }

    DisposableEffect(bitmap) {
        overlayView.setImage(ImageSource.bitmap(bitmap))
        onDispose { }
    }

    DisposableEffect(boundingBoxes) {
        overlayView.updateBoundingBoxes(boundingBoxes)
        onDispose { }
    }

    DisposableEffect(startPoint, finishPoint) {
        overlayView.updateStartPoint(startPoint)
        overlayView.updateFinishPoint(finishPoint)
        onDispose { }
    }

    DisposableEffect(tapListener) {
        overlayView.setTapListener(tapListener)
        onDispose { overlayView.setTapListener(null) }
    }

    DisposableEffect(dragListener) {
        overlayView.setDragListener(dragListener)
        onDispose { overlayView.setDragListener(null) }
    }

    DisposableEffect(northAngle) {
        overlayView.northIndicator.angle = northAngle
        overlayView.invalidate()
        onDispose { }
    }

    DisposableEffect(onNorthAngleChanged, onNorthAngleReset) {
        val listener = object : NorthAngleListener {
            override fun onNorthAngleChanged(angleDegrees: Float) {
                onNorthAngleChanged?.invoke(angleDegrees)
            }
            override fun onNorthAngleReset() {
                onNorthAngleReset?.invoke()
            }
        }
        overlayView.northIndicator.listener = listener
        onDispose { overlayView.northIndicator.listener = null }
    }

    AndroidView(
        factory = { overlayView },
        modifier = modifier.fillMaxSize()
    )
}