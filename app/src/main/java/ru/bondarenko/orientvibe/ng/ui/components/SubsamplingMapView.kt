package ru.bondarenko.orientvibe.ng.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface MapTapListener {
    fun onMapTap(relativeX: Float, relativeY: Float)
}

interface MapDragListener {
    fun onStartPointDragged(relativeX: Float, relativeY: Float)
    fun onFinishPointDragged(relativeX: Float, relativeY: Float)
}

private const val HIT_RADIUS = 40f // view-space pixels for tap/drag detection

class OverlayMapView(
    context: Context,
    private var boundingBoxes: List<BoundingBox> = emptyList(),
    private var startPoint: RoutePoint? = null,
    private var finishPoint: RoutePoint? = null,
    private var tapListener: MapTapListener? = null,
    private var dragListener: MapDragListener? = null
) : SubsamplingScaleImageView(context) {

    private var dragging: Dragging = Dragging.NONE

    private enum class Dragging { NONE, START, FINISH }

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

    private val startPaint = Paint().apply {
        color = Color.rgb(0, 180, 0)
        style = Paint.Style.FILL
    }

    private val startStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val finishFillPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
    }

    private val finishStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val routeLinePaint = Paint().apply {
        color = Color.rgb(0, 180, 0)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        color = Color.rgb(0, 180, 0)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateBoundingBoxes(boxes: List<BoundingBox>) {
        boundingBoxes = boxes
        invalidate()
    }

    fun updateStartPoint(point: RoutePoint?) {
        startPoint = point
        invalidate()
    }

    fun updateFinishPoint(point: RoutePoint?) {
        finishPoint = point
        invalidate()
    }

    fun setTapListener(listener: MapTapListener?) {
        tapListener = listener
    }

    fun setDragListener(listener: MapDragListener?) {
        dragListener = listener
    }

    private fun sourceToView(p: RoutePoint): PointF? {
        val sWidth = getSWidth().toFloat()
        val sHeight = getSHeight().toFloat()
        if (sWidth <= 0 || sHeight <= 0) return null
        return sourceToViewCoord(p.x * sWidth, p.y * sHeight)
    }

    private fun hitTestStart(vx: Float, vy: Float): Boolean {
        val sp = startPoint ?: return false
        val vs = sourceToView(sp) ?: return false
        val dx = vx - vs.x
        val dy = vy - vs.y
        return dx * dx + dy * dy < HIT_RADIUS * HIT_RADIUS
    }

    private fun hitTestFinish(vx: Float, vy: Float): Boolean {
        val fp = finishPoint ?: return false
        val vf = sourceToView(fp) ?: return false
        val dx = vx - vf.x
        val dy = vy - vf.y
        return dx * dx + dy * dy < HIT_RADIUS * HIT_RADIUS
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val vx = event.x
        val vy = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    hitTestStart(vx, vy) -> Dragging.START
                    hitTestFinish(vx, vy) -> Dragging.FINISH
                    else -> Dragging.NONE
                }
                if (dragging != Dragging.NONE) {
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging != Dragging.NONE) {
                    val sourcePt = viewToSourceCoord(vx, vy)
                    if (sourcePt != null) {
                        val sWidth = getSWidth().toFloat()
                        val sHeight = getSHeight().toFloat()
                        if (sWidth > 0 && sHeight > 0) {
                            val relX = sourcePt.x / sWidth
                            val relY = sourcePt.y / sHeight
                            val d = dragListener
                            if (d != null) {
                                when (dragging) {
                                    Dragging.START -> d.onStartPointDragged(relX, relY)
                                    Dragging.FINISH -> d.onFinishPointDragged(relX, relY)
                                    Dragging.NONE -> {}
                                }
                            }
                        }
                    }
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != Dragging.NONE) {
                    dragging = Dragging.NONE
                    return true
                }
                // If not dragging, forward as tap
                val tl = tapListener
                if (tl != null) {
                    val sourcePt = viewToSourceCoord(vx, vy)
                    if (sourcePt != null) {
                        val sWidth = getSWidth().toFloat()
                        val sHeight = getSHeight().toFloat()
                        if (sWidth > 0 && sHeight > 0) {
                            tl.onMapTap(sourcePt.x / sWidth, sourcePt.y / sHeight)
                            return true
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (boundingBoxes.isEmpty() && startPoint == null && finishPoint == null) return

        val sWidth = getSWidth().toFloat()
        val sHeight = getSHeight().toFloat()
        if (sWidth <= 0 || sHeight <= 0) return

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

        val sp = startPoint
        val fp = finishPoint

        // Draw route line from start to finish
        if (sp != null && fp != null) {
            val sx = sp.x * sWidth
            val sy = sp.y * sHeight
            val fx = fp.x * sWidth
            val fy = fp.y * sHeight

            val viewS = sourceToViewCoord(sx, sy) ?: return
            val viewF = sourceToViewCoord(fx, fy) ?: return

            // Draw line
            canvas.drawLine(viewS.x, viewS.y, viewF.x, viewF.y, routeLinePaint)

            // Draw arrow at midpoint
            val midX = (viewS.x + viewF.x) / 2
            val midY = (viewS.y + viewF.y) / 2

            val dxLine = viewF.x - viewS.x
            val dyLine = viewF.y - viewS.y
            val len = sqrt((dxLine * dxLine + dyLine * dyLine).toDouble()).toFloat()
            if (len > 0) {
                val ux = dxLine / len
                val uy = dyLine / len

                val arrowSize = 30f
                val arrowAngle = 0.5f

                val path = Path().apply {
                    moveTo(midX + ux * arrowSize, midY + uy * arrowSize)
                    lineTo(
                        midX - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                                uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                        midY - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                                ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                    )
                    lineTo(
                        midX - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                                uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                        midY - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                                ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                    )
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }

        // Draw start point (triangle rotated to point toward finish)
        if (sp != null) {
            val sx = sp.x * sWidth
            val sy = sp.y * sHeight
            val viewS = sourceToViewCoord(sx, sy) ?: return

            // Calculate rotation angle from start toward finish
            val angle = if (fp != null) {
                atan2(
                    (fp.y - sp.y).toDouble(),
                    (fp.x - sp.x).toDouble()
                ).toFloat()
            } else {
                -(Math.PI.toFloat() / 2) // default: point up
            }

            val size = 30f
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            // Equilateral triangle — local apex points RIGHT (+X)
            // Then add 90° CCW rotation to align with the intended direction
            val h = size * 1.5f
            val w = size * 0.87f
            val p1x = h / 3   // apex at right
            val p1y = 0f
            val p2x = -h * 2 / 3
            val p2y = -w
            val p3x = -h * 2 / 3
            val p3y = w

            // Rotate and translate
            fun rotate(x: Float, y: Float): Pair<Float, Float> {
                return Pair(
                    viewS.x + x * cosA - y * sinA,
                    viewS.y + x * sinA + y * cosA
                )
            }

            val (r1x, r1y) = rotate(p1x, p1y)
            val (r2x, r2y) = rotate(p2x, p2y)
            val (r3x, r3y) = rotate(p3x, p3y)

            val path = Path().apply {
                moveTo(r1x, r1y)
                lineTo(r2x, r2y)
                lineTo(r3x, r3y)
                close()
            }
            canvas.drawPath(path, startPaint)
            canvas.drawPath(path, startStrokePaint)
        }

        // Draw finish point (double circle)
        if (fp != null) {
            val fx = fp.x * sWidth
            val fy = fp.y * sHeight
            val viewF = sourceToViewCoord(fx, fy) ?: return

            val outerRadius = 24f
            val innerRadius = 12f

            canvas.drawCircle(viewF.x, viewF.y, outerRadius, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, outerRadius, finishStrokePaint)
            canvas.drawCircle(viewF.x, viewF.y, innerRadius, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, innerRadius, finishStrokePaint)
        }
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

    AndroidView(
        factory = { overlayView },
        modifier = modifier.fillMaxSize()
    )
}