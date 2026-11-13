package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Слушатель событий интерактивного перетаскивания (Drag & Drop) контрольных маркеров старта и финиша.
 */
interface MapDragListener {
    fun onStartPointDragged(relativeX: Double, relativeY: Double)
    fun onFinishPointDragged(relativeX: Double, relativeY: Double)
}

/**
 * Слушатель событий короткого тапа по координатной матрице интерактивной карты.
 */
interface MapTapListener {
    fun onMapTap(relativeX: Double, relativeY: Double)
}

/**
 * Слой наложения (Overlay) для отображения и калибровки стартовой и финишной точек маршрута.
 */
class RouteOverlay {

    var startPoint: RoutePoint? = null
    var finishPoint: RoutePoint? = null
    var tapListener: MapTapListener? = null
    var dragListener: MapDragListener? = null
    var magneticBearing: Double? = null

    var sourceToViewCoord: ((Double, Double) -> PointF?)? = null
    var viewToSourceCoord: ((Double, Double) -> PointF?)? = null

    private var draggingMode = Dragging.NONE

    /**
     * Внутреннее перечисление состояния перетаскивания маркеров.
     */
    private enum class Dragging {
        NONE,
        START,
        FINISH
    }

    companion object {
        private const val HIT_RADIUS_SQ = 1600.0 // 40.0 * 40.0 пикселей (область захвата пальца)

        /**
         * Утилитарный метод вращения вершин треугольного маркера вокруг точки центра.
         */
        private fun rotatePoint(center: PointF, cosA: Double, sinA: Double, x: Double, y: Double): PointF {
            return PointF(
                (center.x + (x * cosA - y * sinA)).toFloat(),
                (center.y + (x * sinA + y * cosA)).toFloat()
            )
        }
    }

    // Стили кистей (Paint)
    private val startPaint = Paint().apply {
        color = Color.rgb(250, 129, 51) // ControlsRed
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val startStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        isAntiAlias = true
    }

    private val finishFillPaint = Paint().apply {
        color = Color.rgb(250, 129, 51)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val finishStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4.0f
        isAntiAlias = true
    }

    private val routeLinePaint = Paint().apply {
        color = Color.rgb(250, 129, 51)
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        isAntiAlias = true
    }

    private val arrowPaint = Paint().apply {
        color = Color.rgb(250, 129, 51)
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private fun sourceToView(p: RoutePoint): PointF? {
        return sourceToViewCoord?.invoke(p.x, p.y)
    }

    private fun hitTestStart(vx: Double, vy: Double): Boolean {
        val vs = startPoint?.let { sourceToView(it) } ?: return false
        val dx = vx - vs.x
        val dy = vy - vs.y
        return (dx * dx) + (dy * dy) < HIT_RADIUS_SQ
    }

    private fun hitTestFinish(vx: Double, vy: Double): Boolean {
        val vf = finishPoint?.let { sourceToView(it) } ?: return false
        val dx = vx - vf.x
        val dy = vy - vf.y
        return (dx * dx) + (dy * dy) < HIT_RADIUS_SQ
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val vx = event.x.toDouble()
        val vy = event.y.toDouble()

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingMode = when {
                    hitTestStart(vx, vy) -> Dragging.START
                    hitTestFinish(vx, vy) -> Dragging.FINISH
                    else -> Dragging.NONE
                }
                draggingMode != Dragging.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingMode != Dragging.NONE) {
                    viewToSourceCoord?.invoke(event.x.toDouble(), event.y.toDouble())?.let { sourcePt ->
                        when (draggingMode) {
                            Dragging.START -> dragListener?.onStartPointDragged(sourcePt.x.toDouble(), sourcePt.y.toDouble())
                            Dragging.FINISH -> dragListener?.onFinishPointDragged(sourcePt.x.toDouble(), sourcePt.y.toDouble())
                            else -> {}
                        }
                    }
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingMode != Dragging.NONE) {
                    draggingMode = Dragging.NONE
                    true
                } else {
                    viewToSourceCoord?.invoke(event.x.toDouble(), event.y.toDouble())?.let { sourcePt ->
                        tapListener?.onMapTap(sourcePt.x.toDouble(), sourcePt.y.toDouble())
                        true
                    } ?: false
                }
            }
            else -> false
        }
    }

    fun draw(canvas: Canvas) {
        val toView = sourceToViewCoord ?: return
        val sp = startPoint
        val fp = finishPoint

        // 1. Отрисовка соединительной линии маршрута и направляющей стрелки
        if (sp != null && fp != null) {
            val viewS = toView(sp.x, sp.y) ?: return
            val viewF = toView(fp.x, fp.y) ?: return

            canvas.drawLine(viewS.x, viewS.y, viewF.x, viewF.y, routeLinePaint)

            val midX = (viewS.x + viewF.x) / 2.0f
            val midY = (viewS.y + viewF.y) / 2.0f
            val dxLine = viewF.x - viewS.x
            val dyLine = viewF.y - viewS.y
            val len = sqrt((dxLine * dxLine + dyLine * dyLine).toDouble())

            if (len > 0.0) {
                val ux = dxLine.toDouble() / len
                val uy = dyLine.toDouble() / len
                val path = Path().apply {
                    moveTo((midX + ux * 30.0).toFloat(), (midY + uy * 30.0).toFloat())
                    lineTo(
                        (midX - (ux * 30.0 * cos(0.5)) - (uy * 30.0 * sin(0.5))).toFloat(),
                        (midY - (uy * 30.0 * cos(0.5)) + (ux * 30.0 * sin(0.5))).toFloat()
                    )
                    lineTo(
                        (midX - (ux * 30.0 * cos(0.5)) + (uy * 30.0 * sin(0.5))).toFloat(),
                        (midY - (uy * 30.0 * cos(0.5)) - (ux * 30.0 * sin(0.5))).toFloat()
                    )
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }

        // 2. Отрисовка треугольного маркера СТАРТА (с учетом направления на финиш)
        if (sp != null) {
            val viewS = toView(sp.x, sp.y) ?: return
            val angle = if (fp != null) atan2(fp.y - sp.y, fp.x - sp.x) else -1.5707963267948966 // -90 градусов в радианах (вверх)

            val cosA = cos(angle)
            val sinA = sin(angle)
            val h = 1.5f * 30.0f
            val w = 0.87f * 30.0f

            val p1 = rotatePoint(viewS, cosA, sinA, (h / 3.0f).toDouble(), 0.0)
            val p2 = rotatePoint(viewS, cosA, sinA, (-h * 2.0f / 3.0f).toDouble(), (-w).toDouble())
            val p3 = rotatePoint(viewS, cosA, sinA, (-h * 2.0f / 3.0f).toDouble(), w.toDouble())

            val startPath = Path().apply {
                moveTo(p1.x, p1.y)
                lineTo(p2.x, p2.y)
                lineTo(p3.x, p3.y)
                close()
            }
            canvas.drawPath(startPath, startPaint)
            canvas.drawPath(startPath, startStrokePaint)
        }

        // 3. Отрисовка концентрического маркера ФИНИША
        if (fp != null) {
            val viewF = toView(fp.x, fp.y) ?: return
            canvas.drawCircle(viewF.x, viewF.y, 24.0f, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, 24.0f, finishStrokePaint)
            canvas.drawCircle(viewF.x, viewF.y, 12.0f, finishFillPaint)
            canvas.drawCircle(viewF.x, viewF.y, 12.0f, finishStrokePaint)
        }
    }
}
