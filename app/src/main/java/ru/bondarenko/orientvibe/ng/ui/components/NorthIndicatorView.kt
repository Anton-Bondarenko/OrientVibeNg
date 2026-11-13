package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Слушатель событий интерактивного изменения угла стрелки направления севера.
 */
interface NorthAngleListener {
    fun onNorthAngleChanged(angleDegrees: Double)
    fun onNorthAngleReset()
}

/**
 * Интерактивный компонент (Стрелка Севера) для калибровки и отображения магнитного склонения карты.
 */
class NorthIndicator {

    var angle: Double = 0.0
    var listener: NorthAngleListener? = null

    private var isDragging = false

    // Конфигурационные константы компонента
    companion object {
        private const val NORTH_ANCHOR_X = 150.0
        private const val NORTH_ANCHOR_Y = 350.0
        private const val NORTH_LINE_LENGTH = 160.0
        private const val NORTH_DOT_RADIUS = 10.0f
        private const val NORTH_HIT_RADIUS_SQ = 3600.0 // 60.0 * 60.0
    }

    private val linePaint = Paint().apply {
        color = Color.argb(255, 0, 128, 245) // Насыщенный сине-голубой
        style = Paint.Style.STROKE
        strokeWidth = 10.0f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val dotFillPaint = Paint().apply {
        color = Color.argb(255, 0, 128, 245)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dotStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32.0f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun getAnchorPoint(): PointF {
        return PointF(NORTH_ANCHOR_X.toFloat(), NORTH_ANCHOR_Y.toFloat())
    }

    private fun getLineEndPoint(): PointF {
        val anchor = getAnchorPoint()
        val angleRad = Math.toRadians(angle + 180.0)
        val endX = sin(angleRad) * NORTH_LINE_LENGTH + anchor.x
        val endY = cos(angleRad) * NORTH_LINE_LENGTH + anchor.y
        return PointF(endX.toFloat(), endY.toFloat())
    }

    private fun checkLineEndHit(touchX: Double, touchY: Double): Boolean {
        val endPoint = getLineEndPoint()
        val dx = touchX - endPoint.x
        val dy = touchY - endPoint.y
        return (dx * dx) + (dy * dy) < NORTH_HIT_RADIUS_SQ
    }

    private fun checkAnchorHit(touchX: Double, touchY: Double): Boolean {
        val anchor = getAnchorPoint()
        val dx = touchX - anchor.x
        val dy = touchY - anchor.y
        return (dx * dx) + (dy * dy) < NORTH_HIT_RADIUS_SQ
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x.toDouble()
        val touchY = event.y.toDouble()

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (checkLineEndHit(touchX, touchY)) {
                    isDragging = true
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val anchor = getAnchorPoint()
                    val dx = anchor.x - touchX
                    val dy = touchY - anchor.y

                    // Вычисляем угол поворота относительно базового центра
                    val newAngle = Math.toDegrees(atan2(dy, dx)) - 90.0
                    angle = newAngle.coerceIn(-45.0, 45.0)

                    listener?.onNorthAngleChanged(angle)
                    true
                } else {
                    false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    true
                } else if (checkAnchorHit(touchX, touchY)) {
                    angle = 0.0
                    listener?.onNorthAngleReset()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun draw(canvas: Canvas) {
        val anchor = getAnchorPoint()
        val endPoint = getLineEndPoint()

        // Отрисовка стрелки азимута севера
        canvas.drawLine(anchor.x, anchor.y, endPoint.x, endPoint.y, linePaint)
        canvas.drawCircle(anchor.x, anchor.y, NORTH_DOT_RADIUS, dotFillPaint)
        canvas.drawCircle(anchor.x, anchor.y, NORTH_DOT_RADIUS, dotStrokePaint)

        // Рендеринг текстовой метки "N"
        canvas.drawText(
            "N",
            anchor.x,
            endPoint.y - textPaint.textSize,
            textPaint
        )
    }
}
