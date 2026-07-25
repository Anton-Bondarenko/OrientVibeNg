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
import ru.bondarenko.orientvibe.ng.viewmodel.BoundingBox
import ru.bondarenko.orientvibe.ng.viewmodel.RoutePoint
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

open class CustomImageView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            if (field?.isRecycled == true) {
                field = null
            }
            field = value
            invalidate()
        }

    protected var mapScale: Float = 1f
    protected var currentRotation: Float = 0f
    protected var mapPanX: Float = 0f
    protected var mapPanY: Float = 0f

    val northIndicator = NorthIndicator()
    val routeOverlay = RouteOverlay()
    val controlPointOverlay = ControlPointOverlay()

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null
    var tapListener: MapTapListener? = null
    var dragListener: MapDragListener? = null

    private var preNavScale: Float = 1f
    private var preNavRotation: Float = 0f
    private var preNavPanX: Float = 0f
    private var preNavPanY: Float = 0f

    var mapRotation: Float = 0f
        set(value) {
            val previous = field
            field = value
            if (previous == 0f && value != 0f) {
                preNavScale = mapScale
                preNavRotation = currentRotation
                preNavPanX = mapPanX
                preNavPanY = mapPanY
            } else if (previous != 0f && value == 0f) {
                mapScale = preNavScale
                currentRotation = preNavRotation
                mapPanX = preNavPanX
                mapPanY = preNavPanY
                invalidate()
            }
            mapTransformApplied = false
            invalidate()
        }
    var mapTransformApplied: Boolean = false

    private val scaleMin = 0.2f
    private val scaleMax = 10f

    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    fun setPan(x: Float, y: Float) {
        mapPanX = x
        mapPanY = y
        invalidate()
    }

    fun setZoom(s: Float) {
        mapScale = s.coerceIn(scaleMin, scaleMax)
        invalidate()
    }

    fun applyRotation(r: Float) {
        currentRotation = r % 360f
        invalidate()
    }

    private fun computeImageMatrix() {
        val sWidth = bitmap?.width?.toFloat() ?: 0f
        val sHeight = bitmap?.height?.toFloat() ?: 0f
        if (sWidth <= 0 || sHeight <= 0) {
            imageMatrix.reset()
            inverseMatrix.reset()
            return
        }
        imageMatrix.reset()
        imageMatrix.setTranslate(-sWidth / 2f, -sHeight / 2f)
        imageMatrix.postScale(mapScale, mapScale)
        imageMatrix.postRotate(currentRotation)
        imageMatrix.postTranslate(width / 2f + mapPanX, height / 2f + mapPanY)
        imageMatrix.invert(inverseMatrix)
    }

    fun sourceToViewCoord(x: Float, y: Float): PointF? {
        computeImageMatrix()
        val pts = floatArrayOf(x, y)
        imageMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun viewToSourceCoord(x: Float, y: Float): PointF? {
        computeImageMatrix()
        val pts = floatArrayOf(x, y)
        inverseMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    fun updateBoundingBoxes(boxes: List<BoundingBox>) {
        controlPointOverlay.boundingBoxes = boxes
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

    protected fun updateOverlayCoords() {
        val sWidth = bitmap?.width?.toFloat() ?: 0f
        val sHeight = bitmap?.height?.toFloat() ?: 0f
        routeOverlay.imageDimensions = Pair(sWidth, sHeight)
        routeOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
        routeOverlay.viewToSourceCoord = { x, y -> viewToSourceCoord(x, y) }
        controlPointOverlay.imageDimensions = Pair(sWidth, sHeight)
        controlPointOverlay.sourceToViewCoord = { x, y -> sourceToViewCoord(x, y) }
    }

    protected open fun applyMapTransform() {
        val sp = routeOverlay.startPoint
        val fp = routeOverlay.finishPoint
        if (sp == null || fp == null) return

        val sWidth = bitmap?.width?.toFloat() ?: 0f
        val sHeight = bitmap?.height?.toFloat() ?: 0f
        if (sWidth <= 0 || sHeight <= 0) return

        val dx = (fp.x - sp.x) * sWidth
        val dy = (fp.y - sp.y) * sHeight
        val routeAngle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()

        val rawOrientation = -routeAngle
        applyRotation(rawOrientation)

        val routeLength = sqrt(
            (dx * dx + dy * dy).toDouble()
        ).toFloat()

        val targetHeight = height * 0.8f
        val newScale = if (routeLength > 0) targetHeight / routeLength else 1f
        setZoom(newScale)

        mapPanX = 0f
        mapPanY = 0f
        invalidate()

        mapTransformApplied = true
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            mapScale *= detector.scaleFactor
            mapScale = mapScale.coerceIn(scaleMin, scaleMax)
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {}
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onShowPress(e: MotionEvent) {}
        override fun onSingleTapUp(e: MotionEvent): Boolean = false
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            mapPanX -= distanceX
            mapPanY -= distanceY
            invalidate()
            return true
        }
        override fun onLongPress(e: MotionEvent) {}
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = false
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
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

        val sWidth = bitmap?.width?.toFloat() ?: 0f
        val sHeight = bitmap?.height?.toFloat() ?: 0f
        if (sWidth <= 0 || sHeight <= 0) return

        if (mapRotation != 0f && !mapTransformApplied) {
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

        val hasOverlays = controlPointOverlay.boundingBoxes.isNotEmpty() ||
            routeOverlay.startPoint != null ||
            routeOverlay.finishPoint != null

        if (hasOverlays) {
            controlPointOverlay.draw(canvas)
            routeOverlay.draw(canvas)

            val savedAngle = northIndicator.angle
            northIndicator.angle -= mapRotation
            northIndicator.draw(canvas)
            northIndicator.angle = savedAngle
        }
    }
}