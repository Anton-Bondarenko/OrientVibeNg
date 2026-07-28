package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.ui.theme.ControlsRed
import kotlin.math.sqrt

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
//        val boxPaint = Paint().apply {
//            style = Paint.Style.STROKE
//            strokeWidth = 6f
//            color = Color.GREEN
//            isAntiAlias = true
//        }

        for (box in boundingBoxes) {
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

//            canvas.drawRect(
//                viewCenter.x - radiusX,
//                viewCenter.y - radiusY,
//                viewCenter.x + radiusX,
//                viewCenter.y + radiusY,
//                boxPaint
//            )

            val radius = minOf(radiusX, radiusY).coerceAtLeast(4f)
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlFillPaint)
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlCirclePaint)
        }
    }
}
