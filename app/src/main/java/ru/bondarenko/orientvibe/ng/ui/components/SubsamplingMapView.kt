package ru.bondarenko.orientvibe.ng.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import ru.bondarenko.orientvibe.ng.model.*
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils

/**
 * Кастомный View-компонент, расширяющий CustomImageView для поддержки
 * интерактивной автоматической привязки (AutoBind) и отображения расчетных контрольных точек.
 */
class OverlayMapView(
    context: Context,
    startPoint: RoutePoint? = null,
    finishPoint: RoutePoint? = null,
    tapListener: MapTapListener? = null,
    dragListener: MapDragListener? = null
) : CustomImageView(context) {

    var autoBindActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var calibrationImageDims: Pair<Double?, Double?>? = null
        set(value) {
            field = value
            invalidate()
        }

    var calibrationPointBGps: GpsCoordinate? = null
        set(value) {
            field = value
            invalidate()
        }

    var gpsFixImagePos: Pair<Double?, Double?>? = null
        set(value) {
            field = value
            invalidate()
        }

    var onAutoBindTapCallback: ((Double, Double) -> Boolean)? = null

    init {
        updateStartPoint(startPoint)
        updateFinishPoint(finishPoint)
        assignTapListener(tapListener)
        assignDragListener(dragListener)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (controlPointOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }
        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        // Логика обработки тапа в режиме автоматической привязки (AutoBind)
        if (autoBindActive && event.action == MotionEvent.ACTION_UP) {
            val sourcePt: PointF = viewToSourceCoord(event.x.toDouble(), event.y.toDouble())
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                val bitmapW = currentBitmap.width.toDouble()
                val callback = onAutoBindTapCallback
                if (callback != null) {
                    val handled = callback((sourcePt.x / bitmapW), (sourcePt.y / bitmapW))
                    if (handled) {
                        invalidate()
                    }
                }
                return true
            }
            return false
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentBitmap = bitmap ?: return
        val sWidth = currentBitmap.width.toDouble()
        val sHeight = currentBitmap.height.toDouble()
        if (sWidth <= 0.0 || sHeight <= 0.0) return

        if (mapRotation != 0.0 && !mapTransformApplied) {
            applyMapTransform()
        }
        updateOverlayCoords()

        // Отрисовка базовых слоев
        controlPointOverlay.draw(canvas, currentBitmap.width, currentBitmap.height)
        routeOverlay.draw(canvas)
        trackOverlay.draw(canvas)

        // Отрисовка маркера текущей позиции GPS фикса в режиме привязки
        if (autoBindActive) {
            gpsFixImagePos?.let { fixPos ->
                val fixX = fixPos.first
                val fixY = fixPos.second
                if (fixX != null && fixY != null) {
                    sourceToViewCoord(fixX, fixY)?.let { viewPt ->
                        val paint = Paint().apply {
                            color = Color.argb(180, 76, 175, 80) // Полупрозрачный зеленый
                            style = Paint.Style.FILL
                            isAntiAlias = true
                        }
                        val stroke = Paint().apply {
                            color = Color.WHITE
                            strokeWidth = 2.0f
                            style = Paint.Style.STROKE
                            isAntiAlias = true
                        }
                        canvas.drawCircle(viewPt.x, viewPt.y, 12.0f, paint)
                        canvas.drawCircle(viewPt.x, viewPt.y, 12.0f, stroke)
                    }
                }
            }
        }

        // Отрисовка расчетной финишной точки B на основе калибровки
        calibrationPointBGps?.let { targetGps ->
            trackOverlay.calibration?.let { cal ->
                val pairGpsToImage = MapCalibrationUtils.gpsToImage(targetGps, cal, trackOverlay.northAngle)
                val imgX = pairGpsToImage?.first ?: return@let
                val imgY = pairGpsToImage?.second ?: return@let
                sourceToViewCoord(imgX, imgY)?.let { viewPt ->
                        val paint = Paint().apply {
                            color = Color.argb(180, 156, 39, 176) // Полупрозрачный фиолетовый
                            style = Paint.Style.FILL
                            isAntiAlias = true
                        }
                        val stroke = Paint().apply {
                            color = Color.WHITE
                            strokeWidth = 2.0f
                            style = Paint.Style.STROKE
                            isAntiAlias = true
                        }
                        canvas.drawCircle(viewPt.x, viewPt.y, 14.0f, paint)
                        canvas.drawCircle(viewPt.x, viewPt.y, 14.0f, stroke)
                }
            }
        }

        // Вращение и рендеринг стрелки севера
        val savedAngle = northIndicator.angle
        northIndicator.angle = savedAngle - mapRotation
        northIndicator.draw(canvas)
        northIndicator.angle = savedAngle
    }
}

/**
 * Декларативный Compose-компонент (Интерактивная Карта) для встраивания в макеты Jetpack Compose
 */
@Composable
fun SubsamplingMapView(
    bitmap: Bitmap,
    controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    startPoint: RoutePoint? = null,
    finishPoint: RoutePoint? = null,
    tapListener: MapTapListener? = null,
    dragListener: MapDragListener? = null,
    northAngle: Double = 0.0,
    onNorthAngleChanged: ((Double) -> Unit)? = null,
    onNorthAngleReset: (() -> Unit)? = null,
    mapRotation: Double = 0.0,
    trackPoints: List<TrackPoint> = emptyList(),
    calibrationVersionTrigger: Int = 0,
    calibration: MapCalibration? = null,
    currentFix: GpsFix? = null,
    modifier: Modifier = Modifier,
    autoBindActive: Boolean = false,
    gpsFixImagePos: Pair<Double, Double>? = null,
    calibrationPointBGps: GpsCoordinate? = null,
    calibrationImageDims: Pair<Double, Double>? = null,
    onAutoBindTap: ((Double, Double) -> Boolean)? = null,
    showAllBoxes: Boolean = false
) {
    // Управляем жизненным циклом и удержанием состояния Android View внутри Compose
    val overlayView = rememberAndroidView { context ->
        OverlayMapView(context)
    }

    // Реактивные мосты синхронизации данных Compose State -> Android View Properties
    DisposableEffect(bitmap) {
        overlayView.bitmap = bitmap
        onDispose { }
    }

    DisposableEffect(controlsBoundingBoxes, showAllBoxes) {
        overlayView.updateControlsBoundingBoxes(controlsBoundingBoxes)
        overlayView.controlPointOverlay.showAllBoxes = showAllBoxes
        onDispose { }
    }

    DisposableEffect(numbersBoundingBoxes, showAllBoxes) {
        overlayView.updateNumbersBoundingBoxes(numbersBoundingBoxes)
        overlayView.controlPointOverlay.showAllBoxes = showAllBoxes
        onDispose { }
    }

    DisposableEffect(startPoint, finishPoint) {
        overlayView.updateStartPoint(startPoint)
        overlayView.updateFinishPoint(finishPoint)
        onDispose { }
    }

    DisposableEffect(tapListener) {
        overlayView.assignTapListener(tapListener)
        onDispose { }
    }

    DisposableEffect(dragListener) {
        overlayView.assignDragListener(dragListener)
        onDispose { }
    }

    DisposableEffect(northAngle) {
        overlayView.updateNorthAngle(northAngle)
        onDispose { }
    }

    DisposableEffect(onNorthAngleChanged, onNorthAngleReset) {
        overlayView.northIndicator.listener = object : NorthAngleListener {
            override fun onNorthAngleChanged(angleDegrees: Double) {
                onNorthAngleChanged?.invoke(angleDegrees)
            }
            override fun onNorthAngleReset() {
                onNorthAngleReset?.invoke()
            }
        }
        onDispose { }
    }

    DisposableEffect(mapRotation) {
        overlayView.mapRotation = mapRotation
        onDispose { }
    }

    DisposableEffect(trackPoints, calibrationVersionTrigger) {
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

    DisposableEffect(autoBindActive) {
        overlayView.autoBindActive = autoBindActive
        onDispose { }
    }

    DisposableEffect(calibrationPointBGps, gpsFixImagePos) {
        overlayView.calibrationPointBGps = calibrationPointBGps
        overlayView.gpsFixImagePos = gpsFixImagePos
        onDispose { }
    }

    DisposableEffect(onAutoBindTap) {
        overlayView.onAutoBindTapCallback = onAutoBindTap
        onDispose { }
    }

    // Отрисовка очищенной ноды View в иерархии Compose
    AndroidView(
        factory = { overlayView },
        modifier = modifier.fillMaxSize()
    )
}
/**
 Вспомогательный метод инициализации View, исключающий дублирование фабрик в Compose группах
 */
@Composable
private fun rememberAndroidView(factory: (Context) -> OverlayMapView): OverlayMapView {
    val androidxContext = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember { factory(androidxContext) }
}