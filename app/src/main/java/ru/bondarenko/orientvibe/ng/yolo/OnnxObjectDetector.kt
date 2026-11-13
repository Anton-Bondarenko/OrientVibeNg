package ru.bondarenko.orientvibe.ng.yolo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

interface DetectionProgressListener {
    fun onProgressUpdate(current: Int, total: Int, message: String)
}

data class DetectionResult(
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int,
    val tileId: Int? = null
)

class OnnxObjectDetector(private val context: Context) {

    private val tag = "OnnxObjectDetector"
    private val ortEnvironment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private var progressListener: DetectionProgressListener? = null

    private var inputImageWidth: Int = MapDetector.SLICE_SIZE
    private var inputImageHeight: Int = MapDetector.SLICE_SIZE

    private val labels: List<String> = listOf("control_point")
    private val nmsIouThreshold: Float = 0.45f
    private val nmsMergingIouThreshold: Float = 0.7f

    fun setProgressListener(listener: DetectionProgressListener?) {
        this.progressListener = listener
    }

    fun loadModel(modelPath: String): Boolean {
        return try {
            val tempFile = copyAssetToTempFile(modelPath)
            val sessionOptions = OrtSession.SessionOptions().apply {
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            val session = ortEnvironment.createSession(tempFile.absolutePath, sessionOptions)
            this.ortSession = session

            var inputShape: LongArray? = null
            try {
                val info = session.inputInfo.values.firstOrNull()?.info
                if (info is TensorInfo) {
                    inputShape = info.shape
                }
            } catch (_: Exception) {
            }

            this.inputImageWidth = (inputShape?.getOrNull(3) ?: 640L).toInt()
            this.inputImageHeight = (inputShape?.getOrNull(2) ?: 640L).toInt()

            Log.d(tag, "Model input size: ${inputImageWidth}x${inputImageHeight}")
            tempFile.delete()
            Log.d(tag, "ONNX model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error loading ONNX model", e)
            false
        }
    }

    private fun copyAssetToTempFile(assetPath: String): File {
        val tempFile = File(context.cacheDir, "temp_model.onnx")
        context.assets.open(assetPath).use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return tempFile
    }

    suspend fun detect(bitmap: Bitmap): List<DetectionResult> = withContext(Dispatchers.Default) {
        val session = ortSession ?: run {
            Log.e(tag, "ONNX session not initialized")
            return@withContext emptyList()
        }
        if (bitmap.isRecycled) {
            Log.w(tag, "Detect called with recycled bitmap")
            return@withContext emptyList()
        }

        try {
            detectWithSlicing(bitmap, session)
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.e(tag, "Error during detection", e)
            emptyList()
        }
    }

    private suspend fun detectWithSlicing(
        original: Bitmap,
        session: OrtSession
    ): List<DetectionResult> {
        val originalWidth = original.width
        val originalHeight = original.height
        val sliceSize = MapDetector.SLICE_SIZE
        val overlapRatio = 0.2f
        val overlap = (sliceSize * overlapRatio).toInt()
        val step = sliceSize - overlap

        if (originalWidth <= sliceSize && originalHeight <= sliceSize) {
            return detectSingleImage(original, session)
        }

        val numSlicesX = max(1, ceil((originalWidth - overlap).toDouble() / step).toInt())
        val numSlicesY = max(1, ceil((originalHeight - overlap).toDouble() / step).toInt())
        val totalSlices = numSlicesX * numSlicesY

        withContext(Dispatchers.Main) {
            progressListener?.onProgressUpdate(0, totalSlices, "Подготовка нарезки...")
        }

        val allDetections = ArrayList<DetectionResult>()
        var processedSlices = 0
        val maxStartX = max(0, originalWidth - sliceSize)
        val maxStartY = max(0, originalHeight - sliceSize)

        for (y in 0 until numSlicesY) {
            for (x in 0 until numSlicesX) {
                coroutineContext.ensureActive()

                val sliceIdx = y * numSlicesX + x
                val sliceX1 = min(x * step, maxStartX)
                val sliceY1 = min(y * step, maxStartY)
                val sliceX2 = min(sliceX1 + sliceSize, originalWidth)
                val sliceY2 = min(sliceY1 + sliceSize, originalHeight)
                val sliceWidth = sliceX2 - sliceX1
                val sliceHeight = sliceY2 - sliceY1

                if (sliceWidth <= 0 || sliceHeight <= 0) continue

                val sliceBitmap = Bitmap.createBitmap(original, sliceX1, sliceY1, sliceWidth, sliceHeight)
                try {
                    val sliceDetections = detectSingleImage(sliceBitmap, session)
                    for (det in sliceDetections) {
                        val box = det.boundingBox
                        val mappedBox = RectF(
                            box.left + sliceX1,
                            box.top + sliceY1,
                            box.right + sliceX1,
                            box.bottom + sliceY1
                        )
                        allDetections.add(
                            DetectionResult(
                                boundingBox = mappedBox,
                                confidence = det.confidence,
                                classId = det.classId,
                                tileId = sliceIdx
                            )
                        )
                    }
                } finally {
                    sliceBitmap.recycle()
                }

                processedSlices++
                val currentProcessed = processedSlices
                withContext(Dispatchers.Main) {
                    progressListener?.onProgressUpdate(
                        currentProcessed,
                        totalSlices,
                        "Анализ фрагмента $currentProcessed из $totalSlices"
                    )
                }
            }
        }

        val afterNms = applyNMSByArea(allDetections)
        return applyNMSWithMerging(afterNms)
    }

    private fun applyNMSByArea(results: List<DetectionResult>): List<DetectionResult> {
        if (results.isEmpty()) return results
        // Сортируем по убыванию площади рамки, чтобы при пересечении тайлов выживал целый объект, а не обрезанный
        val sorted = results.sortedByDescending {
            it.boundingBox.width() * it.boundingBox.height()
        }
        val selected = ArrayList<DetectionResult>()

        for (result in sorted) {
            var keep = true
            for (selectedResult in selected) {
                if (result.classId == selectedResult.classId &&
                    calculateIoU(result.boundingBox, selectedResult.boundingBox) > nmsIouThreshold
                ) {
                    keep = false
                    break
                }
            }
            if (keep) {
                selected.add(result)
            }
        }
        return selected
    }

    private suspend fun detectSingleImage(
        bitmap: Bitmap,
        session: OrtSession
    ): List<DetectionResult> {
        try {
            val inputBuffer = preprocessImage(bitmap)
            val inputName = session.inputNames.firstOrNull() ?: return emptyList()
            val inputInfo = session.inputInfo[inputName]

            val modelInputShape: LongArray? = try {
                (inputInfo?.info as? TensorInfo)?.shape
            } catch (e: Exception) {
                Log.w(tag, "Could not get input shape from info, using default", e)
                longArrayOf(1L, 3L, inputImageHeight.toLong(), inputImageWidth.toLong())
            }

            Log.d(tag, "Input info shape: ${modelInputShape?.contentToString()}")

            val expectedSize = if (modelInputShape != null) {
                if (modelInputShape.isEmpty()) {
                    throw UnsupportedOperationException("Empty array can't be reduced.")
                }
                modelInputShape.reduce { acc, l -> acc * l }.toInt()
            } else {
                inputImageHeight * 3 * inputImageWidth
            }

            Log.d(tag, "Expected size from model: $expectedSize, Input buffer capacity: ${inputBuffer.capacity()}")
            inputBuffer.rewind()

            if (inputBuffer.capacity() != expectedSize) {
                Log.e(tag, "Buffer size mismatch! Expected: $expectedSize, Got: ${inputBuffer.capacity()}")
                throw IllegalArgumentException("Buffer size mismatch: expected $expectedSize, got ${inputBuffer.capacity()}")
            }

            val inputShape = modelInputShape ?: longArrayOf(1L, 3L, inputImageHeight.toLong(), inputImageWidth.toLong())
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape)
            val outputs = session.run(mapOf(inputName to inputTensor))
            val output = outputs.get(0)

            val outputInfo = output.info
            Log.d(tag, "Output info: $outputInfo")
            val outputValue = output.value

            val outputBuffer: FloatBuffer = when (outputValue) {
                is FloatBuffer -> outputValue
                is ByteBuffer -> {
                    val fb = ByteBuffer.allocateDirect(outputValue.remaining() / 4)
                    outputValue.rewind()
                    while (outputValue.hasRemaining()) {
                        fb.putFloat(outputValue.getFloat())
                    }
                    fb.rewind()
                    fb.asFloatBuffer()
                }
                is Array<*> -> {
                    Log.d(tag, "Output is array type: ${outputValue.javaClass}")
                    val outputShape: LongArray? = try {
                        val info = ortSession?.outputInfo?.values?.firstOrNull()?.info
                        (info as? TensorInfo)?.shape
                    } catch (e: Exception) {
                        Log.w(tag, "Could not get output shape from info, using default", e)
                        longArrayOf(1L, 14L, 8400L)
                    }
                    val numValues = outputShape?.getOrNull(1)?.toInt() ?: 14
                    val numDetections = outputShape?.getOrNull(2)?.toInt() ?: 8400
                    val expectedOutSize = numValues * numDetections
                    Log.d(tag, "Expected buffer size: $expectedOutSize (1 x $numValues x $numDetections)")
                    val byteBuffer = ByteBuffer.allocateDirect(expectedOutSize * 4).order(ByteOrder.nativeOrder())
                    @Suppress("UNCHECKED_CAST")
                    val batchArray = outputValue as Array<Array<FloatArray>>
                    Log.d(tag, "Batch array size: ${batchArray.size}")

                    var index = 0
                    if (batchArray.isNotEmpty()) {
                        val valuesArray: Array<FloatArray> = batchArray.first() // Берем первый (и единственный) батч
                        Log.d(tag, "Values array size: ${valuesArray.size}")

                        if (valuesArray.isNotEmpty()) {
                            val detectionSize: Int = valuesArray.first().size // Берем длину массива из 8400 чисел
                            Log.d(tag, "Detection array size: $detectionSize")

                            for (detIdx in 0 until detectionSize) {
                                for (valIdx in valuesArray.indices) {
                                    coroutineContext.ensureActive()
                                    if (index < expectedOutSize) {
                                        byteBuffer.putFloat(index * 4, valuesArray[valIdx][detIdx])
                                        index++
                                    }
                                }
                            }
                        }
                    }
                    Log.d(tag, "Copied $index elements to buffer")
                    byteBuffer.rewind()
                    byteBuffer.asFloatBuffer()
                }
                else -> {
                    Log.e(tag, "Unsupported output type: ${outputValue.javaClass}")
                    throw IllegalArgumentException("Unsupported output type: ${outputValue.javaClass}")
                }
            }
            val results = postprocessResults(outputBuffer, bitmap.width, bitmap.height)
            inputTensor.close()
            output.close()
            outputs.close()
            return results
        } catch (e: Exception) {
            if (e is java.util.concurrent.CancellationException) throw e
            Log.e(tag, "Error during single image detection", e)
            return emptyList()
        }
    }
    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val scale = min(
            inputImageWidth.toFloat() / originalWidth.toFloat(),
            inputImageHeight.toFloat() / originalHeight.toFloat()
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val letterboxBitmap = Bitmap.createBitmap(inputImageWidth, inputImageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(-16777216)
        val offsetX = (inputImageWidth - newWidth) / 2
        val offsetY = (inputImageHeight - newHeight) / 2
        canvas.drawBitmap(resizedBitmap, offsetX.toFloat(), offsetY.toFloat(), null)
        val expectedSize = inputImageHeight * 3 * inputImageWidth
        val buffer = FloatBuffer.allocate(expectedSize)
        Log.d(
            tag,
            "Preprocess: Original: ${originalWidth}x${originalHeight}, Resized: ${newWidth}x${newHeight}, Letterbox: ${inputImageWidth}x${inputImageHeight}, Scale: $scale"
        )
        val pixels = IntArray(inputImageWidth * inputImageHeight)
        letterboxBitmap.getPixels(pixels, 0, inputImageWidth, 0, 0, inputImageWidth, inputImageHeight)
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[(inputImageWidth * y) + x]
                buffer.put(((pixel shr 16) and 255) / 255.0f)
            }
        }
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[(inputImageWidth * y) + x]
                buffer.put(((pixel shr 8) and 255) / 255.0f)
            }
        }
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[(inputImageWidth * y) + x]
                buffer.put((pixel and 255) / 255.0f)
            }
        }
        resizedBitmap.recycle()
        letterboxBitmap.recycle()
        buffer.rewind()
        Log.d(tag, "Preprocess: Buffer position: ${buffer.position()}, Buffer remaining: ${buffer.remaining()}")
        return buffer
    }
    private fun postprocessResults(
        outputBuffer: FloatBuffer,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        outputBuffer.rewind()
        val results = ArrayList<DetectionResult>()
        val outputShape: LongArray? = try {
            val info = ortSession?.outputInfo?.values?.firstOrNull()?.info
            (info as? TensorInfo)?.shape
        } catch (e: Exception) {
            Log.w(tag, "Could not get output shape from info, using default", e)
            longArrayOf(1L, 5L, 8400L)
        }
        val numDetections = outputShape?.getOrNull(2)?.toInt() ?: 8400
        val numValues = outputShape?.getOrNull(1)?.toInt() ?: 5
        val scale = min(
            inputImageWidth.toFloat() / originalWidth.toFloat(),
            inputImageHeight.toFloat() / originalHeight.toFloat()
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        val offsetX = (inputImageWidth - newWidth) / 2.0f
        val offsetY = (inputImageHeight - newHeight) / 2.0f
        Log.d(tag, "Postprocess: Original: ${originalWidth}x${originalHeight}, Scale: $scale, Offset: ($offsetX, $offsetY)")
        Log.d(tag, "Buffer capacity: ${outputBuffer.capacity()}, numDetections: $numDetections, numValues: $numValues")
        var detectionsAboveThreshold = 0
        for (i in 0 until numDetections) {
            var maxConfidence = 0.0f
            var maxClassId = 0
            for (classId in 4 until numValues) {
                val classIndex = (i * numValues) + classId
                if (classIndex < outputBuffer.remaining()) {
                    val classConfidence = outputBuffer.get(classIndex)
                    if (classConfidence > maxConfidence) {
                        maxConfidence = classConfidence
                        maxClassId = classId - 4
                    }
                }
            }
            if (maxConfidence > 0.5f) {
                detectionsAboveThreshold++
                val centerX = outputBuffer.get(i * numValues)
                val centerY = outputBuffer.get((i * numValues) + 1)
                val width = outputBuffer.get((i * numValues) + 2)
                val height = outputBuffer.get((i * numValues) + 3)
                val centerXOriginal = (centerX - offsetX) / scale
                val centerYOriginal = (centerY - offsetY) / scale
                val widthOriginal = width / scale
                val heightOriginal = height / scale
                val left = centerXOriginal - (widthOriginal / 2.0f)
                val top = centerYOriginal - (heightOriginal / 2.0f)
                val right = centerXOriginal + (widthOriginal / 2.0f)
                val bottom = centerYOriginal + (heightOriginal / 2.0f)
                val boundingBox = RectF(
                    left.coerceIn(0.0f, originalWidth.toFloat()),
                    top.coerceIn(0.0f, originalHeight.toFloat()),
                    right.coerceIn(0.0f, originalWidth.toFloat()),
                    bottom.coerceIn(0.0f, originalHeight.toFloat())
                )
                results.add(DetectionResult(boundingBox, maxConfidence, maxClassId, null))
            }
        }
        Log.d(tag, "Detections above threshold: $detectionsAboveThreshold")
        return applyNMS(results)
    }
    private fun applyNMS(results: List<DetectionResult>): List<DetectionResult> {
        if (results.isEmpty()) return results
        val sorted = results.sortedByDescending { it.confidence }
        val selected = ArrayList<DetectionResult>()
        for (result in sorted) {
            var keep = true
            for (selectedResult in selected) {
                if (result.classId == selectedResult.classId && calculateIoU(result.boundingBox, selectedResult.boundingBox) > nmsIouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                selected.add(result)
            }
        }
        return selected
    }
    private fun applyNMSWithMerging(
        results: List<DetectionResult>,
        iomThreshold: Float = 0.65f
    ): List<DetectionResult> {
        if (results.isEmpty()) return results
        val sorted = results.sortedByDescending { it.confidence }
        val merged = ArrayList<DetectionResult>()
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            val current = sorted[i]
            val overlapping = ArrayList<DetectionResult>().apply { add(current) }
            used[i] = true
            for (j in (i + 1) until sorted.size) {
                if (used[j]) continue
                val candidate = sorted[j]
                if (current.classId == candidate.classId) {
                    val iou = calculateIoU(current.boundingBox, candidate.boundingBox)
                    val iom = calculateIoM(current.boundingBox, candidate.boundingBox)
                    val axisOverlap = checkAxisOverlap(current.boundingBox, candidate.boundingBox)
                    if (iou > nmsMergingIouThreshold || iom > iomThreshold || axisOverlap) {
                        overlapping.add(candidate)
                        used[j] = true
                    }
                }
            }
            if (overlapping.size > 1) {
                merged.add(mergeDetectionsByUnion(overlapping))
            } else {
                merged.add(current)
            }
        }
        return merged
    }
    private fun checkAxisOverlap(boxA: RectF, boxB: RectF): Boolean {
        val overlapY = max(0.0f, min(boxA.bottom, boxB.bottom) - max(boxA.top, boxB.top))
        val overlapX = max(0.0f, min(boxA.right, boxB.right) - max(boxA.left, boxB.left))
        val minHeight = min(boxA.bottom - boxA.top, boxB.bottom - boxB.top)
        val minWidth = min(boxA.right - boxA.left, boxB.right - boxB.left)
        val verticalRatio = if (minHeight > 0.0f) overlapY / minHeight else 0.0f
        val horizontalRatio = if (minWidth > 0.0f) overlapX / minWidth else 0.0f
        return (verticalRatio > 0.85f && overlapX > 0.0f) || (horizontalRatio > 0.85f && overlapY > 0.0f)
    }
    private fun mergeDetectionsByUnion(detections: List<DetectionResult>): DetectionResult {
        var minLeft = Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxRight = -Float.MAX_VALUE
        var maxBottom = -Float.MAX_VALUE
        var maxConfidence = 0.0f
        for (d in detections) {
            val box = d.boundingBox
            if (box.left < minLeft) minLeft = box.left
            if (box.top < minTop) minTop = box.top
            if (box.right > maxRight) maxRight = box.right
            if (box.bottom > maxBottom) maxBottom = box.bottom
            if (d.confidence > maxConfidence) maxConfidence = d.confidence
        }
        return DetectionResult(
            boundingBox = RectF(minLeft, minTop, maxRight, maxBottom),
            confidence = maxConfidence,
            classId = detections.first().classId,
            tileId = null
        )
    }
    private fun calculateIoM(boxA: RectF, boxB: RectF): Float {
        val intersectionX1 = max(boxA.left, boxB.left)
        val intersectionY1 = max(boxA.top, boxB.top)
        val intersectionX2 = min(boxA.right, boxB.right)
        val intersectionY2 = min(boxA.bottom, boxB.bottom)
        val intersectionWidth = max(0.0f, intersectionX2 - intersectionX1)
        val intersectionHeight = max(0.0f, intersectionY2 - intersectionY1)
        val intersectionArea = intersectionWidth * intersectionHeight
        if (intersectionArea == 0.0f) return 0.0f
        val areaA = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val areaB = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)
        return intersectionArea / min(areaA, areaB)
    }
    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)
        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) return 0.0f
        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = (box1Area + box2Area) - intersectionArea
        return intersectionArea / unionArea
    }
    @Throws(OrtException::class)
    fun close() {
        ortSession?.close()
        ortSession = null
        ortEnvironment.close()
    }
}