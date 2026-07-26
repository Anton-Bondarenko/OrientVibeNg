package ru.bondarenko.orientvibe.ng.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.TrackPoint
import ru.bondarenko.orientvibe.ng.viewmodel.BoundingBox
import ru.bondarenko.orientvibe.ng.viewmodel.RoutePoint
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

class OverlayMapView(
    context: Context,
    startPoint: RoutePoint? = null,
    finishPoint: RoutePoint? = null,
    tapListener: MapTapListener? = null,
    dragListener: MapDragListener? = null
) : CustomImageView(context) {

    init {
        this.startPoint = startPoint
        this.finishPoint = finishPoint
        this.tapListener = tapListener
        this.dragListener = dragListener
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        updateOverlayCoords()

        if (northIndicator.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sWidth = bitmap?.width?.toFloat() ?: 0f
        val sHeight = bitmap?.height?.toFloat() ?: 0f
        if (sWidth <= 0 || sHeight <= 0) return

        if (mapRotation != 0f && !mapTransformApplied) {
            applyMapTransform()
        }

        updateOverlayCoords()

        controlPointOverlay.draw(canvas)
        routeOverlay.draw(canvas)
        trackOverlay.draw(canvas)

        val savedAngle = northIndicator.angle
        northIndicator.angle -= mapRotation
        northIndicator.draw(canvas)
        northIndicator.angle = savedAngle
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
    mapRotation: Float = 0f,
    trackPoints: List<TrackPoint> = emptyList(),
    calibration: MapCalibration? = null,
    currentFix: GpsFix? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val overlayView = remember { OverlayMapView(context) }

    DisposableEffect(bitmap) {
        overlayView.bitmap = bitmap
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
        overlayView.assignTapListener(tapListener)
        onDispose { overlayView.assignTapListener(null) }
    }

    DisposableEffect(dragListener) {
        overlayView.assignDragListener(dragListener)
        onDispose { overlayView.assignDragListener(null) }
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

    DisposableEffect(mapRotation) {
        overlayView.mapRotation = mapRotation
        overlayView.mapTransformApplied = false
        overlayView.invalidate()
        onDispose { }
    }

    DisposableEffect(trackPoints) {
        overlayView.updateTrackPoints(trackPoints)
        onDispose { }
    }

    DisposableEffect(calibration) {
        overlayView.updateCalibration(calibration)
        onDispose { }
    }

    DisposableEffect(currentFix) {
        overlayView.updateCurrentFix(currentFix)
        onDispose { }
    }

    DisposableEffect(northAngle, calibration) {
        overlayView.updateNorthAngle(northAngle)
        onDispose { }
    }

    AndroidView(
        factory = { overlayView },
        modifier = modifier.fillMaxSize()
    )
}
