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
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import ru.bondarenko.orientvibe.ng.model.TrackPoint
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Кастомный View-компонент интерактивной карты с поддержкой панорамирования,
 * масштабирования, вращения и отрисовки слоев наложения (Overlays).
 */
open class CustomImageView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field?.let { if (!it.isRecycled) it.recycle() }
            field = value
            mapScale = 1.0
            currentRotation = 0.0
            mapPanX = 0.0
            mapPanY = 0.0
            routeOverlay.startPoint = null
            routeOverlay.finishPoint = null
            routeOverlay.tapListener = null
            routeOverlay.dragListener = null
            controlPointOverlay.controlsBoundingBoxes = emptyList()
            controlPointOverlay.numbersBoundingBoxes = emptyList()
            trackOverlay.trackPoints = emptyList()
            trackOverlay.calibration = null
            trackOverlay.currentFix = null
            invalidate()
        }

    val controlPointOverlay = ControlPointOverlay()
    val northIndicator = NorthIndicator()
    val routeOverlay = RouteOverlay()
    val trackOverlay = TrackOverlay()

    var dragListener: MapDragListener? = null
    var tapListener: MapTapListener? = null

    var mapPanX: Double = 0.0
    var mapPanY: Double = 0.0
    var mapScale: Double = 1.0
    var mapTransformApplied: Boolean = false

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null

    private var currentRotation = 0.0
    private var preNavPanX = 0.0
    private var preNavPanY = 0.0
    private var preNavRotation = 0.0
    private var preNavScale = 1.0
    private var isInteractionEnabled = true

    private var savedDragListener: MapDragListener? = null
    private var savedTapListener: MapTapListener? = null

    private val scaleMin = 0.2
    private val scaleMax = 10.0
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val tempPointBuf = FloatArray(2)
    private var isMatrixPreparedForFrame = false

    init {
        routeOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
        routeOverlay.viewToSourceCoord = { x, y -> this.viewToSourceCoord(x, y) }
        controlPointOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
        trackOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
        trackOverlay.sourceToViewCoordFast = { x, y, out -> this.sourceToViewCoordFast(x, y, out) }
    }

    var mapRotation = 0.0
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

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true
        override fun onScaleEnd(detector: ScaleGestureDetector) {}

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            if (b.width <= 0 || b.height <= 0) return false

            val oldScale = mapScale
            mapScale = (mapScale * detector.scaleFactor).coerceIn(scaleMin, scaleMax)

            val focusSrcX = ((detector.focusX - width / 2.0f) - mapPanX) / oldScale
            val focusSrcY = ((detector.focusY - height / 2.0f) - mapPanY) / oldScale

            mapPanX -= (mapScale - oldScale) * focusSrcX
            mapPanY -= (mapScale - oldScale) * focusSrcY
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            mapPanX -= distanceX.toDouble()
            mapPanY -= distanceY.toDouble()
            invalidate()
            return true
        }
    })

    fun setPan(x: Double, y: Double) {
        mapPanX = x
        mapPanY = y
        invalidate()
    }

    private fun computeImageMatrix() {
        val b = bitmap
        if (b == null || b.width <= 0 || b.height <= 0) {
            imageMatrix.reset()
            inverseMatrix.reset()
            return
        }
        imageMatrix.reset()
        imageMatrix.setTranslate(-b.width / 2.0f, -b.height / 2.0f)
        imageMatrix.postScale(mapScale.toFloat(), mapScale.toFloat())
        imageMatrix.postRotate(currentRotation.toFloat())
        imageMatrix.postTranslate((width / 2.0f + mapPanX).toFloat(), (height / 2.0f + mapPanY).toFloat())
        imageMatrix.invert(inverseMatrix)
    }

    fun prepareImageMatrixForFrame() {
        computeImageMatrix()
        isMatrixPreparedForFrame = true
    }

    /** Нулевые аллокации: пишет экранные координаты в outPoint без пересчёта матрицы */
    fun sourceToViewCoordFast(x: Double, y: Double, outPoint: PointF): Boolean {
        val b = bitmap ?: return false
        val w = b.width.toDouble()
        val h = b.height.toDouble()
        if (w <= 0.0 || h <= 0.0) return false

        if (!isMatrixPreparedForFrame) {
            computeImageMatrix()
        }

        val size = if (w < h) w else h
        tempPointBuf[0] = ((x * size) + (w - size) / 2.0).toFloat()
        tempPointBuf[1] = ((y * size) + (h - size) / 2.0).toFloat()
        imageMatrix.mapPoints(tempPointBuf)
        outPoint.x = tempPointBuf[0]
        outPoint.y = tempPointBuf[1]
        return true
    }

    fun sourceToViewCoord(x: Double, y: Double): PointF? {
        val res = PointF()
        return if (sourceToViewCoordFast(x, y, res)) res else null
    }

    fun viewToSourceCoord(x: Double, y: Double): PointF {
        computeImageMatrix()
        val pts = floatArrayOf(x.toFloat(), y.toFloat())
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun updateControlsBoundingBoxes(boxes: List<BoundingBox>) {
        controlPointOverlay.controlsBoundingBoxes = boxes
        controlPointOverlay.debugDrawAllBoxes = true
        invalidate()
    }

    fun updateNumbersBoundingBoxes(boxes: List<BoundingBox>) {
        controlPointOverlay.numbersBoundingBoxes = boxes
        controlPointOverlay.debugDrawAllBoxes = true
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
        routeOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
        routeOverlay.viewToSourceCoord = { x, y -> this.viewToSourceCoord(x, y) }
        controlPointOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
        trackOverlay.sourceToViewCoord = { x, y -> this.sourceToViewCoord(x, y) }
    }

    protected fun applyMapTransform() {
        val sp = routeOverlay.startPoint
        val fp = routeOverlay.finishPoint
        if (sp == null || fp == null) return

        val b = bitmap ?: return
        val sWidth = b.width.toDouble()
        val sHeight = b.height.toDouble()

        if (sWidth > 0.0 && sHeight > 0.0) {
            val dPixels = MapGeometry.convertToPixels(fp.x - sp.x, fp.y - sp.y, sWidth, sHeight)
            val routeAngle = Math.toDegrees(atan2(dPixels.x.toDouble(), -dPixels.y.toDouble()))
            currentRotation = -routeAngle % 360.0

            val routeLength = sqrt(((dPixels.x * dPixels.x) + (dPixels.y * dPixels.y)).toDouble())
            val targetHeight = 0.8 * height
            mapScale = if (routeLength > 0.0) (targetHeight / routeLength).coerceIn(scaleMin, scaleMax) else 1.0

            mapPanX = 0.0
            mapPanY = 0.0
            computeImageMatrix()

            val midSrcPx = MapGeometry.convertToPixels((sp.x + fp.x) / 2.0, (sp.y + fp.y) / 2.0, sWidth, sHeight)
            val mid = floatArrayOf(midSrcPx.x, midSrcPx.y)
            imageMatrix.mapPoints(mid)

            mapPanX += (width / 2.0f - mid[0]).toDouble()
            mapPanY += (height / 2.0f - mid[1]).toDouble()

            mapTransformApplied = true
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractionEnabled) return false
//        updateOverlayCoords()

        if (northIndicator.handleTouchEvent(event)) {
            invalidate()
            return true
        }
        if (routeOverlay.handleTouchEvent(event)) {
            invalidate()
            return true
        }

        val handledScale = scaleDetector.onTouchEvent(event)
        return gestureDetector.onTouchEvent(event) || handledScale
    }

    protected open fun drawOverlays(canvas: Canvas, b: Bitmap) {
        val hasOverlays = controlPointOverlay.controlsBoundingBoxes.isNotEmpty() ||
                routeOverlay.startPoint != null || routeOverlay.finishPoint != null ||
                trackOverlay.trackPoints.isNotEmpty() || trackOverlay.currentFix != null
        if (hasOverlays) {
            controlPointOverlay.draw(canvas, b.width, b.height)
            routeOverlay.draw(canvas)
            trackOverlay.draw(canvas)
            val savedAngle = northIndicator.angle
            northIndicator.angle = savedAngle - mapRotation
            northIndicator.draw(canvas)
            northIndicator.angle = savedAngle
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (b.width <= 0 || b.height <= 0) return
        if (mapRotation != 0.0 && !mapTransformApplied) {
            applyMapTransform()
        }
        prepareImageMatrixForFrame()
        val saved = canvas.save()
        canvas.concat(imageMatrix)
        if (!b.isRecycled) {
            canvas.drawBitmap(b, 0.0f, 0.0f, null)
        } else {
            bitmap = null
        }
        canvas.restoreToCount(saved)

        drawOverlays(canvas, b)
        isMatrixPreparedForFrame = false
    }
}