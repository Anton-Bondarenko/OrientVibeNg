package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color.BLACK
import android.graphics.Paint
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
        alpha = 5
        style = Paint.Style.FILL
    }

    // ── Утиль: последовательный цвет через hue-сдвиг (для отладки) ──
    private fun detColor(index: Int): Int {
        // Сдвигаем hue на ~137° (золотой угол) — цвета равномерно по кругу
        val hue = (index * 137.508) % 360f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.9f, 0.95f))
    }

    // ── Цвет текста: bright hue для контраста на фоне ──
    private fun textColorForIndex(index: Int): Int {
        val hue = (index * 137.508 + 60) % 360f
        return android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 1f, 1f))
    }

    var debugDrawAllBoxes = false

    private val debugTextPaint = Paint().apply {
        color = android.graphics.Color.YELLOW
        textSize = 60f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // ── Отладка: рисует индексы всех боксов (CP#N / NUM#N) в координатах центра ──
    private fun drawDebugBoxes(
        canvas: Canvas,
        sWidth: Float,
        sHeight: Float,
        toView: (Float, Float) -> android.graphics.PointF?
    ) {
        // Боксы КП — над кружком чуть выше
        controlsboundingBoxes.forEachIndexed { idx, box ->
            val cx = (box.centerX + box.width / 2f) * sWidth + 18f
            val cy = (box.centerY + box.height / 2f) * sHeight
            val view = toView(cx, cy) ?: return@forEachIndexed
            debugTextPaint.color = android.graphics.Color.BLACK
            canvas.drawText(
                "CP#$idx %.2f %.2f".format(box.centerX, box.centerY),
                view.x,
                view.y,
                debugTextPaint
            )
        }

        // Боксы номеров — смещение вниз от центра бокса
        numbersBoundingBoxes.forEachIndexed { idx, box ->
            val cx = (box.centerX + box.width / 2f) * sWidth + 18f
            val cy = (box.centerY + box.height / 2f) * sHeight
            val view = toView(cx, cy) ?: return@forEachIndexed
            debugTextPaint.color = android.graphics.Color.BLACK
            canvas.drawText(
                "NUM#$idx %.2f %.2f".format(box.centerX, box.centerY),
                view.x,
                view.y,
                debugTextPaint
            )
        }
    }

    fun numDraw(box: BoundingBox, canvas: Canvas, idx: Int) {
        val (sWidth, sHeight) = imageDimensions ?: return
        val left = (box.centerX - box.width / 2f) * sWidth
        val top = (box.centerY - box.height / 2f) * sHeight
        val right = (box.centerX + box.width / 2f) * sWidth
        val bottom = (box.centerY + box.height / 2f) * sHeight
        val toView = sourceToViewCoord ?: return

        val corner = toView(left, top) ?: return
        val cornerBR = toView(right, bottom) ?: return

        val paint = Paint().apply {
            color = detColor(idx)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawRect(corner.x, corner.y, cornerBR.x, cornerBR.y, paint)

        // Отрисовка распознанного числа внутри бокса (белый текст с цветной обводкой по hue бокса)
        box.number?.let { num ->
            val hue = (idx * 137.508) % 360f
            val textColor =
                android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 1f, 0.95f))
            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 2f
                style = Paint.Style.FILL_AND_STROKE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            textPaint.color = textColor
            // Добавляем белую обводку для читаемости на любом фоне
            val outlinePaint = Paint().apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 4f
                style = Paint.Style.STROKE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            textPaint.textSize = (cornerBR.y - corner.y) * 0.99f
            val cx = corner.x + (cornerBR.x - corner.x) / 2f
            val textY = corner.y + (cornerBR.y - corner.y) / 2f + textPaint.textSize / 3f
            canvas.drawText(num.toString(), cx, textY, outlinePaint)  // белая обводка
            canvas.drawText(num.toString(), cx, textY, textPaint)     // цветной текст
        }
    }

    fun controlDraw(box: BoundingBox, canvas: Canvas, idx: Int) {
        val (sWidth, sHeight) = imageDimensions ?: return
        val cx = box.centerX * sWidth
        val cy = box.centerY * sHeight
        val toView = sourceToViewCoord ?: return

        val viewCenter = toView(cx, cy) ?: return
        val viewEdgeX = toView(cx + box.width * sWidth / 2f, cy) ?: return
        val viewEdgeY = toView(cx, cy + box.height * sHeight / 2f) ?: return
        val dxX = viewEdgeX.x - viewCenter.x
        val dyX = viewEdgeX.y - viewCenter.y
        val radiusX = sqrt((dxX * dxX + dyX * dyX).toDouble()).toFloat()
        val dxY = viewEdgeY.x - viewCenter.x
        val dyY = viewEdgeY.y - viewCenter.y
        val radiusY = sqrt((dxY * dxY + dyY * dyY).toDouble()).toFloat()

        val radius = minOf(radiusX, radiusY).coerceAtLeast(4f)

        // Закрашенный круг контрольной точки
        canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlFillPaint)
        canvas.drawCircle(viewCenter.x, viewCenter.y, radius, controlCirclePaint)

        // Номер привязанного пункта внутри круга (цветной фон + белый текст)
        box.number?.let { num ->
            val hue = (idx * 137.508) % 360f
            val bgColor =
                android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.9f, 0.95f))
            val bgPaint = Paint().apply {
                color = bgColor
                alpha = 0 // ~12.5% opacity — почти прозрачный фон
                style = Paint.Style.FILL
            }
            canvas.drawCircle(viewCenter.x, viewCenter.y, radius * 0.7f, bgPaint)

            val textPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 2f
                style = Paint.Style.FILL_AND_STROKE
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            textPaint.textSize = radius * 1.2f
            val textY = viewCenter.y + textPaint.textSize / 3f
            canvas.drawText(num.toString(), viewCenter.x, textY, bgPaint) // белая обводка
            canvas.drawText(num.toString(), viewCenter.x, textY, textPaint) // цветной текст
        }
    }

    fun searchBoxDraw(box: BoundingBox, canvas: Canvas) {
        val (sWidth, sHeight) = imageDimensions ?: return
        val left = (box.centerX - (box.width * 6f) / 2f) * sWidth
        val top = (box.centerY - (box.height * 6f) / 2f) * sHeight
        val right = (box.centerX + (box.width * 6f) / 2f) * sWidth
        val bottom = (box.centerY + (box.height * 6f) / 2f) * sHeight
        val toView = sourceToViewCoord ?: return

        val corner = toView(left, top) ?: return
        val cornerBR = toView(right, bottom) ?: return

        val paint = Paint().apply {
            color = detColor(BLACK)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawRect(corner.x, corner.y, cornerBR.x, cornerBR.y, paint)
    }

    fun draw(canvas: Canvas) {
        if (controlsboundingBoxes.isEmpty() && numbersBoundingBoxes.isEmpty()) return

        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return

        val toView = sourceToViewCoord ?: return

        // ── Прямоугольники для боксов номеров ──
//        numbersBoundingBoxes.forEachIndexed { idx, box ->
//            numDraw(box, canvas, idx)
//        }

        // ── Круги контрольных пунктов ──
        controlsboundingBoxes.forEachIndexed { idx, box ->
            controlDraw(box, canvas, idx)
        }
    }
}
