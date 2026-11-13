package ru.bondarenko.orientvibe.ng.yolo

import ai.onnxruntime.OrtException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DetectionTaskHandle(
    val job: Job,
    val version: Int
)

data class DigitAssemblyResult(
    val numbers: List<BoundingBox>,
    val digitsPerNum: List<List<Pair<Float, Float>>>
)

interface MapDetectionProgressListener {
    fun onProgressUpdate(current: Int, total: Int, message: String)
}

data class MapDetectionResult(
    val controlsBoundingBoxes: List<BoundingBox>,
    val numbersBoundingBoxes: List<BoundingBox>
)

class MapDetector(private val context: Context) {

    private val tag = "MapDetector"
    private val totalProgressSteps = 4

    private val _currentTask = AtomicReference<DetectionTaskHandle?>(null)
    val currentTaskRef: AtomicReference<DetectionTaskHandle?> = _currentTask

    private var detector: OnnxObjectDetector? = null
    private var detectorDigits: OnnxObjectDetector? = null
    private var initialized = false
    private var nextVersion = 0
    var progressListener: MapDetectionProgressListener? = null

    private val skipControlFiltering = false
    private val skipNumberFiltering = false

    fun launchDetection(): DetectionTaskHandle {
        val version = synchronized(this) {
            nextVersion++
        }
        val handle = DetectionTaskHandle(Job(), version)
        val prev = _currentTask.getAndSet(handle)
        prev?.job?.cancel()
        return handle
    }

    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        var primaryOk = try {
            val det = OnnxObjectDetector(context)
            det.setProgressListener(object : DetectionProgressListener {
                override fun onProgressUpdate(current: Int, total: Int, message: String) {
                    progressListener?.onProgressUpdate(current, total, message)
                }
            })
            val loaded = det.loadModel("orientmapv8n.onnx")
            if (loaded) {
                detector = det
            } else {
                Log.w(tag, "Failed to load orientmapv8n.onnx")
            }
            loaded
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize primary detector: ${e.message}")
            false
        }

        val digitsOk = try {
            val det2 = OnnxObjectDetector(context)
            val loaded = det2.loadModel("digitsv8n.onnx")
            if (loaded) {
                detectorDigits = det2
            } else {
                Log.w(tag, "Failed to load digitsv8n.onnx — digit recognition disabled")
            }
            loaded
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize digit detector: ${e.message}")
            false
        }

        initialized = primaryOk && digitsOk
        primaryOk
    }

    suspend fun detect(bitmap: Bitmap): MapDetectionResult = withContext(Dispatchers.IO) {
        val activeDetector = detector
        if (activeDetector == null) {
            Log.w(tag, "detect() called before init()")
            return@withContext MapDetectionResult(emptyList(), emptyList())
        }

        try {
            emitProgress(1, "YOLO детекция...")
            val allDetections = activeDetector.detect(bitmap)

            emitProgress(2, "Разделение объектов...")
            val controlsBoxes = ArrayList<BoundingBox>()
            val numbersBoxes = ArrayList<BoundingBox>()

            for (dr in allDetections) {
                if (dr.classId in 0..9) {
                    val bbox = yoloDrToBoundingBox(dr, bitmap.width, bitmap.height)
                    when (dr.classId) {
                        0 -> controlsBoxes.add(bbox.copy(label = "control_point"))
                        1 -> numbersBoxes.add(bbox.copy(label = "number"))
                    }
                }
            }

            val dedupedControls = dedupOverlappingBoxes(controlsBoxes)
            val dedupedNumbers = dedupOverlappingBoxes(numbersBoxes)

            Log.d(
                tag,
                "DEDUP SUMMARY: controls ${controlsBoxes.size}→${dedupedControls.size}, numbers ${numbersBoxes.size}→${dedupedNumbers.size}"
            )
            Log.d(tag, "PRE-FILTER numbers (${dedupedNumbers.size}):")
            dedupedNumbers.forEachIndexed { idx, box ->
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  [preFilter #%d] h=%.4f w=%.4f conf=%.3f num=%s tile=%s",
                        idx, box.height, box.width, box.confidence, box.number, box.tileId
                    )
                )
            }

            val filteredControls = if (skipControlFiltering) {
                Log.d(tag, "  [DIAG] Skipping control filtering — all ${dedupedControls.size} boxes passed")
                dedupedControls
            } else {
                filterControlsByMedianArea(dedupedControls)
            }

            val filteredNumbers = if (skipNumberFiltering) {
                Log.d(tag, "  [DIAG] Skipping number filtering — all ${dedupedNumbers.size} boxes passed")
                dedupedNumbers
            } else {
                filterNumbersByMedianHeight(dedupedNumbers)
            }

            Log.d(
                tag,
                "POST-FILTER: controls ${dedupedControls.size}→${filteredControls.size}, numbers ${dedupedNumbers.size}→${filteredNumbers.size}"
            )
            filteredNumbers.forEachIndexed { idx, box ->
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  [survived #%d] h=%.4f w=%.4f conf=%.3f num=%s",
                        idx, box.height, box.width, box.confidence, box.number
                    )
                )
            }

            emitProgress(3, "Распознавание номеров...")
            coroutineContext.ensureActive()

            val digitResult = if (detectorDigits != null && filteredNumbers.isNotEmpty()) {
                detectAndAssembleNumbers(bitmap, filteredNumbers)
            } else {
                DigitAssemblyResult(filteredNumbers, filteredNumbers.map { emptyList() })
            }

            val finalNumbers = digitResult.numbers.ifEmpty { filteredNumbers }

            emitProgress(3, "Привязка номеров...")
            coroutineContext.ensureActive()
            val matchedControls = attachNumbersBySquareSearch(filteredControls, finalNumbers)

            emitProgress(4, "Почти готово...")
            Log.d(tag, "=== FINAL NUMBER BOX TRACE ===")
            numbersBoxes.forEachIndexed { idx, rawBox ->
                val wasDeduped = dedupedNumbers.none { it === rawBox }
                val wasFiltered = filteredNumbers.none { it === rawBox }
                val matchedNumBox = finalNumbers.firstOrNull {
                    it === rawBox || (it.centerX == rawBox.centerX && it.centerY == rawBox.centerY)
                }
                val hasNumber = matchedNumBox?.number != null
                val attachedCpNum = matchedControls.firstOrNull { it.number != null }?.number

                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  [RAW #%d] h=%.4f w=%.4f conf=%.3f deduped=%b filtered=%b hasNumber=%b number=%s cpAttach=%s",
                        idx, rawBox.height, rawBox.width, rawBox.confidence,
                        wasDeduped, wasFiltered, hasNumber, matchedNumBox?.number, attachedCpNum
                    )
                )
            }
            Log.d(tag, "=== END TRACE ===")

            val attachedCount = matchedControls.count { it.number != null }
            Log.d(
                tag,
                "Detection: controls=${matchedControls.size}, numbers total=${numbersBoxes.size}, attached=$attachedCount"
            )

            MapDetectionResult(matchedControls, numbersBoxes)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.e(tag, "Detection error", e)
            MapDetectionResult(emptyList(), emptyList())
        }
    }

    private fun yoloDrToBoundingBox(
        dr: DetectionResult,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): BoundingBox {
        val box = dr.boundingBox
        val centerPx = PointF((box.left + box.right) / 2.0f, (box.top + box.bottom) / 2.0f)
        val centerNorm = MapGeometry.convertToNormalized(
            centerPx.x.toDouble(),
            centerPx.y.toDouble(),
            bitmapWidth.toDouble(),
            bitmapHeight.toDouble()
        )
        val sizePx = min(bitmapWidth, bitmapHeight).toDouble()
        val rawHalfW = (box.right - box.left) / 2.0f
        val rawHalfH = (box.bottom - box.top) / 2.0f
        val halfWNorm = rawHalfW / sizePx.toFloat()
        val halfHNorm = rawHalfH / sizePx.toFloat()

        Log.d(
            tag,
            String.format(
                Locale.ROOT,
                "yoloDrToBoundingBox: px=[%.1f,%.1f,%.1f,%.1f] centerNorm=(%.4f, %.4f) halfW=%.4f halfH=%.4f sizePx=%.0f maxPx=%.0f",
                box.left, box.top, box.right, box.bottom,
                centerNorm.x, centerNorm.y, halfWNorm, halfHNorm,
                sizePx, max(bitmapWidth, bitmapHeight).toDouble()
            )
        )
        return BoundingBox(
            centerX = centerNorm.x.toDouble(),
            centerY = centerNorm.y.toDouble(),
            width = halfWNorm.toDouble() * 2.0,
            height = halfHNorm.toDouble() * 2.0,
            confidence = dr.confidence.toDouble(),
            label = "",
            number = null,
            tileId = dr.tileId
        )
    }
    private suspend fun detectAndAssembleNumbers(
        bitmap: Bitmap,
        numbersBoxes: List<BoundingBox>
    ): DigitAssemblyResult {
        val results = ArrayList<BoundingBox>()
        val digitsPerNum = ArrayList<List<Pair<Float, Float>>>()
        for (numberBox in numbersBoxes) {
            coroutineContext.ensureActive()
            val idx = numbersBoxes.indexOf(numberBox)
            Log.d(
                tag,
                String.format(
                    Locale.ROOT,
                    "NUM#%d tileId=%s cx=%.4f cy=%.4f w=%.4f h=%.4f conf=%.3f",
                    idx, numberBox.tileId, numberBox.centerX, numberBox.centerY,
                    numberBox.width, numberBox.height, numberBox.confidence
                )
            )
            numberBox.tileId?.let { tileIdx ->
                val numSlicesXPerRow = ceil(bitmap.width.toDouble() / 512.0).toInt()
                val col = tileIdx / numSlicesXPerRow
                val row = tileIdx % numSlicesXPerRow
                val offsetX = col * 512
                val offsetY = row * 512
                val sizePx = min(bitmap.width, bitmap.height).toDouble()
                val padX = (bitmap.width.toDouble() - sizePx) / 2.0
                val padY = (bitmap.height.toDouble() - sizePx) / 2.0
                val tileLeftNorm = (offsetX.toDouble() - padX) / sizePx
                val tileTopNorm = (offsetY.toDouble() - padY) / sizePx
                val leftNorm = (numberBox.centerX - (numberBox.width / 2.0)) - tileLeftNorm
                val rightNorm = (numberBox.centerX + (numberBox.width / 2.0)) - tileLeftNorm
                val topNorm = (numberBox.centerY - (numberBox.height / 2.0)) - tileTopNorm
                val bottomNorm = (numberBox.centerY + (numberBox.height / 2.0)) - tileTopNorm
                if (leftNorm < 0.0 || rightNorm >= 1.0 || topNorm < 0.0 || bottomNorm >= 1.0) {
                    Log.d(
                        tag,
                        "  bbox частично вне слайса #$tileIdx, но OCR запускаем — координаты полн. изображения ROI корректен"
                    )
                }
            }
            val cPx = MapGeometry.convertToPixels(
                numberBox.centerX,
                numberBox.centerY,
                bitmap.width.toDouble(),
                bitmap.height.toDouble()
            )
            val sizePx = min(bitmap.width, bitmap.height).toDouble()
            val halfW = (numberBox.width * sizePx) / 2.0
            val halfH = (numberBox.height * sizePx) / 2.0
            val roiX1 = (cPx.x.toDouble() - (halfW * DIGIT_ROI_EXPANSION_FACTOR)).coerceAtLeast(0.0).toInt()
            val roiY1 = (cPx.y.toDouble() - (halfH * DIGIT_ROI_EXPANSION_FACTOR)).coerceAtLeast(0.0).toInt()
            val roiX2 = (cPx.x.toDouble() + (halfW * DIGIT_ROI_EXPANSION_FACTOR)).coerceAtMost(bitmap.width.toDouble()).toInt()
            val roiY2 = (cPx.y.toDouble() + (halfH * DIGIT_ROI_EXPANSION_FACTOR)).coerceAtMost(bitmap.height.toDouble()).toInt()
            if (roiX2 - roiX1 < 8 || roiY2 - roiY1 < 8) {
                Log.w(tag, "  >>> SKIP ROI too small [NUM#$idx]: ${roiX2 - roiX1}x${roiY2 - roiY1}")
                digitsPerNum.add(emptyList())
                continue
            }
            val cropW = min(max(roiX2 - roiX1, MIN_ROI_WIDTH), bitmap.width - roiX1)
            val cropH = min(max(roiY2 - roiY1, MIN_ROI_HEIGHT), bitmap.height - roiY1)
            if (cropW <= 0 || cropH <= 0) {
                Log.w(tag, "  >>> SKIP crop dims invalid: w=$cropW h=$cropH")
                digitsPerNum.add(emptyList())
                continue
            }
            Log.d(tag, "  finalCrop=[$roiX1,$roiY1] ${cropW}x$cropH")
            val roiBitmap = Bitmap.createBitmap(bitmap, roiX1, roiY1, cropW, cropH)
            try {
                val digitDetector = detectorDigits
                if (digitDetector == null) {
                    Log.w(tag, "  >>> SKIP digitDetector is null")
                    digitsPerNum.add(emptyList())
                    continue
                }
                val rawDigits = digitDetector.detect(roiBitmap)
                if (rawDigits.isEmpty()) {
                    Log.d(tag, "  rawDigits=0 — no detections")
                    digitsPerNum.add(emptyList())
                    continue
                }
                Log.d(tag, "  rawDigits=${rawDigits.size} on ${roiBitmap.width}x${roiBitmap.height}")
                val validDigits = ArrayList<Pair<BoundingBox, Int>>()
                val sb = StringBuilder()
                for (dr in rawDigits) {
                    val box = dr.boundingBox
                    Log.d(
                        tag,
                        String.format(
                            Locale.ROOT,
                            "    rawDigit classId=%d conf=%.3f box=[%.1f, %.1f] [%.1f, %.1f]",
                            dr.classId, dr.confidence, box.left, box.top, box.right, box.bottom
                        )
                    )
                    sb.append(String.format(Locale.ROOT, " digit(%d)@conf=%.3f", dr.classId, dr.confidence))
                    if (dr.confidence >= DIG_CONFIDENCE && dr.classId in 0..9) {
                        val wScale = bitmap.width.toFloat() / roiBitmap.width.toFloat()
                        val hScale = bitmap.height.toFloat() / roiBitmap.height.toFloat()
                        val globalBox = BoundingBox(
                            centerX = (((box.left * wScale) + roiX1) / bitmap.width).toDouble(),
                            centerY = (((box.top * hScale) + roiY1) / bitmap.height).toDouble(),
                            width = (((box.right - box.left) * wScale) / bitmap.width).toDouble(),
                            height = (((box.bottom - box.top) * hScale) / bitmap.height).toDouble(),
                            confidence = dr.confidence.toDouble(),
                            label = "digit"
                        )
                        validDigits.add(Pair(globalBox, dr.classId))
                    } else {
                        Log.d(
                            tag,
                            String.format(
                                Locale.ROOT,
                                "    filtered out: conf=%.3f classId=%d",
                                dr.confidence, dr.classId
                            )
                        )
                    }
                }
                Log.d(tag, "  validDigits=${validDigits.size}$sb")
                if (validDigits.isEmpty()) {
                    Log.w(tag, "  >>> SKIP no valid digits found — all ${rawDigits.size} raw:$sb")
                    digitsPerNum.add(emptyList())
                    continue
                }
                val sortedDigits = validDigits.sortedBy { it.first.centerX }
                val traceStr = sortedDigits.joinToString(", ") { (b, digit) ->
                    String.format(
                        Locale.ROOT,
                        "(digit=%d conf=%.2f cx=%.4f left=%.4f right=%.4f tile=%s)",
                        digit, b.confidence, b.centerX,
                        b.centerX - (b.width / 2.0), b.centerX + (b.width / 2.0), b.tileId
                    )
                }
                Log.d(tag, "  assemble: ${sortedDigits.size} digit(s) left→right:$traceStr")
                digitsPerNum.add(sortedDigits.map { Pair(it.first.centerX.toFloat(), it.second.toFloat()) })
                var assembledNumber = 0
                for ((box, digit) in sortedDigits) {
                    Log.d(tag, String.format(Locale.ROOT, "    digit=%d conf=%.3f", digit, box.confidence))
                    assembledNumber = (assembledNumber * 10) + digit
                }
                numberBox.number = assembledNumber
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  >>> NUMBER=%d from %d digit(s) [tile=%s] bbox=[%.4f, %.4f]",
                        assembledNumber, sortedDigits.size, numberBox.tileId,
                        numberBox.centerX - (numberBox.width / 2.0),
                        numberBox.centerX + (numberBox.width / 2.0)
                    )
                )
                val maxConf = sortedDigits.maxOf { it.first.confidence }
                results.add(
                    BoundingBox(
                        centerX = numberBox.centerX,
                        centerY = numberBox.centerY,
                        width = numberBox.width,
                        height = numberBox.height,
                        confidence = maxConf,
                        label = "number",
                        number = assembledNumber
                    )
                )
            } finally {
                roiBitmap.recycle()
            }
        }
        while (digitsPerNum.size < results.size) {
            digitsPerNum.add(emptyList())
        }
        val withNum = results.count { it.number != null }
        Log.d(tag, "OCR SUMMARY: $withNum/${results.size} numbers recognized:")
        results.forEachIndexed { i, box ->
            val num = box.number
            if (num != null) {
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  [OCRed #%d] num=%d conf=%.3f h=%.4f w=%.4f digits=%d",
                        i, num, box.confidence, box.height, box.width, digitsPerNum[i].size
                    )
                )
            } else {
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "  [EMPTY #%d] h=%.4f w=%.4f conf=%.3f — no digits assembled",
                        i, box.height, box.width, box.confidence
                    )
                )
            }
        }
        return DigitAssemblyResult(results, digitsPerNum)
    }
    private fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0f
    }
    private fun filterControlsByMedianArea(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes
        val areas = boxes.map { (it.width * it.height).toFloat() }
        val med = median(areas) ?: return boxes
        val minArea = 0.8f * med
        val kept = boxes.filter { box ->
            val area = box.width * box.height
            if (area < minArea) {
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "    CP size check: area=%.4f median=%.4f diff=too-small (<80%% of med) FILTER",
                        area, med
                    )
                )
                false
            } else {
                val diff = abs(area - med.toDouble()) / med.toDouble()
                val sizeOk = diff < 0.6
                val ratio = box.width / box.height
                val aspectOk = ratio <= 2.5
                if (!aspectOk) {
                    Log.d(
                        tag,
                        String.format(
                            Locale.ROOT,
                            "    CP aspect check: w/h=%.2f median-area=%.4f diff=%.1f%% FILTER (elongated)",
                            ratio, med, diff * 100.0
                        )
                    )
                }
                val keep = sizeOk && aspectOk
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "    CP size check: area=%.4f median=%.4f diff=%.1f%% ratio=%.2f %s",
                        area, med, diff * 100.0, ratio, if (keep) "KEEP" else "FILTER"
                    )
                )
                keep
            }
        }
        return kept.ifEmpty { boxes.take(1) }
    }
    private fun filterNumbersByMedianHeight(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes
        val heights = boxes.map { it.height.toFloat() }
        val med = median(heights) ?: return boxes
        val kept = boxes.filter { box ->
            val diff = abs(box.height - med.toDouble()) / med.toDouble()
            val keep = diff < 0.25
            Log.d(
                tag,
                String.format(
                    Locale.ROOT,
                    "    NUM height check: h=%.4f median=%.4f diff=%.1f%% %s",
                    box.height, med, diff * 100.0, if (keep) "KEEP" else "FILTER"
                )
            )
            keep
        }
        return kept.ifEmpty { boxes.take(1) }
    }
    private fun makeEmptyBox(original: BoundingBox): BoundingBox {
        return BoundingBox(
            centerX = original.centerX,
            centerY = original.centerY,
            width = original.width,
            height = original.height,
            confidence = original.confidence,
            label = "number",
            number = null
        )
    }
    private fun attachNumbersBySquareSearch(
        controls: List<BoundingBox>,
        numbers: List<BoundingBox>
    ): List<BoundingBox> {
        if (controls.isEmpty() || numbers.isEmpty()) return controls
        val resultControls = controls.map { it.copy() }
        val attachedNumbers = LinkedHashSet<Int>()
        resultControls.forEachIndexed { cpIdx, cp ->
            val halfWidth = cp.width * (SEARCH_BOX_SIZE_MUL / 2.0) // 3.0 * width
            val halfHeight = cp.height * (SEARCH_BOX_SIZE_MUL / 2.0) // 3.0 * height
            val candidates = numbers.withIndex().filter { (_, nb) ->
                abs(nb.centerX - cp.centerX) < halfWidth && abs(nb.centerY - cp.centerY) < halfHeight
            }
            if (candidates.isEmpty()) {
                Log.d(
                    tag,
                    String.format(
                        Locale.ROOT,
                        "    CP#%d: search empty  cp=(%.4f, %.4f) sz=%.4f×%.4f halfWidth=%.4f halfHeight=%.4f",
                        cpIdx, cp.centerX, cp.centerY, cp.width, cp.height, halfWidth, halfHeight
                    )
                )
            } else {
                val closest = candidates.minByOrNull { (_, nb) ->
                    val dx = nb.centerX - cp.centerX
                    val dy = nb.centerY - cp.centerY
                    (dx * dx) + (dy * dy)
                }
                if (closest != null) {
                    val (numIdx, numBox) = closest
                    val dxRel = abs(numBox.centerX - cp.centerX)
                    val dyRel = abs(numBox.centerY - cp.centerY)
                    val distRel = sqrt((dxRel * dxRel) + (dyRel * dyRel))
                    val maxDistRel = (SEARCH_BOX_SIZE_MUL / 2.0) * cp.height // 3.0 * height
                    if (distRel > maxDistRel) {
                        Log.d(
                            tag,
                            String.format(
                                Locale.ROOT,
                                "    CP@%d: too far  cp=(%.4f, %.4f, w=%.4f, h=%.4f) distRel=%.4f maxRel=%.4f | numIdx=%d nb=(%.4f, %.4f)",
                                cpIdx, cp.centerX, cp.centerY, cp.width, cp.height,
                                distRel, maxDistRel, numIdx, numBox.centerX, numBox.centerY
                            )
                        )
                    } else {
                        cp.number = numBox.number ?: cp.number
                        attachedNumbers.add(numIdx)
                        Log.d(
                            tag,
                            String.format(
                                Locale.ROOT,
                                "    CP@%d: attached NUM#%d (value=%s) distRel=%.4f cp=(%.4f, %.4f) nb=(%.4f, %.4f)",
                                cpIdx, numIdx, numBox.number, distRel,
                                cp.centerX, cp.centerY, numBox.centerX, numBox.centerY
                            )
                        )
                    }
                }
            }
        }
        return resultControls
    }
    private fun dedupOverlappingBoxes(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 1) return boxes
        val kept = BooleanArray(boxes.size) { true }
        var changed = true
        while (changed) {
            changed = false
            for (i in boxes.indices) {
                if (!kept[i]) continue
                for (j in (i + 1) until boxes.size) {
                    if (!kept[j]) continue
                    val a = boxes[i]
                    val b = boxes[j]
                    val tileA = a.tileId ?: -1
                    val tileB = b.tileId ?: -1
                    val x1a = a.centerX - (a.width / 2.0)
                    val y1a = a.centerY - (a.height / 2.0)
                    val x2a = a.centerX + (a.width / 2.0)
                    val y2a = a.centerY + (a.height / 2.0)
                    val x1b = b.centerX - (b.width / 2.0)
                    val y1b = b.centerY - (b.height / 2.0)
                    val x2b = b.centerX + (b.width / 2.0)
                    val y2b = b.centerY + (b.height / 2.0)
                    val interLeft = max(x1a, x1b)
                    val interTop = max(y1a, y1b)
                    val interRight = min(x2a, x2b)
                    val interBottom = min(y2a, y2b)
                    val interW = max(0.0f, interRight.toFloat() - interLeft.toFloat())
                    val interH = max(0.0f, interBottom.toFloat() - interTop.toFloat())
                    val interArea = interW * interH
                    val areaA = a.width * a.height
                    val areaB = b.width * b.height
                    val unionArea = (areaA + areaB) - interArea.toDouble()
                    val iou = if (unionArea > 0.0) interArea / unionArea.toFloat() else 0.0f
                    if (iou > 0.5f) {
                        val removed = if (areaB >= areaA) i else j
                        val removedBox = boxes[removed]
                        val keeping = (i + j) - removed
                        Log.d(
                            tag,
                            String.format(
                                Locale.ROOT,
                                "  >>> TILE_MERGE IoU=%.2f areas=[%.4f,%.4f] REMOVED box#%d (%s label='%s' num=%s tile=%d left=%.4f right=%.4f) vs box#%d (%s label='%s' num=%s tile=%d left=%.4f right=%.4f) → KEEPING #%d",
                                iou, areaA, areaB,
                                removed, removedBox.label, removedBox.label, removedBox.number, tileA, x1a, x2a,
                                j, b.label, b.label, b.number, tileB, x1b, x2b, keeping
                            )
                        )
                        kept[removed] = false
                        changed = true
                        break
                    }
                }
                if (changed) break
            }
        }
        val keptBoxes = boxes.filterIndexed { index, _ -> kept[index] }
        val removedBoxes = boxes.mapIndexedNotNull { index, box ->
            if (!kept[index]) index to box else null
        }
        val removedLog = if (removedBoxes.isEmpty()) {
            ""
        } else {
            val indicesStr = removedBoxes.joinToString(", ") { it.first.toString() }
            val detailsStr = removedBoxes.joinToString("\n") { (idx, box) ->
                String.format(
                    Locale.ROOT,
                    "    #%d h=%.4f w=%.4f conf=%.3f num=%s tile=%s",
                    idx, box.height, box.width, box.confidence, box.number, box.tileId
                )
            }
            "\n  REMOVED boxes (original_idx): $indicesStr\n$detailsStr"
        }
        Log.d(
            tag,
            "DEDUP SUMMARY: ${boxes.size} → ${keptBoxes.size} (removed ${removedBoxes.size})$removedLog"
        )
        return keptBoxes
    }
    private fun emitProgress(current: Int, message: String) {
        val task = _currentTask.get()
        if (task != null && task.job.isActive) {
            progressListener?.onProgressUpdate(current, totalProgressSteps, message)
        }
    }
    @Throws(OrtException::class)
    fun close() {
        detector?.close()
        detector = null
        detectorDigits?.close()
        detectorDigits = null
        initialized = false
    }
    companion object {
        const val SLICE_SIZE = 640
        const val CONTROL_POINT_SNAP_THRESHOLD = 0.03f
        const val DIGIT_ROI_EXPANSION_FACTOR = 1.1f
        const val DIG_CONFIDENCE = 0.5f
        const val MIN_ROI_HEIGHT = 40
        const val MIN_ROI_WIDTH = 30
        const val NUMBER_CORRELATION_THRESHOLD_MULT = 1.5f
        const val SEARCH_BOX_SIZE_MUL = 6.0f
    }
}