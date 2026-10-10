package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

interface NorthAngleListener {
    fun onNorthAngleChanged(angleDegrees: Double)
    fun onNorthAngleReset()
}

private const val NORTH_DOT_HIT_RADIUS = 60.0
private const val NORTH_LINE_LENGTH = 160.0
private const val NORTH_DOT_RADIUS = 10.0
private const val NORTH_ANCHOR_X = 150.0 // left offset from view edge
private const val NORTH_ANCHOR_Y = 350.0  // top offset from view edge

private const val ZERO_ANGLE = 180

class NorthIndicator {

    var angle: Double = 0.0 // degrees, 0 = up, positive = CW, range -45..45
    var listener: NorthAngleListener? = null

    private var dragging: Boolean = false

    private val linePaint = Paint().apply {
        color = Color.argb(255, 0, 102, 245)
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val dotFillPaint = Paint().apply {
        color = Color.argb(255, 0, 102, 245)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val dotStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** The fixed anchor point (dot center) in view coordinates */
    private fun anchor(): Pair<Double, Double> {
        return Pair(NORTH_ANCHOR_X, NORTH_ANCHOR_Y)
    }

    /** Bottom endpoint of the line, computed from angle */
    private fun lineEnd(): Pair<Double, Double> {
        val (ax, ay) = anchor()
        val angleRad = Math.toRadians(angle.toDouble() + ZERO_ANGLE).toDouble()
        val endX = ax + NORTH_LINE_LENGTH * sin(angleRad)
        val endY = ay + NORTH_LINE_LENGTH * cos(angleRad.toDouble()).toDouble()
        return Pair(endX, endY)
    }

    fun lineEndTest(vx: Double, vy: Double): Boolean {
        val (ax, ay) = lineEnd()
        val dx = vx - ax
        val dy = vy - ay
        return dx * dx + dy * dy < NORTH_DOT_HIT_RADIUS * NORTH_DOT_HIT_RADIUS
    }

    fun ancorTest(vx: Double, vy: Double): Boolean {
        val (ax, ay) = anchor()
        val dx = vx - ax
        val dy = vy - ay
        return dx * dx + dy * dy < NORTH_DOT_HIT_RADIUS * NORTH_DOT_HIT_RADIUS
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (lineEndTest(event.x.toDouble(), event.y.toDouble())) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val (ax, _) = lineEnd()
                    val dx = (ax - event.x)/2 // для точности
                    // Angle from vertical: up = 0°, positive CW
                    val newAngle = (Math.toDegrees(
                        atan2((-NORTH_LINE_LENGTH).toDouble(), dx.toDouble())) + 90f).toDouble()
                    angle = newAngle.coerceIn(-45.0, 45.0)
                    listener?.onNorthAngleChanged(angle)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    return true
                }
                // Tap on dot -> reset
                if (ancorTest(event.x.toDouble(), event.y.toDouble())) {
                    angle = 0.0
                    listener?.onNorthAngleReset()
                    return true
                }
            }
        }
        return false
    }

    fun draw(canvas: Canvas) {
        val (ax, ay) = anchor()
        val (ex, ey) = lineEnd()

        // Line from anchor downward
        canvas.drawLine(ax.toFloat(), ay.toFloat(), ex.toFloat(), ey.toFloat(), linePaint)

        // Dot at anchor
        canvas.drawCircle(ax.toFloat(), ay.toFloat(), NORTH_DOT_RADIUS.toFloat(), dotFillPaint)
        canvas.drawCircle(ax.toFloat(), ay.toFloat(), NORTH_DOT_RADIUS.toFloat(), dotStrokePaint)

        // "N" text
        canvas.drawText("N", ax.toFloat(), ey.toFloat() - textPaint.textSize, textPaint)
    }
}