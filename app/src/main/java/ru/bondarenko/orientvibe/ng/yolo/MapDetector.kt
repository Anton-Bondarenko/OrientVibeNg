package ru.bondarenko.orientvibe.ng.yolo

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Constants shared between MapDetector and MapViewModel (snap-to-point threshold, OCR ROI expansion, etc.).
 */
const val CONTROL_POINT_SNAP_THRESHOLD = 0.03f
const val DIGIT_ROI_EXPANSION_FACTOR = 1.1f
const val NUMBER_CORRELATION_THRESHOLD_MULT = 1.5f
const val MIN_ROI_WIDTH = 100
const val MIN_ROI_HEIGHT = 100
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
            val det = OnnxObjectDetector(context).also { it.setProgressListener(object : DetectionProgressListener {
                override fun onProgressUpdate(current: Int, total: Int, message: String) {
                    progressListener?.onProgressUpdate(current, total, message)
                }
            }) }
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
                if (ok) detectorDigits = det else Log.w(tag, "Failed to load digitsv8n.onnx — digit recognition disabled")
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

            // Run YOLO on the full image — returns DetectionResult[]
            val allDetections = detector.detect(bitmap)

            // Split YOLO detections: classId 0 = control points, classId 1 = numbers
            val controlsBoxes = mutableListOf<BoundingBox>()
            val numbersBoxes   = mutableListOf<BoundingBox>()

            for (dr in allDetections) {
                if (dr.classId < 0 || dr.classId > 9) continue
                val bbox = yoloDrToBoundingBox(dr, bitmap.width, bitmap.height)
                when (dr.classId) {
                    0 -> controlsBoxes.add(bbox.copy(label = "control_point"))
                    1 -> numbersBoxes.add(bbox.copy(label = "number"))
                }
            }

            // Digit OCR on each number box ROI
            val detectedNumbers = if (detectorDigits != null && numbersBoxes.isNotEmpty()) {
                detectAndAssembleNumbers(bitmap, numbersBoxes)
                    .takeIf { it.isNotEmpty() } ?: numbersBoxes  // fallback — original boxes when OCR fails
            } else {
                numbersBoxes  // OCR unavailable — show YOLO boxes without number
            }

            // Correlate numbers with control points by proximity
            val matchedControls = correlateNumbersWithControls(controlsBoxes, detectedNumbers)

            Log.d(tag, "Detection: controls=${matchedControls.size}, numbers=${detectedNumbers.size}")
            MapDetectionResult(matchedControls, numbersBoxes)
        } catch (e: Exception) {
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
        val left   = dr.boundingBox.left / bitmapWidth
        val top    = dr.boundingBox.top  / bitmapHeight
        val width  = (dr.boundingBox.right - dr.boundingBox.left) / bitmapWidth.toFloat()
        val height = (dr.boundingBox.bottom - dr.boundingBox.top)  / bitmapHeight.toFloat()
        return BoundingBox(
            centerX = left + width / 2f,
            centerY = top + height / 2f,
            width = width,
            height = height,
            confidence = dr.confidence,
            label = "",
            number = null
        )
    }

    /** Recognises digits inside each number bounding box via OCR, returns boxes with assembled numbers. */
    private suspend fun detectAndAssembleNumbers(bitmap: Bitmap, numbersBoxes: List<BoundingBox>): List<BoundingBox> {
        val results = mutableListOf<BoundingBox>()

        for (numberBox in numbersBoxes) {
            val cxPx = numberBox.centerX * bitmap.width
            val cyPx = numberBox.centerY * bitmap.height
            val halfW = (numberBox.width * bitmap.width) / 2f
            val halfH = (numberBox.height * bitmap.height) / 2f

            // Expand ROI: ±150% from number bbox — digits live there
            val roiX1 = ((cxPx - halfW * DIGIT_ROI_EXPANSION_FACTOR).coerceAtLeast(0f)).toInt()
            val roiY1 = ((cyPx - halfH * DIGIT_ROI_EXPANSION_FACTOR).coerceAtLeast(0f)).toInt()
            val roiX2 = (cxPx + halfW * DIGIT_ROI_EXPANSION_FACTOR).coerceAtMost(bitmap.width.toFloat()).toInt()
            val roiY2 = (cyPx + halfH * DIGIT_ROI_EXPANSION_FACTOR).coerceAtMost(bitmap.height.toFloat()).toInt()

            if (roiX2 - roiX1 < 8 || roiY2 - roiY1 < 8) {
                results.add(makeEmptyBox(numberBox))
                continue
            }

            val roiBitmap = Bitmap.createBitmap(bitmap, roiX1, roiY1, max(roiX2 - roiX1, MIN_ROI_WIDTH), max(roiY2 - roiY1, MIN_ROI_HEIGHT)) ?: run {
                results.add(makeEmptyBox(numberBox))
                continue
            }

            try {
                val digitDetector = detectorDigits ?: continue
                val digitDetections = digitDetector.detect(roiBitmap)

                // Filter valid digits (classId 0-9) and convert ROI coords → original image coords
                val validDigits = mutableListOf<Pair<BoundingBox, Int>>()
                for (dr in digitDetections) {
                    if (dr.confidence < DIG_CONFIDENCE || dr.classId !in 0..9) continue

                    val scaleX = bitmap.width.toFloat() / roiBitmap.width.toFloat()
                    val scaleY = bitmap.height.toFloat() / roiBitmap.height.toFloat()

                    val cx2 = (dr.boundingBox.left * scaleX + roiX1) / bitmap.width.toFloat()
                    val cy2 = (dr.boundingBox.top  * scaleY + roiY1) / bitmap.height.toFloat()
                    val bw2 = (dr.boundingBox.right - dr.boundingBox.left) * scaleX / bitmap.width.toFloat()
                    val bh2 = (dr.boundingBox.bottom - dr.boundingBox.top)  * scaleY / bitmap.height.toFloat()

                    validDigits.add(Pair(BoundingBox(cx2, cy2, bw2, bh2, dr.confidence, "digit", null), dr.classId))
                }

                if (validDigits.isEmpty()) {
                    // Не добавляем bbox — только жёлтая рамка для области с цифрами
                    continue
                }

                // Assemble digits left→right into a single number value.
                val sorted = validDigits.sortedBy { p -> p.first.centerX }
                var number = 0
                for ((_, digitNum) in sorted) {
                    number = number * 10 + digitNum
                }

                // Create one merged bbox for the entire number (not individual digits).
                val leftest = sorted.first().first
                val rightest = sorted.last().first
                numberBox.number = number
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
            } finally {
                roiBitmap.recycle()
            }
        }

        return results
    }

    /** Correlates detected numbers with control points by proximity within 1.5× number-box width. */
    private fun correlateNumbersWithControls(controlsBoxes: List<BoundingBox>, detectedNumbers: List<BoundingBox>): List<BoundingBox> {
        if (detectedNumbers.isEmpty()) return controlsBoxes

        return controlsBoxes.mapNotNull { control ->
            var bestMatch: BoundingBox? = null
            var bestDist = Float.MAX_VALUE

            for (numBox in detectedNumbers) {
                val dx = abs(control.centerX - numBox.centerX)
                val dy = abs(control.centerY - numBox.centerY)
                val dist = sqrt(dx * dx + dy * dy)
                val threshold = numBox.width * NUMBER_CORRELATION_THRESHOLD_MULT

                if (dist < bestDist && dist < threshold) {
                    bestDist = dist
                    bestMatch = numBox
                }
            }

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
}
