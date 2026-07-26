package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

interface NorthAngleListener {
    fun onNorthAngleChanged(angleDegrees: Float)
    fun onNorthAngleReset()
}

private const val NORTH_DOT_HIT_RADIUS = 60f
private const val NORTH_LINE_LENGTH = 160f
private const val NORTH_DOT_RADIUS = 10f
private const val NORTH_ANCHOR_X = 150f // left offset from view edge
private const val NORTH_ANCHOR_Y = 350f  // top offset from view edge

private const val ZERO_ANGLE = 180

class NorthIndicator {

    var angle: Float = 0f // degrees, 0 = up, positive = CW, range -45..45
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
    private fun anchor(): Pair<Float, Float> {
        return Pair(NORTH_ANCHOR_X, NORTH_ANCHOR_Y)
    }

    /** Bottom endpoint of the line, computed from angle */
    private fun lineEnd(): Pair<Float, Float> {
        val (ax, ay) = anchor()
        val angleRad = Math.toRadians(angle.toDouble() + ZERO_ANGLE).toFloat()
        val endX = ax + NORTH_LINE_LENGTH * sin(angleRad)
        val endY = ay + NORTH_LINE_LENGTH * cos(angleRad.toDouble()).toFloat()
        return Pair(endX, endY)
    }

    fun lineEndTest(vx: Float, vy: Float): Boolean {
        val (ax, ay) = lineEnd()
        val dx = vx - ax
        val dy = vy - ay
        return dx * dx + dy * dy < NORTH_DOT_HIT_RADIUS * NORTH_DOT_HIT_RADIUS
    }

    fun ancorTest(vx: Float, vy: Float): Boolean {
        val (ax, ay) = anchor()
        val dx = vx - ax
        val dy = vy - ay
        return dx * dx + dy * dy < NORTH_DOT_HIT_RADIUS * NORTH_DOT_HIT_RADIUS
    }

    fun handleTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (lineEndTest(event.x, event.y)) {
                    dragging = true
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val (ax, ay) = lineEnd()
                    val dx = (ax - event.x)/2 // для точности
                    // Angle from vertical: up = 0°, positive CW
                    val newAngle = (Math.toDegrees(
                        atan2((-NORTH_LINE_LENGTH).toDouble(), dx.toDouble())) + 90f).toFloat()
                    angle = newAngle.coerceIn(-45f, 45f)
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
                if (ancorTest(event.x, event.y)) {
                    angle = 0f
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
        canvas.drawLine(ax, ay, ex, ey, linePaint)

        // Dot at anchor
        canvas.drawCircle(ax, ay, NORTH_DOT_RADIUS, dotFillPaint)
        canvas.drawCircle(ax, ay, NORTH_DOT_RADIUS, dotStrokePaint)

        // "N" text
        canvas.drawText("N", ax, ey - textPaint.textSize, textPaint)
    }
}