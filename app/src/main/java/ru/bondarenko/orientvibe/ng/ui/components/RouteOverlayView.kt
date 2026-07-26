package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
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

class RouteOverlay {

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null
    var tapListener: MapTapListener? = null
    var dragListener: MapDragListener? = null
    var magneticBearing: Float? = null

    private var dragging: Dragging = Dragging.NONE

    private enum class Dragging { NONE, START, FINISH }

    private val startPaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.FILL
    }

    private val startStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val finishFillPaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.FILL
    }

    private val finishStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val routeLinePaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Source-to-view coordinate conversion — set externally
    var sourceToViewCoord: ((Float, Float) -> android.graphics.PointF?)? = null
    var viewToSourceCoord: ((Float, Float) -> android.graphics.PointF?)? = null
    var imageDimensions: Pair<Float, Float>? = null // (width, height) in source pixels

    private fun sourceToView(p: RoutePoint): android.graphics.PointF? {
        val (sWidth, sHeight) = imageDimensions ?: return null
        if (sWidth <= 0 || sHeight <= 0) return null
        return sourceToViewCoord?.invoke(p.x * sWidth, p.y * sHeight)
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

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val vx = event.x
        val vy = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    hitTestStart(vx, vy) -> Dragging.START
                    hitTestFinish(vx, vy) -> Dragging.FINISH
                    else -> Dragging.NONE
                }
                return dragging != Dragging.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging != Dragging.NONE) {
                    val (sWidth, sHeight) = imageDimensions ?: return true
                    val viewToSource = viewToSourceCoord ?: return true
                    val sourcePt = viewToSource(vx, vy)
                    if (sourcePt != null && sWidth > 0 && sHeight > 0) {
                        val relX = sourcePt.x / sWidth
                        val relY = sourcePt.y / sHeight
                        when (dragging) {
                            Dragging.START -> dragListener?.onStartPointDragged(relX, relY)
                            Dragging.FINISH -> dragListener?.onFinishPointDragged(relX, relY)
                            Dragging.NONE -> {}
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
                // Forward as tap
                val (sWidth, sHeight) = imageDimensions ?: return false
                val viewToSource = viewToSourceCoord ?: return false
                val sourcePt = viewToSource(vx, vy)
                if (sourcePt != null && sWidth > 0 && sHeight > 0) {
                    tapListener?.onMapTap(sourcePt.x / sWidth, sourcePt.y / sHeight)
                    return true
                }
            }
        }
        return false
    }

    fun draw(canvas: Canvas) {
        val sp = startPoint
        val fp = finishPoint
        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return
        val toView = sourceToViewCoord ?: return

        // Draw route line from start to finish
        if (sp != null && fp != null) {
            val sx = sp.x * sWidth
            val sy = sp.y * sHeight
            val fx = fp.x * sWidth
            val fy = fp.y * sHeight

            val viewS = toView(sx, sy) ?: return
            val viewF = toView(fx, fy) ?: return

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
            val viewS = toView(sx, sy) ?: return

            val angle = if (fp != null) {
                val (sWidth, sHeight) = imageDimensions ?: 0f to 0f
                atan2(
                    ((fp.y - sp.y) * sHeight).toDouble(),
                    ((fp.x - sp.x) * sWidth).toDouble()
                ).toFloat()
            } else {
                -(Math.PI.toFloat() / 2)
            }

            val size = 30f
            val cosA = cos(angle.toDouble()).toFloat()
            val sinA = sin(angle.toDouble()).toFloat()

            val h = size * 1.5f
            val w = size * 0.87f
            val p1x = h / 3
            val p1y = 0f
            val p2x = -h * 2 / 3
            val p2y = -w
            val p3x = -h * 2 / 3
            val p3y = w

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
            val viewF = toView(fx, fy) ?: return

            val outerRadius = 24f
            val innerRadius = 12f

            canvas.drawCircle(viewF.x, viewF.y, outerRadius, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, outerRadius, finishStrokePaint)
            canvas.drawCircle(viewF.x, viewF.y, innerRadius, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, innerRadius, finishStrokePaint)
        }
    }
}
