package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface MapTapListener {
    fun onMapTap(relativeX: Double, relativeY: Double)
}

interface MapDragListener {
    fun onStartPointDragged(relativeX: Double, relativeY: Double)
    fun onFinishPointDragged(relativeX: Double, relativeY: Double)
}

private const val HIT_RADIUS = 40f // view-space pixels for tap/drag detection

class RouteOverlay {

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null
    var tapListener: MapTapListener? = null
    var dragListener: MapDragListener? = null
    var magneticBearing: Double? = null

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
    var sourceToViewCoord: ((Double, Double) -> android.graphics.PointF?)? = null
    var viewToSourceCoord: ((Double, Double) -> android.graphics.PointF?)? = null

    private fun sourceToView(p: RoutePoint): android.graphics.PointF? {
        return sourceToViewCoord?.invoke(p.x, p.y)
    }

    private fun hitTestStart(vx: Double, vy: Double): Boolean {
        val sp = startPoint ?: return false
        val vs = sourceToView(sp) ?: return false
        val dx = vx - vs.x
        val dy = vy - vs.y
        return dx * dx + dy * dy < HIT_RADIUS * HIT_RADIUS
    }

    private fun hitTestFinish(vx: Double, vy: Double): Boolean {
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
                    hitTestStart(vx.toDouble(), vy.toDouble()) -> Dragging.START
                    hitTestFinish(vx.toDouble(), vy.toDouble()) -> Dragging.FINISH
                    else -> Dragging.NONE
                }
                return dragging != Dragging.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging != Dragging.NONE) {
                    val viewToSource = viewToSourceCoord ?: return true
                    val sourcePt = viewToSource(vx.toDouble(), vy.toDouble())
                    if (sourcePt != null) {
                        val relX = sourcePt.x
                        val relY = sourcePt.y
                        when (dragging) {
                            Dragging.START -> dragListener?.onStartPointDragged(relX.toDouble(), relY.toDouble())
                            Dragging.FINISH -> dragListener?.onFinishPointDragged(relX.toDouble(), relY.toDouble())
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
                val viewToSource = viewToSourceCoord ?: return false
                val sourcePt = viewToSource(vx.toDouble(), vy.toDouble())
                if (sourcePt != null) {
                    tapListener?.onMapTap(sourcePt.x.toDouble(), sourcePt.y.toDouble())
                    return true
                }
            }
        }
        return false
    }

    fun draw(canvas: Canvas) {
        val sp = startPoint
        val fp = finishPoint
        val toView = sourceToViewCoord ?: return

        // Draw route line from start to finish
        if (sp != null && fp != null) {
            val sx = sp.x
            val sy = sp.y
            val fx = fp.x
            val fy = fp.y

            val viewS = toView(sx, sy) ?: return
            val viewF = toView(fx, fy) ?: return

            // Draw line
            canvas.drawLine(viewS.x, viewS.y, viewF.x, viewF.y, routeLinePaint)

            // Draw arrow at midpoint
            val midX = (viewS.x + viewF.x) / 2
            val midY = (viewS.y + viewF.y) / 2

            val dxLine = viewF.x - viewS.x
            val dyLine = viewF.y - viewS.y
            val len = sqrt((dxLine * dxLine + dyLine * dyLine).toDouble()).toDouble()
            if (len > 0) {
                val ux = dxLine / len
                val uy = dyLine / len

                val arrowSize = 30.0
                val arrowAngle = 0.5

                val path = Path().apply {
                    moveTo((midX + ux * arrowSize).toFloat(), (midY + uy * arrowSize).toFloat())
                    lineTo(
                        (midX - ux * arrowSize * cos(arrowAngle.toDouble()).toDouble() -
                                uy * arrowSize * sin(arrowAngle.toDouble()).toDouble()).toFloat(),
                        (midY - uy * arrowSize * cos(arrowAngle.toDouble()).toDouble() +
                                ux * arrowSize * sin(arrowAngle.toDouble()).toDouble()).toFloat()
                    )
                    lineTo(
                        (midX - ux * arrowSize * cos(arrowAngle.toDouble()).toDouble() +
                                uy * arrowSize * sin(arrowAngle.toDouble()).toDouble()).toFloat(),
                        (midY - uy * arrowSize * cos(arrowAngle.toDouble()).toDouble() -
                                ux * arrowSize * sin(arrowAngle.toDouble()).toDouble()).toFloat()
                    )
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }

        // Draw start point (triangle rotated to point toward finish)
        if (sp != null) {
            val sx = sp.x
            val sy = sp.y
            val viewS = toView(sx, sy) ?: return

            val angle = if (fp != null) {
                atan2(
                    ((fp.y - sp.y)).toDouble(),
                    ((fp.x - sp.x)).toDouble()
                ).toDouble()
            } else {
                -(Math.PI.toDouble() / 2)
            }

            val size = 30f
            val cosA = cos(angle.toDouble()).toDouble()
            val sinA = sin(angle.toDouble()).toDouble()

            val h = size * 1.5f
            val w = size * 0.87f
            val p1x = h / 3
            val p1y = 0f
            val p2x = -h * 2 / 3
            val p2y = -w
            val p3x = -h * 2 / 3
            val p3y = w

            fun rotate(x: Double, y: Double): Pair<Double, Double> {
                return Pair(
                    viewS.x + x * cosA - y * sinA,
                    viewS.y + x * sinA + y * cosA
                )
            }

            val (r1x, r1y) = rotate(p1x.toDouble(), p1y.toDouble())
            val (r2x, r2y) = rotate(p2x.toDouble(), p2y.toDouble())
            val (r3x, r3y) = rotate(p3x.toDouble(), p3y.toDouble())

            val path = Path().apply {
                moveTo(r1x.toFloat(), r1y.toFloat())
                lineTo(r2x.toFloat(), r2y.toFloat())
                lineTo(r3x.toFloat(), r3y.toFloat())
                close()
            }
            canvas.drawPath(path, startPaint)
            canvas.drawPath(path, startStrokePaint)
        }

        // Draw finish point (double circle)
        if (fp != null) {
            val fx = fp.x
            val fy = fp.y
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
