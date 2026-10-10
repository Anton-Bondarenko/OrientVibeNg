package ru.bondarenko.orientvibe.ng.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.TrackPoint
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import kotlin.math.atan2
import kotlin.math.sqrt

open class CustomImageView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            if (field != null && !field!!.isRecycled) field!!.recycle()
            field = value

            // Сброс состояния при загрузке нового изображения карты
            mapScale = 1.0
            currentRotation = 0.0
            mapPanX = 0.0
            mapPanY = 0.0
            proportion = 1.44
            routeOverlay.startPoint = null
            routeOverlay.finishPoint = null
            routeOverlay.tapListener = null
            routeOverlay.dragListener = null
            controlPointOverlay.controlsboundingBoxes = emptyList()
            controlPointOverlay.numbersBoundingBoxes = emptyList()
            trackOverlay.trackPoints = emptyList()
            trackOverlay.calibration = null
            trackOverlay.currentFix = null

            invalidate()
        }

    protected var mapScale: Double = 1.0
    protected var currentRotation: Double = 0.0
    protected var mapPanX: Double = 0.0
    protected var mapPanY: Double = 0.0
    protected var proportion: Double = 1.44

    val northIndicator = NorthIndicator()
    val routeOverlay = RouteOverlay()
    val controlPointOverlay = ControlPointOverlay()
    val trackOverlay = TrackOverlay()

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null
    var tapListener: MapTapListener? = null
    var dragListener: MapDragListener? = null

    private var preNavScale: Double = 1.0
    private var preNavRotation: Double = 0.0
    private var preNavPanX: Double = 0.0
    private var preNavPanY: Double = 0.0
    private var savedTapListener: MapTapListener? = null
    private var savedDragListener: MapDragListener? = null

    var isInteractionEnabled: Boolean = true
    var mapRotation: Double = 0.0
        set(value) {
            val previous = field
            field = value
            if (previous == 0.0 && value != 0.0) {
                preNavScale = mapScale
                preNavRotation = currentRotation
                preNavPanX = mapPanX
                preNavPanY = mapPanY
                savedTapListener = routeOverlay.tapListener
                savedDragListener = routeOverlay.dragListener
                routeOverlay.tapListener = null
                routeOverlay.dragListener = null
                isInteractionEnabled = false
            } else if (previous != 0.0 && value == 0.0) {
                mapScale = preNavScale
                currentRotation = preNavRotation
                mapPanX = preNavPanX
                mapPanY = preNavPanY
                routeOverlay.tapListener = savedTapListener
                routeOverlay.dragListener = savedDragListener
                isInteractionEnabled = true
                invalidate()
            }
            mapTransformApplied = false
            invalidate()
        }
    var mapTransformApplied: Boolean = false

    private val scaleMin = 0.2
    private val scaleMax = 10.0

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    fun setPan(x: Double, y: Double) {
        mapPanX = x
        mapPanY = y
        invalidate()
    }

    fun setZoom(s: Double) {
        mapScale = s.coerceIn(scaleMin, scaleMax)
        invalidate()
    }

    fun applyRotation(r: Double) {
        currentRotation = r % 360f
        invalidate()
    }

    private fun computeImageMatrix() {
        val sWidth = bitmap?.width?.toDouble() ?: 0.0
        val sHeight = bitmap?.height?.toDouble() ?: 0.0
        if (sWidth <= 0 || sHeight <= 0) {
            imageMatrix.reset()
            inverseMatrix.reset()
            return
        }
        imageMatrix.reset()
        imageMatrix.setTranslate(-sWidth.toFloat() / 2f, -sHeight.toFloat() / 2f)
        imageMatrix.postScale(mapScale.toFloat(), mapScale.toFloat())
        imageMatrix.postRotate(currentRotation.toFloat())
        imageMatrix.postTranslate((width / 2f + mapPanX).toFloat(), (height / 2f + mapPanY).toFloat())
        imageMatrix.invert(inverseMatrix)
    }

    fun sourceToViewCoord(x: Double, y: Double): PointF? {
        val sWidth = bitmap?.width?.toDouble() ?: 0.0
        val sHeight = bitmap?.height?.toDouble() ?: 0.0
        if (sWidth <= 0|| sHeight <= 0) {
            imageMatrix.reset()
            inverseMatrix.reset()
            return null
        }
        computeImageMatrix()
        val pts = floatArrayOf((x * sWidth).toFloat(), (y * sHeight).toFloat())
        imageMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun viewToSourceCoord(x: Double, y: Double): PointF {
        computeImageMatrix()
        val pts = floatArrayOf(x.toFloat(), y.toFloat())
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun updateControlsBoundingBoxes(boxes: List<BoundingBox>) {
        controlPointOverlay.controlsboundingBoxes = boxes
        controlPointOverlay.debugDrawAllBoxes = true  // <-- отладка: убрать после теста
        invalidate()
    }

    fun updateNumbersBoundingBoxes(boxes: List<BoundingBox>) {
        controlPointOverlay.numbersBoundingBoxes = boxes
        controlPointOverlay.debugDrawAllBoxes = true  // <-- отладка: убрать после теста
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

    fun assignTapListener(listener: MapTapListener?) {
        routeOverlay.tapListener = listener
    }

    fun assignDragListener(listener: MapDragListener?) {
        routeOverlay.dragListener = listener
    }

    fun updateTrackPoints(points: List<TrackPoint>) {
        trackOverlay.trackPoints = points
        invalidate()
    }

    fun updateCurrentFix(fix: GpsFix?) {
        trackOverlay.currentFix = fix
        invalidate()
    }

    fun updateCalibration(cal: MapCalibration?) {
        trackOverlay.calibration = cal
        invalidate()
    }

    fun updateNorthAngle(angle: Double) {
        trackOverlay.northAngle = angle
        invalidate()
    }

    protected fun updateOverlayCoords() {
        routeOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
        routeOverlay.viewToSourceCoord = { x, y -> viewToSourceCoord(x, y) }
        controlPointOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
        trackOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
    }

    protected open fun applyMapTransform() {
        val sp = routeOverlay.startPoint
        val fp = routeOverlay.finishPoint
        if (sp == null || fp == null) return

        val sWidth = bitmap?.width?.toDouble() ?: 0.0
        val sHeight = bitmap?.height?.toDouble() ?: 0.0
        if (sWidth <= 0 || sHeight <= 0) return

        val dx = (fp.x - sp.x) * sWidth
        val dy = (fp.y - sp.y) * sHeight
        val routeAngle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toDouble()

        val rawOrientation = -routeAngle
        applyRotation(rawOrientation)

        val routeLength = sqrt(
            (dx * dx + dy * dy).toDouble()
        ).toDouble()

        val targetHeight = height * 0.8
        val newScale = if (routeLength > 0) targetHeight / routeLength else 1.0
        setZoom(newScale)

        mapPanX = 0.0
        mapPanY = 0.0
        invalidate()

        computeImageMatrix()
        val midSrcX = ((sp.x + fp.x) / 2.0) * sWidth
        val midSrcY = ((sp.y + fp.y) / 2.0) * sHeight
        val mid = floatArrayOf(midSrcX.toFloat(), midSrcY.toFloat())
        imageMatrix.mapPoints(mid)
        mapPanX += width / 2f - mid[0]
        mapPanY += height / 2f - mid[1]
        invalidate()

        mapTransformApplied = true
    }

    private val scaleDetector =
        ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val sWidth = bitmap?.width?.toDouble() ?: 0.0
                val sHeight = bitmap?.height?.toDouble() ?: 0.0
                if (sWidth <= 0 || sHeight <= 0) return false

                val oldScale = mapScale
                mapScale *= detector.scaleFactor
                mapScale = mapScale.coerceIn(scaleMin, scaleMax)

                // 1. ПРАВИЛЬНЫЙ ПЕРЕВОД: Из экранных координат фокуса в координаты картинки (Source)
                // Формула: screenX = (sourceX * oldScale) + (width / 2f) + mapPanX
                // Отсюда выражаем sourceX:
                val focusSrcX = (detector.focusX - width / 2f - mapPanX) / oldScale
                val focusSrcY = (detector.focusY - height / 2f - mapPanY) / oldScale

                // 2. ИСПРАВЛЕННОЕ СМЕЩЕНИЕ: Чтобы точка под пальцами осталась на том же месте экрана,
                // новое смещение должно компенсировать изменение масштаба для этой точки:
                // Изменение расстояния от центра картинки до фокуса * разница масштабов
                mapPanX -= focusSrcX * (mapScale - oldScale)
                mapPanY -= focusSrcY * (mapScale - oldScale)

                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {}
        })

    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onShowPress(e: MotionEvent) {}
            override fun onSingleTapUp(e: MotionEvent): Boolean = false
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                mapPanX -= distanceX
                mapPanY -= distanceY
                invalidate()
                return true
            }

            override fun onLongPress(e: MotionEvent) {}
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean = false
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractionEnabled) return false

        updateOverlayCoords()

        if (northIndicator.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sWidth = bitmap?.width?.toDouble() ?: 0.0
        val sHeight = bitmap?.height?.toDouble() ?: 0.0
        if (sWidth <= 0 || sHeight <= 0) return

        if (mapRotation != 0.0 && !mapTransformApplied) {
            applyMapTransform()
        }

        updateOverlayCoords()
        computeImageMatrix()

        val saved = canvas.save()
        canvas.concat(imageMatrix)
        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            canvas.drawBitmap(currentBitmap, 0f, 0f, null)
        } else if (currentBitmap == null) {
            // skip draw
        } else {
            bitmap = null
        }
        canvas.restoreToCount(saved)

        val hasOverlays = controlPointOverlay.controlsboundingBoxes.isNotEmpty() ||
                routeOverlay.startPoint != null ||
                routeOverlay.finishPoint != null ||
                trackOverlay.trackPoints.isNotEmpty() ||
                trackOverlay.currentFix != null

        if (hasOverlays) {
            controlPointOverlay.draw(canvas)
            routeOverlay.draw(canvas)
            trackOverlay.draw(canvas)

            val savedAngle = northIndicator.angle
            northIndicator.angle -= mapRotation
            northIndicator.draw(canvas)
            northIndicator.angle = savedAngle
        }
    }
}
