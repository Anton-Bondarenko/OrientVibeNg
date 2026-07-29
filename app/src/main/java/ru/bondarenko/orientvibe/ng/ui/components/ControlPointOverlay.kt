package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
import kotlin.math.sqrt

class ControlPointOverlay {

    var controlsboundingBoxes: List<BoundingBox> = emptyList()
    var numbersBoundingBoxes: List<BoundingBox> = emptyList()

    // Source-to-view coordinate conversion — set externally
    var sourceToViewCoord: ((Float, Float) -> android.graphics.PointF?)? = null
    var imageDimensions: Pair<Float, Float>? = null // (width, height) in source pixels

    private val controlCirclePaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val controlFillPaint = Paint().apply {
        color = ControlsRed
        alpha = 64
        style = Paint.Style.FILL
    }

    // Прямоугольники для боксов номеров (отрисовка границ)
    private val numberBoxPaint = Paint().apply {
        color = android.graphics.Color.rgb(255, 160, 0)  // оранжевый
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    fun draw(canvas: Canvas) {
        if (controlsboundingBoxes.isEmpty() && numbersBoundingBoxes.isEmpty()) return

        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return

        val toView = sourceToViewCoord ?: return

        // ── Прямоугольники для боксов номеров ──
        for (box in numbersBoundingBoxes) {
            val left   = box.centerX * sWidth - box.width * sWidth / 2f
            val top    = box.centerY * sHeight - box.height * sHeight / 2f
            val right  = box.centerX * sWidth + box.width * sWidth / 2f
            val bottom = box.centerY * sHeight + box.height * sHeight / 2f

            val corner = toView(left, top) ?: continue
            val cornerBR = toView(right, bottom) ?: continue

            canvas.drawRect(corner.x, corner.y, cornerBR.x, cornerBR.y, numberBoxPaint)

            // Отрисовка распознанного числа внутри/над боксом
            box.number?.let { num ->
                val textPaint = Paint().apply {
                    color = android.graphics.Color.rgb(255, 160, 0)
                    this.textSize = 36f
                    style = Paint.Style.FILL_AND_STROKE
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                }
                val textY = corner.y + textPaint.textSize * 1.2f
                canvas.drawText(num.toString(), corner.x + (cornerBR.x - corner.x) / 2f, textY, textPaint)
            }
        }

        // ── Круги контрольных пунктов ──

        for (box in controlsboundingBoxes) {
            val cx = box.centerX * sWidth
            val cy = box.centerY * sHeight

            val viewCenter = toView(cx, cy) ?: continue
            val viewEdgeX = toView(cx + box.width * sWidth / 2f, cy) ?: continue
            val viewEdgeY = toView(cx, cy + box.height * sHeight / 2f) ?: continue
            val dxX = viewEdgeX.x - viewCenter.x
            val dyX = viewEdgeX.y - viewCenter.y
            val radiusX = sqrt((dxX * dxX + dyX * dyX).toDouble()).toFloat()
            val dxY = viewEdgeY.x - viewCenter.x
            val dyY = viewEdgeY.y - viewCenter.y
            val radiusY = sqrt((dxY * dxY + dyY * dyY).toDouble()).toFloat()

            val radius = minOf(radiusX, radiusY).coerceAtLeast(4f)
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlFillPaint)
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlCirclePaint)
        }
    }
}
