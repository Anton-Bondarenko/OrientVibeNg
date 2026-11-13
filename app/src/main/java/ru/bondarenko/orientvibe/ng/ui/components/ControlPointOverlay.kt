package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.ui.theme.OrientVibeColors
import ru.bondarenko.orientvibe.ng.ui.theme.OrientVibeColors.ControlsRed
import ru.bondarenko.orientvibe.ng.yolo.MapDetector
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Слой наложения (Overlay) для отрисовки распознанных КП и цифр на Canvas карты.
 */
class ControlPointOverlay {

    var controlsBoundingBoxes: List<BoundingBox> = emptyList()
    var numbersBoundingBoxes: List<BoundingBox> = emptyList()
    var sourceToViewCoord: ((Double, Double) -> PointF?)? = null

    var debugDrawAllBoxes: Boolean = false
    var showAllBoxes: Boolean = false

    // Определение стилей кистей (Paint)
    private val controlCirclePaint = Paint().apply {
        color = ControlsRed
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        isAntiAlias = true
    }

    private val controlFillPaint = Paint().apply {
        color = ControlsRed
        alpha = 15
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bboxStrokePaint = Paint().apply {
        color = AndroidColor.rgb(13, 71, 161)
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        isAntiAlias = true
    }

    private val bboxFillPaint = Paint().apply {
        color = AndroidColor.rgb(13, 71, 161)
        alpha = 40
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bboxIndexTextPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 36.0f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val bboxOutlineTextPaint = Paint().apply {
        color = AndroidColor.WHITE
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val bboxNumberTextPaint = Paint().apply {
        color = AndroidColor.rgb(13, 71, 161)
        strokeWidth = 2.0f
        style = Paint.Style.FILL_AND_STROKE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val tileGridPaint = Paint().apply {
        color = AndroidColor.rgb(255, 193, 7)
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
        alpha = 180
        isAntiAlias = true
    }

    private val tileIndexTextPaint = Paint().apply {
        color = AndroidColor.WHITE
        textSize = 24.0f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val tileIndexOutlinePaint = Paint().apply {
        color = AndroidColor.BLACK
        strokeWidth = 3.0f
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val debugTextPaint = Paint().apply {
        color = AndroidColor.RED
        textSize = 60.0f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private fun detColor(index: Int): Int {
        val hue = (index.toDouble() * 137.508) % 360.0
        return AndroidColor.HSVToColor(floatArrayOf(hue.toFloat(), 0.9f, 0.95f))
    }

    fun draw(canvas: Canvas, imageWidth: Int = 0, imageHeight: Int = 0) {
        if ((controlsBoundingBoxes.isEmpty() && numbersBoundingBoxes.isEmpty()) || sourceToViewCoord == null) {
            return
        }
        if (showAllBoxes) {
            drawAllBoxes(canvas, imageWidth, imageHeight)
            return
        }
        controlsBoundingBoxes.forEachIndexed { index, box ->
            controlDraw(box, canvas, index)
        }
    }

    private fun controlDraw(box: BoundingBox, canvas: Canvas, idx: Int) {
        val toView = sourceToViewCoord ?: return
        val viewCenter = toView(box.centerX, box.centerY) ?: return
        val viewEdgeX = toView((box.width / 2.0) + box.centerX, box.centerY) ?: return
        val viewEdgeY = toView(box.centerX, (box.height / 2.0) + box.centerY) ?: return

        val radiusX = sqrt(((viewEdgeX.x - viewCenter.x) * (viewEdgeX.x - viewCenter.x)) + ((viewEdgeX.y - viewCenter.y) * (viewEdgeX.y - viewCenter.y)))
        val radiusY = sqrt(((viewEdgeY.x - viewCenter.x) * (viewEdgeY.x - viewCenter.x)) + ((viewEdgeY.y - viewCenter.y) * (viewEdgeY.y - viewCenter.y)))
        val radius = min(radiusX, radiusY).coerceAtLeast(4.0f)

        canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlFillPaint)
        canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlCirclePaint)

        box.number?.let { num ->
            val textPaint = Paint(bboxNumberTextPaint).apply {
                color = ControlsRed
                textSize = 1.2f * radius
            }
            val textY = viewCenter.y + (textPaint.textSize / 3.0f)
            canvas.drawText(num.toString(), viewCenter.x, textY, textPaint)
        }
    }

    private fun drawAllBoxes(canvas: Canvas, imageWidth: Int, imageHeight: Int) {
        val toView = sourceToViewCoord ?: return
        val seenTiles = LinkedHashSet<Int>()
        val allBoxes = controlsBoundingBoxes + numbersBoundingBoxes

        allBoxes.forEach { box ->
            box.tileId?.let { seenTiles.add(it) }
        }

        if (seenTiles.isNotEmpty() && imageWidth > 0 && imageHeight > 0) {
            drawTileGrid(canvas, toView, seenTiles, imageWidth, imageHeight)
        }

        allBoxes.forEachIndexed { idx, box ->
            val left = box.centerX - (box.width / 2.0)
            val top = box.centerY - (box.height / 2.0)
            val right = box.centerX + (box.width / 2.0)
            val bottom = box.centerY + (box.height / 2.0)

            val cornerTL = toView(left, top) ?: return@forEachIndexed
            val cornerBR = toView(right, bottom) ?: return@forEachIndexed

            canvas.drawRect(cornerTL.x, cornerTL.y, cornerBR.x, cornerBR.y, bboxFillPaint)
            canvas.drawRect(cornerTL.x, cornerTL.y, cornerBR.x, cornerBR.y, bboxStrokePaint)

            val indexText = idx.toString()
            val indexSize = min(cornerBR.x - cornerTL.x, cornerBR.y - cornerTL.y)
            val padding = 0.12f * indexSize
            val fontSize = (0.45f * indexSize).coerceIn(24.0f, 48.0f)

            bboxIndexTextPaint.textSize = fontSize
            val labelX = cornerTL.x + padding
            val labelY = cornerTL.y + padding + (fontSize * 0.3f)

            canvas.drawText(indexText, labelX, labelY, bboxOutlineTextPaint)
            canvas.drawText(indexText, labelX, labelY, bboxIndexTextPaint)

            box.number?.let { num ->
                val numFontSize = (indexSize * 0.6f).coerceIn(32.0f, 72.0f)
                bboxNumberTextPaint.textSize = numFontSize
                bboxOutlineTextPaint.textSize = 0.15f * numFontSize

                val centerX = cornerTL.x + ((cornerBR.x - cornerTL.x) / 2.0f)
                val centerY = cornerTL.y + ((cornerBR.y - cornerTL.y) / 2.0f) + (numFontSize * 0.3f)

                canvas.drawText(num.toString(), centerX, centerY, bboxOutlineTextPaint)
                canvas.drawText(num.toString(), centerX, centerY, bboxNumberTextPaint)
            }
        }
    }

    private fun drawTileGrid(
        canvas: Canvas,
        toView: (Double, Double) -> PointF?,
        seenTiles: Set<Int>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val sizePx = min(imageWidth, imageHeight).toDouble()
        val sliceSize = MapDetector.SLICE_SIZE // 640
        val overlapRatio = 0.2f
        val step = (sliceSize * (1.0f - overlapRatio)).toInt() // 512! (640 * 0.8 = 512)

        val padX = (imageWidth.toDouble() - sizePx) / 2.0
        val padY = (imageHeight.toDouble() - sizePx) / 2.0

        val nxCols = maxOf(1, ceil(imageWidth.toDouble() / step).toInt())
        val nyRows = maxOf(1, ceil(imageHeight.toDouble() / step).toInt())

        // Вертикальные линии (колонки X)
        for (col in 0 until nxCols) {
            val hasTileInCol = seenTiles.any { (it - 1) % nxCols == col }
            if (hasTileInCol) {
                val normX = ((col * step).toDouble() - padX) / sizePx
                toView(normX, 0.0)?.let { viewPt ->
                    canvas.drawLine(viewPt.x, 0.0f, viewPt.x, canvas.height.toFloat(), tileGridPaint)
                }
            }
        }

        // Горизонтальные линии (строки Y)
        for (row in 0 until nyRows) {
            val hasTileInRow = seenTiles.any { (it - 1) / nxCols == row }
            if (hasTileInRow) {
                val normY = ((row * step).toDouble() - padY) / sizePx
                toView(0.0, normY)?.let { viewPt ->
                    canvas.drawLine(0.0f, viewPt.y, canvas.width.toFloat(), viewPt.y, tileGridPaint)
                }
            }
        }

        // Подписи тайлов (T1, T2...)
        seenTiles.sorted().forEach { tileId ->
            val zeroBasedIdx = tileId - 1 // В OnnxObjectDetector нумерация начинается с 1 (sliceIdx++)
            val row = zeroBasedIdx / nxCols
            val col = zeroBasedIdx % nxCols

            val leftNorm = ((col * step).toDouble() - padX) / sizePx
            val topNorm = ((row * step).toDouble() - padY) / sizePx

            toView(leftNorm, topNorm)?.let { viewPt ->
                if (viewPt.x in 0.0f..canvas.width.toFloat() && viewPt.y in 0.0f..canvas.height.toFloat()) {
                    val fontSize = 24.0f
                    tileIndexTextPaint.textSize = fontSize
                    tileIndexOutlinePaint.textSize = fontSize

                    val text = "T$tileId"
                    val textY = viewPt.y + fontSize + 4.0f
                    canvas.drawText(text, viewPt.x + 24.0f, textY, tileIndexOutlinePaint)
                    canvas.drawText(text, viewPt.x + 24.0f, textY, tileIndexTextPaint)
                }
            }
        }
    }

    fun handleTouchEvent(event: MotionEvent): Boolean = false
}