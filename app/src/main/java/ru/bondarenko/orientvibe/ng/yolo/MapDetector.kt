package ru.bondarenko.orientvibe.ng.yolo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Constants shared between MapDetector and MapViewModel (snap-to-point threshold, OCR ROI expansion, etc.).
 */
const val CONTROL_POINT_SNAP_THRESHOLD = 0.03f
const val DIGIT_ROI_EXPANSION_FACTOR = 1.1f
const val NUMBER_CORRELATION_THRESHOLD_MULT = 1.5f
const val MIN_ROI_WIDTH = 200
const val MIN_ROI_HEIGHT = 200
const val DIG_CONFIDENCE = 0.5f

/** Result of the full detection pipeline (YOLO + OCR + correlation). */
data class MapDetectionResult(
    val controlsBoundingBoxes: List<BoundingBox>,
    val numbersBoundingBoxes: List<BoundingBox>
)

/** Progress events emitted by [MapDetector]. */
interface MapDetectionProgressListener {
    fun onProgressUpdate(current: Int, total: Int, message: String)
}

/**
 * Task handle shared between all detection callers (MapViewModel + ImageLoader).
 * Cancelled via atomic reference — cancellation is instant and cross-scope.
 */
class DetectionTaskHandle(
    val job: kotlinx.coroutines.Job,
    val version: Int
)

/**
 * Encapsulates all YOLO/OCR detection logic for the orienteering map.
 *
 * Lifecycle:
 * 1. Constructor (lazy) — holds app context only
 * 2. [init] — loads orientmapv8n.onnx + digitsv8n.onnx from assets
 * 3. [detect] — main pipeline: YOLO → digit OCR → correlate numbers with controls
 * 4. [close] — releases ONNX sessions (call in MapViewModel.onCleared)
 */
class MapDetector(private val context: Context) {

    private var detector: OnnxObjectDetector? = null
    private var detectorDigits: OnnxObjectDetector? = null
    private var progressListener: MapDetectionProgressListener? = null

    private val tag = "MapDetector"
    private var initialized = false

    /** Shared atomic slot — every caller reads/writes through this single reference. */
    private val _currentTask = AtomicReference<DetectionTaskHandle?>(null)

    /** Monotonically increasing version for stale-result filtering (thread-safe). */
    private var nextVersion = 0

    // ── Diagnostic flags (toggle during development, reset before production) ──
    /** Bypass median-area filtering — passes ALL YOLO control point boxes through unchanged. */
    private var skipControlFiltering = false

    /** Bypass median-height filtering — passes ALL YOLO number boxes through unchanged. */
    private var skipNumberFiltering = false

    /** Skip number↔control correlation — numbers stay on number boxes, not attached to CPs. */
    private var skipNumberCorrelation = false

    /** Creates a new task handle (atomic). Cancels any previous task first. */
    fun launchDetection(): DetectionTaskHandle {
        val version = synchronized(this) { nextVersion++ }
        val handle = DetectionTaskHandle(kotlinx.coroutines.Job(), version)
        val prev = _currentTask.getAndSet(handle)
        prev?.job?.cancel()
        return handle
    }

    /** Exposed for callers to read the current handle and verify version validity. */
    val currentTaskRef: AtomicReference<DetectionTaskHandle?> = _currentTask

    fun setProgressListener(listener: MapDetectionProgressListener?) {
        progressListener = listener
    }

    /**
     * Loads both ONNX models. Call once after construction (e.g. from viewModelScope.launch).
     * Returns true when both models are ready, false otherwise.
     */
    suspend fun init(): Boolean = withContext(Dispatchers.IO) {
        // Load primary detection model
        val primaryOk = try {
            val det = OnnxObjectDetector(context).also {
                it.setProgressListener(object : DetectionProgressListener {
                    override fun onProgressUpdate(current: Int, total: Int, message: String) {
                        progressListener?.onProgressUpdate(current, total, message)
                    }
                })
            }
            det.loadModel("orientmapv8n.onnx").also { ok ->
                if (ok) detector = det else Log.w(tag, "Failed to load orientmapv8n.onnx")
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize primary detector: ${e.message}")
            false
        }

        // Load secondary digit recognition model
        val digitsOk = try {
            val det = OnnxObjectDetector(context)
            det.loadModel("digitsv8n.onnx").also { ok ->
                if (ok) detectorDigits = det else Log.w(
                    tag,
                    "Failed to load digitsv8n.onnx — digit recognition disabled"
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize digit detector: ${e.message}")
            false
        }

        initialized = primaryOk && digitsOk
        primaryOk // digits are optional
    }

    /**
     * Full detection pipeline: YOLO on orientmap → split control_points + numbers → OCR digits → correlate.
     */
    suspend fun detect(bitmap: Bitmap): MapDetectionResult = withContext(Dispatchers.IO) {
        try {
            val detector = this@MapDetector.detector ?: run {
                Log.w(tag, "detect() called before init()")
                return@withContext MapDetectionResult(emptyList(), emptyList())
            }

            // ── Прогресс: запуск YOLO ──
            emitProgress(1, 4, "YOLO детекция...")

            // Run YOLO on the full image — returns DetectionResult[]
            val allDetections = detector.detect(bitmap)

            // Прогресс: YOLO завершена, переходим к разделению
            emitProgress(2, 4, "Разделение объектов...")

            // Split YOLO detections: classId 0 = control points, classId 1 = numbers
            val controlsBoxes = mutableListOf<BoundingBox>()
            val numbersBoxes = mutableListOf<BoundingBox>()

            for (dr in allDetections) {
                if (dr.classId < 0 || dr.classId > 9) continue
                val bbox = yoloDrToBoundingBox(dr, bitmap.width, bitmap.height)
                when (dr.classId) {
                    0 -> controlsBoxes.add(bbox.copy(label = "control_point"))
                    1 -> numbersBoxes.add(bbox.copy(label = "number"))
                }
            }

            // ── Этап 1: фильтрация КП по медиане размера и номеров по медиане высоты (ДО OCR/корреляции) ──
            val filteredControls = if (skipControlFiltering) {
                Log.d(tag, "  [DIAG] Skipping control filtering — all ${controlsBoxes.size} boxes passed")
                controlsBoxes
            } else {
                filterControlsByMedianArea(controlsBoxes)
            }

            val filteredNumbers = if (skipNumberFiltering) {
                Log.d(tag, "  [DIAG] Skipping number filtering — all ${numbersBoxes.size} boxes passed")
                numbersBoxes
            } else {
                filterNumbersByMedianHeight(numbersBoxes)
            }

            Log.d(tag, "Post-filter: controls ${controlsBoxes.size}→${filteredControls.size}, numbers ${numbersBoxes.size}→${filteredNumbers.size}")

            // Прогресс: фильтр завершён, переходим к OCR
            emitProgress(3, 4, "Распознавание номеров...")

            // ТОЧКА ОТМЕНЫ 2: Перед запуском тяжелого OCR
            currentCoroutineContext().ensureActive()
            // Digit OCR on each number box ROI — works on filtered boxes only
            val detectedNumbers = if (detectorDigits != null && filteredNumbers.isNotEmpty()) {
                detectAndAssembleNumbers(bitmap, filteredNumbers)
                    .takeIf { it.isNotEmpty() }
                    ?: filteredNumbers  // fallback — original boxes when OCR fails
            } else {
                filteredNumbers  // OCR unavailable — show YOLO boxes without number
            }

            // Correlate numbers with control points by proximity (uses filtered controls)
            val matchedControls = if (skipNumberCorrelation) {
                Log.d(tag, "  [DIAG] Skipping number↔control correlation")
                filteredControls.mapIndexed { idx, box ->
                    // Attach number from correlated detected numbers if available, otherwise keep null
                    val numNumBox = detectedNumbers.getOrNull(idx)
                    box.copy(number = numNumBox?.number)
                }
            } else {
                correlateNumbersWithControls(filteredControls, detectedNumbers)
            }

            emitProgress(4, 4, "Почти готово...")

            Log.d(
                tag,
                "Detection: controls=${matchedControls.size}, numbers total=${numbersBoxes.size}, with_digit=${detectedNumbers.count { it.number != null }}, without_digit=${detectedNumbers.count { it.number == null }}"
            )
            MapDetectionResult(matchedControls, numbersBoxes)
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e // Обязательно перебрасываем отмену корутины вверх!
            }
            Log.e(tag, "Detection error", e)
            MapDetectionResult(emptyList(), emptyList())
        }
    }

    /** Convert a YOLO [DetectionResult] to a relative-coordinate [BoundingBox]. */
    private fun yoloDrToBoundingBox(
        dr: DetectionResult,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): BoundingBox {
        val left = dr.boundingBox.left / bitmapWidth
        val top = dr.boundingBox.top / bitmapHeight
        val width = (dr.boundingBox.right - dr.boundingBox.left) / bitmapWidth.toFloat()
        val height = (dr.boundingBox.bottom - dr.boundingBox.top) / bitmapHeight.toFloat()
        return BoundingBox(
            centerX = left + width / 2f,
            centerY = top + height / 2f,
            width = width,
            height = height,
            confidence = dr.confidence,
            label = "",
            number = null,
            tileId = dr.tileId
        )
    }

    /** Slice size — совпадает с OnnxObjectDetector.inputImageWidth. */
    private companion object { val SLICE_SIZE = 640 }

    /** Recognises digits inside each number bounding box via OCR, returns boxes with assembled numbers. */
    private suspend fun detectAndAssembleNumbers(
        bitmap: Bitmap,
        numbersBoxes: List<BoundingBox>
    ): List<BoundingBox> {
        val results = mutableListOf<BoundingBox>()

        for (numberBox in numbersBoxes) {
            // ТОЧКА ОТМЕНЫ 3: Проверяем перед обработкой каждого отдельного номера
            // Если корутина отменена, выполнение прервется, а блок try-finally гарантирует вызов roiBitmap.recycle()
            currentCoroutineContext().ensureActive()

            // Пропускаем OCR для боксов, обрезанных границами тайла.
            // Они содержат только часть объекта (детектирована на краю тайла).
            // За счёт избыточного наложения — полная версия есть в соседнем тайле.
            Log.d(tag, "NUM#${numbersBoxes.indexOf(numberBox)} tileId=${numberBox.tileId} cx=${"%.4f".format(numberBox.centerX)} cy=${"%.4f".format(numberBox.centerY)} w=${"%.4f".format(numberBox.width)} h=${"%.4f".format(numberBox.height)} conf=${"%.3f".format(numberBox.confidence)}")
            // bbox tileId указывает, в каком слайсе YOLO обнаружил число.
            // Координаты cx/cy/w/h всегда в нормализованных координатах полного изображения — ROI корректен.
            // Раньше мы пропускали OCR для bbox, частично выходящих за границы слайса, но это приводило
            // к потере номеров на границах тайлов (число детектировано в одном слайсе, а bbox частично
            // лежит в соседнем — по координатам проверяем: leftNorm < 0).
            // Теперь OCR запускается всегда — ROI берётся из полной картинки.
            if (numberBox.tileId != null) {
                val tileIdx = numberBox.tileId!!
                val numSlicesXPerRow = kotlin.math.ceil(bitmap.width.toDouble() / (SLICE_SIZE - SLICE_SIZE / 5)).toInt()
                val col = tileIdx / numSlicesXPerRow
                val row = tileIdx % numSlicesXPerRow
                val offsetX = col * (SLICE_SIZE - SLICE_SIZE / 5)
                val offsetY = row * (SLICE_SIZE - SLICE_SIZE / 5)

                // Конвертируем bbox из абсолютных пикселей → локальные координаты тайла
                val leftNorm = numberBox.centerX - numberBox.width / 2f - offsetX / bitmap.width.toFloat()
                val rightNorm = numberBox.centerX + numberBox.width / 2f - offsetX / bitmap.width.toFloat()
                val topNorm = numberBox.centerY - numberBox.height / 2f - offsetY / bitmap.height.toFloat()
                val bottomNorm = numberBox.centerY + numberBox.height / 2f - offsetY / bitmap.height.toFloat()

                Log.d(tag, "  slice=$tileIdx [$col,$row] offsetX=$offsetX offsetY=$offsetY bounds=tl=${"%.4f".format(leftNorm)} tr=${"%.4f".format(rightNorm)} bl=${"%.4f".format(topNorm)} br=${"%.4f".format(bottomNorm)}")
                if (leftNorm < 0 || rightNorm >= 1 || topNorm < 0 || bottomNorm >= 1) {
                    Log.d(tag, "  bbox частично вне слайса #$tileIdx, но OCR запускаем — координаты полн. изображения ROI корректен")
                }
            }

            val cxPx = numberBox.centerX * bitmap.width
            val cyPx = numberBox.centerY * bitmap.height
            val halfW = (numberBox.width * bitmap.width) / 2f
            val halfH = (numberBox.height * bitmap.height) / 2f

            // Expand ROI: ±150% from number bbox — digits live there
            val roiX1 = ((cxPx - halfW * DIGIT_ROI_EXPANSION_FACTOR).coerceAtLeast(0f)).toInt()
            val roiY1 = ((cyPx - halfH * DIGIT_ROI_EXPANSION_FACTOR).coerceAtLeast(0f)).toInt()
            val roiX2 =
                (cxPx + halfW * DIGIT_ROI_EXPANSION_FACTOR).coerceAtMost(bitmap.width.toFloat())
                    .toInt()
            val roiY2 =
                (cyPx + halfH * DIGIT_ROI_EXPANSION_FACTOR).coerceAtMost(bitmap.height.toFloat())
                    .toInt()

            Log.d(tag, "  cx=$cxPx cy=$cyPx halfW=$halfW halfH=$halfH rawROI=[$roiX1,$roiY1]-$roiX2,$roiY2 sz=${roiX2-roiX1}x${roiY2-roiY1}")

            if (roiX2 - roiX1 < 8 || roiY2 - roiY1 < 8) {
                Log.w(tag, "  >>> SKIP ROI too small: ${roiX2 - roiX1}x${roiY2 - roiY1}")
                results.add(makeEmptyBox(numberBox))
                continue
            }

            val cropW = minOf(max(roiX2 - roiX1, MIN_ROI_WIDTH).toInt(), bitmap.width - roiX1)
            val cropH = minOf(max(roiY2 - roiY1, MIN_ROI_HEIGHT).toInt(), bitmap.height - roiY1)
            if (cropW <= 0 || cropH <= 0) {
                Log.w(tag, "  >>> SKIP crop dims invalid: w=$cropW h=$cropH")
                results.add(makeEmptyBox(numberBox))
                continue
            }

            Log.d(tag, "  finalCrop=[$roiX1,$roiY1] ${cropW}x$cropH")
            val roiBitmap = Bitmap.createBitmap(
                bitmap,
                roiX1,
                roiY1,
                cropW,
                cropH
            ) ?: run {
                Log.w(tag, "  >>> SKIP createBitmap returned null")
                results.add(makeEmptyBox(numberBox))
                continue
            }

            try {
                val digitDetector = detectorDigits ?: run {
                    Log.w(tag, "  >>> SKIP digitDetector is null")
                    results.add(makeEmptyBox(numberBox))
                    null
                }

                digitDetector?.let { det ->
                    val digitDetections = det.detect(roiBitmap)
                    Log.d(tag, "  rawDigits=${digitDetections.size} on ${roiBitmap.width}x${roiBitmap.height}")

                    // Filter valid digits (classId 0-9) and convert ROI coords → original image coords
                    val validDigits = mutableListOf<Pair<BoundingBox, Int>>()
                    for (dr in digitDetections) {
                        Log.d(tag, "    rawDigit classId=${dr.classId} conf=${"%.3f".format(dr.confidence)} box=[${dr.boundingBox.left}, ${dr.boundingBox.top}] [${dr.boundingBox.right}, ${dr.boundingBox.bottom}]")
                        if (dr.confidence < DIG_CONFIDENCE || dr.classId !in 0..9) {
                            Log.d(tag, "    filtered out: conf=${"%.3f".format(dr.confidence)} classId=${dr.classId}")
                            continue
                        }

                        val scaleX = bitmap.width.toFloat() / roiBitmap.width.toFloat()
                        val scaleY = bitmap.height.toFloat() / roiBitmap.height.toFloat()

                        val cx2 = (dr.boundingBox.left * scaleX + roiX1) / bitmap.width.toFloat()
                        val cy2 = (dr.boundingBox.top * scaleY + roiY1) / bitmap.height.toFloat()
                        val bw2 =
                            (dr.boundingBox.right - dr.boundingBox.left) * scaleX / bitmap.width.toFloat()
                        val bh2 =
                            (dr.boundingBox.bottom - dr.boundingBox.top) * scaleY / bitmap.height.toFloat()

                        validDigits.add(
                            Pair(
                                BoundingBox(
                                    cx2,
                                    cy2,
                                    bw2,
                                    bh2,
                                    dr.confidence,
                                    "digit",
                                    null
                                ), dr.classId
                            )
                        )
                    }

                    Log.d(tag, "  validDigits=${validDigits.size}")
                    if (validDigits.isEmpty()) {
                        Log.w(tag, "  >>> SKIP no valid digits found")
                        continue
                    }

                    // Assemble digits left→right into a single number value.
                    val sorted = validDigits.sortedBy { p -> p.first.centerX }
                    var number = 0
                    for ((_, digitNum) in sorted) {
                        Log.d(tag, "    digit=$digitNum conf=${"%.3f".format(sorted.find { it.second == digitNum }?.first?.confidence ?: 0f)}")
                        number = number * 10 + digitNum
                    }

                    // Create one merged bbox for the entire number (not individual digits).
                    val leftest = sorted.first().first
                    val rightest = sorted.last().first
                    numberBox.number = number
                    Log.d(tag, "  >>> NUMBER=$number from ${validDigits.size} digit(s)")
                    results.add(
                        BoundingBox(
                            centerX = (leftest.centerX + rightest.centerX) / 2f,
                            centerY = (leftest.centerY + rightest.centerY) / 2f,
                            width = if (sorted.size > 1) {
                                (rightest.centerX + rightest.width / 2f) - (leftest.centerX - leftest.width / 2f)
                            } else {
                                leftest.width
                            },
                            height = sorted.maxOf { it.first.height },
                            confidence = sorted.maxOf { it.first.confidence },
                            label = "number",
                            number = number
                        )
                    )
                }
            } finally {
                roiBitmap.recycle()
            }
        }

        val withNum = results.count { it.number != null }
        Log.d(tag, "detectAndAssembleNumbers: $withNum/${results.size} numbers recognized")

        return results
    }

    /** Compute median of a float list. Returns null if empty. */
    internal fun median(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /**
     * Remove control points whose area differs from the median area by ≥60%,
     * or whose absolute area is less than 30% of the median (catches tiny false-positives).
     * Keeps at least 1 box even if all are outliers (guard against empty result).
     */
    internal fun filterControlsByMedianArea(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes

        val areas = boxes.map { it.width * it.height }
        val med = median(areas) ?: return boxes
        val minArea = med * 0.30f // absolute floor: anything below 30% of median is tiny noise

        return boxes.filter { box ->
            val area = box.width * box.height
            if (area < minArea) {
                Log.d(tag, "    CP size check: area=${"%.4f".format(area)} median=${"%.4f".format(med)} diff=too-small (<30%% of med) FILTER")
                false
            } else {
                val diff = abs(area - med) / med
                val keep = diff < 0.60f
                Log.d(tag, "    CP size check: area=${"%.4f".format(area)} median=${"%.4f".format(med)} diff=%.1f%% %s"
                    .format(diff * 100, if (keep) "KEEP" else "FILTER"))
                keep
            }
        }.takeIf { it.isNotEmpty() } ?: boxes.take(1) // guard: at least one
    }

    /**
     * Remove number boxes whose height differs from the median height by ≥25%.
     * Keeps at least 1 box even if all are outliers (guard against empty result).
     */
    internal fun filterNumbersByMedianHeight(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes

        val heights = boxes.map { it.height }
        val med = median(heights) ?: return boxes

        return boxes.filter { box ->
            val diff = abs(box.height - med) / med
            val keep = diff < 0.25f
            Log.d(tag, "    NUM height check: h=${"%.4f".format(box.height)} median=${"%.4f".format(med)} diff=%.1f%% %s"
                .format(diff * 100, if (keep) "KEEP" else "FILTER"))
            keep
        }.takeIf { it.isNotEmpty() } ?: boxes.take(1) // guard: at least one
    }

    /**
     * Correlates detected numbers with control points by proximity.
     *
     * Uses closest-edge-of-box distance (not center-to-center), with a dynamic threshold based on
     * each CP's box size. The minimum threshold was increased from 0.005 to 0.015 to account for
     * tile-overlap artifacts that can displace number detections by several percent — especially
     * critical for CPs near image edges (KP 8, 10, 11-16) where artifacts compound.
     *
     * Greedy assignment: each number box is assigned to at most one CP (the closest),
     * preventing duplicate assignments from tile-overlap artifacts.
     */
    private fun correlateNumbersWithControls(
        controlsBoxes: List<BoundingBox>,
        detectedNumbers: List<BoundingBox>
    ): List<BoundingBox> {
        if (detectedNumbers.isEmpty()) return controlsBoxes

        val assigned = mutableSetOf<Int>()

        return controlsBoxes.mapIndexed { idx, control ->
            var bestMatch: BoundingBox? = null
            var bestDist = Float.MAX_VALUE

            // Dynamic threshold: 4x the larger dimension of this CP box.
            // Minimum was raised from 0.005 to 0.015 — tile-overlap artifacts can displace
            // number detections by 5-8% near image edges, and small controls need a wider reach.
            val cpSize = max(control.width, control.height)
            val dynamicThreshold = max(cpSize * 4f, 0.015f) // min 1.5% of image dimension (was 0.5%)

            for ((i, numBox) in detectedNumbers.withIndex()) {
                if (assigned.contains(i)) continue // already assigned to another CP

                // Closest-edge distance: accounts for BOTH the CP box size AND the number box size.
                // If the number box is large and positioned at an edge of its tile, its center may be
                // far from the CP center — but the closest edges are still adjacent.
                val dx = max(0f, abs(control.centerX - numBox.centerX) - control.width / 2f - numBox.width / 2f)
                val dy = max(0f, abs(control.centerY - numBox.centerY) - control.height / 2f - numBox.height / 2f)
                val dist = sqrt(dx * dx + dy * dy).toFloat()

                if (dist < bestDist && dist < dynamicThreshold) {
                    bestDist = dist
                    bestMatch = numBox
                }
            }

            bestMatch?.let { assigned.add(detectedNumbers.indexOf(it)) }
            control.copy(number = bestMatch?.number)
        }
    }

    /** When OCR fails, emit the original bbox without a number so it stays visible on screen. */
    private fun makeEmptyBox(original: BoundingBox): BoundingBox {
        return BoundingBox(
            centerX = original.centerX, centerY = original.centerY,
            width = original.width, height = original.height,
            confidence = original.confidence, label = "number", number = null
        )
    }

    /** Releases ONNX sessions. Safe to call multiple times. */
    fun close() {
        detector?.close()
        detector = null
        detectorDigits?.close()
        detectorDigits = null
        initialized = false
    }

    /** Thread-safe progress emitter — checks current task handle before reporting. */
    private fun emitProgress(current: Int, total: Int, message: String) {
        val task = _currentTask.get() ?: return
        if (!task.job.isActive) return // cancelled — silently skip
        progressListener?.onProgressUpdate(current, total, message)
    }
}
