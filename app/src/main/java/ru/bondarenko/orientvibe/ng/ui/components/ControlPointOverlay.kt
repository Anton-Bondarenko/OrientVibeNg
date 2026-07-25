package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.sqrt
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
import ru.bondarenko.orientvibe.ng.viewmodel.BoundingBox

class ControlPointOverlay {

    var boundingBoxes: List<BoundingBox> = emptyList()

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

    fun draw(canvas: Canvas) {
        if (boundingBoxes.isEmpty()) return

        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return

        val toView = sourceToViewCoord ?: return

        for (box in boundingBoxes) {
            val cx = box.centerX * sWidth
            val cy = box.centerY * sHeight

            val viewCenter = toView(cx, cy) ?: continue
            val viewEdge = toView(cx + box.width * sWidth / 2f, cy) ?: continue
            val radius = (viewEdge.x - viewCenter.x).coerceAtLeast(4f)

            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlFillPaint)
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlCirclePaint)
        }
    }
}
