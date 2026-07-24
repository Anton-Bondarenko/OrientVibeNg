package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
            val centerRelX = (box.left + box.right) / 2
            val centerRelY = (box.top + box.bottom) / 2
            val boxRelWidth = box.right - box.left
            val boxRelHeight = box.bottom - box.top

            val centerX = centerRelX * sWidth
            val centerY = centerRelY * sHeight
            val boxWidth = boxRelWidth * sWidth
            val boxHeight = boxRelHeight * sHeight
            val radius = minOf(boxWidth, boxHeight) / 2

            val viewCenter = toView(centerX, centerY) ?: continue
            val viewEdge = toView(centerX + radius, centerY) ?: continue
            val viewRadius = (viewEdge.x - viewCenter.x).coerceAtLeast(4f)

            canvas.drawCircle(viewCenter.x, viewCenter.y, viewRadius, controlFillPaint)
            canvas.drawCircle(viewCenter.x, viewCenter.y, viewRadius, controlCirclePaint)
        }
    }
}
