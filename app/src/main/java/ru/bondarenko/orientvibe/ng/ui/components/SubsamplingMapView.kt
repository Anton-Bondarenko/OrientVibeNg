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
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.TrackPoint
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.RoutePoint
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

    // ── Auto-bind GPS mode fields ──

    var autoBindActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var gpsFixImagePos: Pair<Float, Float>? = null
    var onAutoBindTapCallback: ((relX: Float, relY: Float) -> Boolean)? = null

    /** Calibration point B GPS for purple marker rendering */
    var calibrationPointBGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Image dimensions for purple point rendering (absolute pixels) */
    var calibrationImageDims: Pair<Float, Float>? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {

        if (northIndicator.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        // Auto-bind: intercept tap to check proximity to KP circles
        if (autoBindActive && event.action == android.view.MotionEvent.ACTION_UP) {
            val sourcePt = viewToSourceCoord(event.x, event.y) ?: return false
            val bitmapW = bitmap?.width?.toFloat() ?: return false
            val handled = onAutoBindTapCallback?.invoke(sourcePt.x / bitmapW, sourcePt.y / bitmapW)
            if (handled == true) {
                invalidate() // redraw to clear/close overlay
            }
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

        // Green circle indicator for GPS bind mode
        if (autoBindActive && gpsFixImagePos != null) {
            val pos = gpsFixImagePos!!
            val viewPt = sourceToViewCoord(pos.first, pos.second)
            if (viewPt != null) {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 76, 175, 80)
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                val stroke = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 255, 255, 255)
                    strokeWidth = 2f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawCircle(viewPt.x, viewPt.y, 12f, paint)
                canvas.drawCircle(viewPt.x, viewPt.y, 12f, stroke)
            }
        }

        // Purple circle for calibration point B (finish/second calibration point)
        // Uses gpsToImageAbs with northAngle so it shares the same rotated coordinate frame as
        // the green GPS dot — both rotate around pointA and render consistently on screen.
        calibrationPointBGps?.let { gpsB ->
            val cal = trackOverlay.calibration ?: return@let
            val imageCoords = MapCalibrationUtils.gpsToImageAbs(
                gpsB, cal, Pair(sWidth, sHeight), trackOverlay.northAngle
            ) ?: return@let
            val viewPt = sourceToViewCoord(imageCoords.first, imageCoords.second)
            if (viewPt != null) {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(180, 156, 39, 176) // purple #9C27B0
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                val stroke = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(200, 255, 255, 255)
                    strokeWidth = 2f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                canvas.drawCircle(viewPt.x, viewPt.y, 14f, paint)
                canvas.drawCircle(viewPt.x, viewPt.y, 14f, stroke)
            }
        }

        val savedAngle = northIndicator.angle
        northIndicator.angle -= mapRotation
        northIndicator.draw(canvas)
        northIndicator.angle = savedAngle
    }
}

@Composable
fun SubsamplingMapView(
    bitmap: Bitmap,
    controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    numbersBoundingBoxes: List<BoundingBox> = emptyList(),
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
    modifier: Modifier = Modifier,
    // ── Auto-bind GPS mode ──
    autoBindActive: Boolean = false,
    gpsFixImagePos: Pair<Float, Float>? = null,
    calibrationPointBGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate? = null,
    calibrationImageDims: Pair<Float, Float>? = null,
    onAutoBindTap: ((relX: Float, relY: Float) -> Boolean)? = null
) {
    val context = LocalContext.current

    val overlayView = remember { OverlayMapView(context) }

    DisposableEffect(bitmap) {
        overlayView.bitmap = bitmap
        onDispose { }
    }

    DisposableEffect(controlsBoundingBoxes) {
        overlayView.updateControlsBoundingBoxes(controlsBoundingBoxes)
        onDispose { }
    }

    DisposableEffect(numbersBoundingBoxes) {
        overlayView.updateNumbersBoundingBoxes(numbersBoundingBoxes)
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

    // ── Auto-bind GPS mode wiring ──
    DisposableEffect(autoBindActive) {
        overlayView.autoBindActive = autoBindActive
        overlayView.gpsFixImagePos = gpsFixImagePos
        onDispose { overlayView.autoBindActive = false }
    }

    DisposableEffect(calibrationPointBGps) {
        overlayView.calibrationPointBGps = calibrationPointBGps
        overlayView.calibrationImageDims = calibrationImageDims
        onDispose { overlayView.calibrationPointBGps = null }
    }

    DisposableEffect(onAutoBindTap) {
        overlayView.onAutoBindTapCallback = onAutoBindTap
        onDispose { overlayView.onAutoBindTapCallback = null }
    }

    AndroidView(
        factory = { overlayView },
        modifier = modifier.fillMaxSize()
    )
}
