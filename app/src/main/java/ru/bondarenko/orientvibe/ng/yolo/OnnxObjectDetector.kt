package ru.bondarenko.orientvibe.ng.yolo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

data class DetectionResult(
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int
)

interface DetectionProgressListener {
    fun onProgressUpdate(current: Int, total: Int, message: String)
}

class OnnxObjectDetector(private val context: Context) {

    private var progressListener: DetectionProgressListener? = null

    fun setProgressListener(listener: DetectionProgressListener?) {
        this.progressListener = listener
    }

    private val tag = "OnnxObjectDetector"
    private var ortSession: OrtSession? = null
    private val ortEnvironment = OrtEnvironment.getEnvironment()
    private var inputImageWidth = 640
    private var inputImageHeight = 640
    private val labels = listOf("control_point")

    fun loadModel(modelPath: String): Boolean {
        return try {
            // Copy model from assets to temporary file
            val tempFile = copyAssetToTempFile(modelPath)

            // Create ONNX session
            val sessionOptions = OrtSession.SessionOptions().apply {
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            ortSession = ortEnvironment.createSession(tempFile.absolutePath, sessionOptions)

            // Читаем входной размер из модели (например 640 или 320)
            val inputShape = try {
                (ortSession?.inputInfo?.values?.first()?.info as? ai.onnxruntime.TensorInfo)?.shape
            } catch (e: Exception) {
                null
            }
            inputImageWidth = (inputShape?.getOrNull(3) ?: 640).toInt()
            inputImageHeight = (inputShape?.getOrNull(2) ?: 640).toInt()
            Log.d(tag, "Model input size: ${inputImageWidth}x${inputImageHeight}")

            // Clean up temp file
            tempFile.delete()

            Log.d(tag, "ONNX model loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(tag, "Error loading ONNX model", e)
            false
        }
    }

    private fun copyAssetToTempFile(assetPath: String): File {
        val inputStream = context.assets.open(assetPath)
        val tempFile = File(context.cacheDir, "temp_model.onnx")
        val outputStream = FileOutputStream(tempFile)

        inputStream.use { input ->
            outputStream.use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    suspend fun detect(bitmap: Bitmap): List<DetectionResult> {
        val session = this.ortSession ?: run {
            Log.e(tag, "ONNX session not initialized")
            return emptyList()
        }

        val source = if (bitmap.isRecycled) {
            Log.w(tag, "Detect called with recycled bitmap")
            return emptyList()
        } else {
            bitmap
        }

        return try {
            // Use sliced inference for better detection on high-resolution images
            detectWithSlicing(source, session)
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            Log.e(tag, "Error during detection", e)
            emptyList()
        }
    }

    private suspend fun detectWithSlicing(
        original: Bitmap,
        session: OrtSession
    ): List<DetectionResult> {
        if (original.isRecycled) {
            Log.w(tag, "Skipping sliced inference: recycled bitmap")
            return emptyList()
        }
        val source = original.copy(Bitmap.Config.ARGB_8888, false)
        val originalWidth = source.width
        val originalHeight = source.height

        Log.d(tag, "Sliced inference: Original image ${originalWidth}x${originalHeight}")

        // Slicing parameters — совпадают с входным размером модели (640 или 320)
        val sliceSize = inputImageWidth
        val overlapRatio = 0.2f
        val overlap = (sliceSize * overlapRatio).toInt()
        val step = sliceSize - overlap

        // Calculate number of slices
        val numSlicesX = ceil(originalWidth.toDouble() / step).toInt()
        val numSlicesY = ceil(originalHeight.toDouble() / step).toInt()
        val totalSlices = numSlicesX * numSlicesY

        Log.d(
            tag,
            "Slicing: ${numSlicesX}x${numSlicesY} = $totalSlices slices, step=$step, overlap=$overlap"
        )

        Handler(Looper.getMainLooper()).post {
            progressListener?.onProgressUpdate(0, totalSlices, "Подготовка нарезки...")
        }

        val allDetections = mutableListOf<DetectionResult>()
        var processedSlices = 0

        // Process each slice
        for (y in 0 until numSlicesY) {
            for (x in 0 until numSlicesX) {
                // ТОЧКА ОТМЕНЫ 1: Проверяем перед обработкой каждого тайла.
                // Если задача отменена через shared AtomicReference в MapDetector, этот метод выбросит CancellationException,
                // выполнение цикла прекратится, а блок catch/finally утилизирует 'source' bitmap.
                currentCoroutineContext().ensureActive()

                val maxStartX = (originalWidth - sliceSize).coerceAtLeast(0)
                val maxStartY = (originalHeight - sliceSize).coerceAtLeast(0)
                val sliceX1 = (x * step).coerceAtMost(maxStartX)
                val sliceY1 = (y * step).coerceAtMost(maxStartY)
                val sliceX2 = (sliceX1 + sliceSize).coerceAtMost(originalWidth)
                val sliceY2 = (sliceY1 + sliceSize).coerceAtMost(originalHeight)

                // Skip if slice is too small
                if ((sliceX2 - sliceX1 < 100 || sliceY2 - sliceY1 < 100) && (numSlicesY != 1 || numSlicesX != 1)) {
                    continue
                }

                if (source.isRecycled) {
                    Log.w(tag, "Source bitmap recycled during slicing, aborting")
                    return allDetections
                }

                // Extract slice
                val sliceWidth = sliceX2 - sliceX1
                val sliceHeight = sliceY2 - sliceY1
                val sliceBitmap =
                    Bitmap.createBitmap(source, sliceX1, sliceY1, sliceWidth, sliceHeight)

                // Update progress
                processedSlices++
                Handler(Looper.getMainLooper()).post {
                    progressListener?.onProgressUpdate(
                        processedSlices,
                        totalSlices,
                        "Анализ фрагмента $processedSlices из $totalSlices"
                    )
                }

                // Run inference on slice
                val sliceDetections = detectSingleImage(sliceBitmap, session)

                // Adjust coordinates to original image
                val adjustedDetections = sliceDetections.map { det ->
                    val adjustedBox = RectF(
                        det.boundingBox.left + sliceX1,
                        det.boundingBox.top + sliceY1,
                        det.boundingBox.right + sliceX1,
                        det.boundingBox.bottom + sliceY1
                    )
                    DetectionResult(adjustedBox, det.confidence, det.classId)
                }

                allDetections.addAll(adjustedDetections)

                // Clean up
                sliceBitmap.recycle()
            }
        }

        Log.d(tag, "Total detections from all slices: ${allDetections.size}")

        progressListener?.onProgressUpdate(totalSlices, totalSlices, "Объединение результатов...")

        // Apply NMS with merging to combine overlapping detections from slicing
        val finalDetections = applyNMSWithMerging(allDetections, 0.7f)
        Log.d(tag, "After NMS with merging: ${finalDetections.size} detections")

        return finalDetections
    }

    private suspend fun detectSingleImage(
        bitmap: Bitmap,
        session: OrtSession
    ): List<DetectionResult> {
        return try {
            // Preprocess image
            val inputBuffer = preprocessImage(bitmap)

            // Get input info from session first to understand expected format
            val inputName = session.inputNames?.iterator()?.next()
            val inputInfo = session.inputInfo[inputName]
            val modelInputShape = try {
                (inputInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
            } catch (e: Exception) {
                Log.w(tag, "Could not get input shape from info, using default", e)
                longArrayOf(1, 3, inputImageHeight.toLong(), inputImageWidth.toLong())
            }
            Log.d(tag, "Input info shape: ${modelInputShape?.contentToString()}")

            // Calculate expected size from actual model input shape (number of float elements)
            val expectedSize = modelInputShape?.reduce { a, b -> a * b }?.toInt()
                ?: (1 * 3 * inputImageHeight * inputImageWidth)
            Log.d(
                tag,
                "Expected size from model: $expectedSize, Input buffer capacity: ${inputBuffer.capacity()}"
            )

            // Ensure input buffer has correct size
            inputBuffer.rewind()
            if (inputBuffer.capacity() != expectedSize) {
                Log.e(
                    tag,
                    "Buffer size mismatch! Expected: $expectedSize, Got: ${inputBuffer.capacity()}"
                )
                throw IllegalArgumentException("Buffer size mismatch: expected $expectedSize, got ${inputBuffer.capacity()}")
            }

            // Create tensor using ortEnvironment with FloatBuffer and shape
            val inputShape = modelInputShape ?: longArrayOf(
                1,
                3,
                inputImageHeight.toLong(),
                inputImageWidth.toLong()
            )
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape)

            // Run inference
            val inputs = mapOf(inputName to inputTensor)
            val outputs = session.run(inputs)

            // Get output - use getValue() with array handling
            val output = outputs[0]
            val outputBuffer = try {
                // Get output info to understand the shape and type
                val outputInfo = output.info
                Log.d(tag, "Output info: $outputInfo")

                val outputValue = output.getValue()
                when (outputValue) {
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
                        // [[[F structure: Array<Array<FloatArray>> — shape читается динамически из модели
                        // [1, numValues, numDetections] где numValues=4_bbox+classes, numDetections зависит от imgsz (640→8400, 320→2100)
                        // Мы flatten'им как: [det0_val0, det0_val1, ..., det0_valN, det1_val0, ...]
                        val outputShape = try {
                            (ortSession?.outputInfo?.values?.first()?.info as? ai.onnxruntime.TensorInfo)?.shape
                        } catch (e: Exception) {
                            Log.w(tag, "Could not get output shape from info, using default", e)
                            longArrayOf(
                                1,
                                14,
                                8400
                            ) // Default: [batch, 4_bbox + 10_classes, detections]
                        }
                        val numValues = outputShape?.getOrNull(1)?.toInt() ?: 14
                        val numDetections = outputShape?.getOrNull(2)?.toInt() ?: 8400
                        val expectedSize = 1 * numValues * numDetections
                        Log.d(
                            tag,
                            "Expected buffer size: $expectedSize (1 x $numValues x $numDetections)"
                        )

                        val byteBuffer = ByteBuffer.allocateDirect(expectedSize * 4)
                        byteBuffer.order(ByteOrder.nativeOrder())

                        // [[[F = Array<Array<FloatArray>>
                        // Structure: batch[0] = Array<FloatArray> of size 6 (values)
                        // Each FloatArray has size 8400 (detections)
                        // We need: [det0_val0, det0_val1, ..., det0_val5, det1_val0, ...]
                        // So: for each detection (0..8399), for each value (0..5)
                        var index = 0

                        @Suppress("UNCHECKED_CAST")
                        val batchArray = outputValue as Array<Array<FloatArray>>
                        Log.d(tag, "Batch array size: ${batchArray.size}")
                        if (batchArray.isNotEmpty()) {
                            val valuesArray = batchArray[0]
                            Log.d(tag, "Values array size: ${valuesArray.size}")
                            if (valuesArray.isNotEmpty()) {
                                val detectionSize = valuesArray[0].size
                                Log.d(tag, "Detection array size: $detectionSize")

                                // Iterate over detections first (8400), then values (6)
                                for (detIdx in 0 until detectionSize) {
                                    for (valIdx in 0 until valuesArray.size) {
                                        currentCoroutineContext().ensureActive()
                                        if (index < expectedSize) {
                                            byteBuffer.putFloat(
                                                index * 4,
                                                valuesArray[valIdx][detIdx]
                                            )
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
            } catch (e: Exception) {
                Log.e(tag, "Error processing output", e)
                throw e
            }

            // Postprocess results
            val results = postprocessResults(outputBuffer, bitmap.width, bitmap.height)

            // Clean up
            inputTensor.close()
            output.close()
            outputs.close()

            results
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            Log.e(tag, "Error during single image detection", e)
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        // Calculate letterbox dimensions to preserve aspect ratio
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val scale = minOf(
            inputImageWidth.toFloat() / originalWidth,
            inputImageHeight.toFloat() / originalHeight
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()

        // Resize with letterbox (preserve aspect ratio)
        val resizedBitmap = bitmap.scale(newWidth, newHeight)

        // Create letterbox bitmap with padding
        val letterboxBitmap =
            createBitmap(inputImageWidth, inputImageHeight)
        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(Color.BLACK) // Padding color
        val offsetX = (inputImageWidth - newWidth) / 2
        val offsetY = (inputImageHeight - newHeight) / 2
        canvas.drawBitmap(resizedBitmap, offsetX.toFloat(), offsetY.toFloat(), null)

        // Prepare buffer (NCHW format: batch, channels, height, width) - matches model input [1, 3, 640, 640]
        val expectedSize = 1 * 3 * inputImageHeight * inputImageWidth
        val buffer = FloatBuffer.allocate(expectedSize)
        Log.d(
            tag,
            "Preprocess: Original: ${originalWidth}x${originalHeight}, Resized: ${newWidth}x${newHeight}, Letterbox: ${inputImageWidth}x${inputImageHeight}, Scale: $scale"
        )

        // Convert bitmap to float array with normalization [0, 1]
        val pixels = IntArray(inputImageWidth * inputImageHeight)
        letterboxBitmap.getPixels(
            pixels,
            0,
            inputImageWidth,
            0,
            0,
            inputImageWidth,
            inputImageHeight
        )

        // NCHW format: first all reds, then all greens, then all blues
        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[y * inputImageWidth + x]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                buffer.put(r)
            }
        }

        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[y * inputImageWidth + x]
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                buffer.put(g)
            }
        }

        for (y in 0 until inputImageHeight) {
            for (x in 0 until inputImageWidth) {
                val pixel = pixels[y * inputImageWidth + x]
                val b = (pixel and 0xFF) / 255.0f
                buffer.put(b)
            }
        }

        resizedBitmap.recycle()
        letterboxBitmap.recycle()
        buffer.rewind()
        Log.d(
            tag,
            "Preprocess: Buffer position: ${buffer.position()}, Buffer remaining: ${buffer.remaining()}"
        )

        return buffer
    }

    private fun postprocessResults(
        outputBuffer: FloatBuffer,
        originalWidth: Int,
        originalHeight: Int
    ): List<DetectionResult> {
        outputBuffer.rewind()

        val results = mutableListOf<DetectionResult>()
        val confidenceThreshold = 0.5f

        // YOLOv8 output format: [1, numValues, numDetections]
        // numValues = 4(bbox) + классы, numDetections зависит от imgsz: 640→8400, 320→2100

        // Get output shape from session
        val outputShape = try {
            (ortSession?.outputInfo?.values?.first()?.info as? ai.onnxruntime.TensorInfo)?.shape
        } catch (e: Exception) {
            Log.w(tag, "Could not get output shape from info, using default", e)
            longArrayOf(1, 5, 8400)
        }
        val numDetections = outputShape?.getOrNull(2)?.toInt() ?: 8400
        val numValues = outputShape?.getOrNull(1)?.toInt() ?: 5

        // Calculate letterbox scale and offset (same as in preprocessing)
        val scale = minOf(
            inputImageWidth.toFloat() / originalWidth,
            inputImageHeight.toFloat() / originalHeight
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        val offsetX = (inputImageWidth - newWidth) / 2f
        val offsetY = (inputImageHeight - newHeight) / 2f

        Log.d(
            tag,
            "Postprocess: Original: ${originalWidth}x${originalHeight}, Scale: $scale, Offset: ($offsetX, $offsetY)"
        )
        Log.d(
            tag,
            "Buffer capacity: ${outputBuffer.capacity()}, numDetections: $numDetections, numValues: $numValues"
        )

        var detectionsAboveThreshold = 0
        for (i in 0 until numDetections) {
            // Find max confidence across all classes
            var maxConfidence = 0f
            var maxClassId = 0

            for (classId in 4 until numValues) {
                val classIndex = i * numValues + classId
                if (classIndex < outputBuffer.remaining()) {
                    val classConfidence = outputBuffer.get(classIndex)
                    if (classConfidence > maxConfidence) {
                        maxConfidence = classConfidence
                        maxClassId = classId - 4
                    }
                }
            }

            if (maxConfidence > confidenceThreshold) {
                detectionsAboveThreshold++
                // YOLO format: center_x, center_y, width, height (in letterbox coordinates)
                val centerX = outputBuffer.get(i * numValues)
                val centerY = outputBuffer.get(i * numValues + 1)
                val width = outputBuffer.get(i * numValues + 2)
                val height = outputBuffer.get(i * numValues + 3)

                // Remove letterbox offset
                val centerXOriginal = (centerX - offsetX) / scale
                val centerYOriginal = (centerY - offsetY) / scale
                val widthOriginal = width / scale
                val heightOriginal = height / scale

                // Convert to top-left coordinates
                val left = centerXOriginal - widthOriginal / 2
                val top = centerYOriginal - heightOriginal / 2
                val right = centerXOriginal + widthOriginal / 2
                val bottom = centerYOriginal + heightOriginal / 2

                // Clamp to original image bounds
                val clampedLeft = left.coerceIn(0f, originalWidth.toFloat())
                val clampedTop = top.coerceIn(0f, originalHeight.toFloat())
                val clampedRight = right.coerceIn(0f, originalWidth.toFloat())
                val clampedBottom = bottom.coerceIn(0f, originalHeight.toFloat())

                val boundingBox = RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)
                results.add(DetectionResult(boundingBox, maxConfidence, maxClassId))
            }
        }

        Log.d(tag, "Detections above threshold: $detectionsAboveThreshold")

        // Apply Non-Maximum Suppression (NMS)
        return applyNMS(results, 0.45f)
    }

    private fun applyNMS(
        results: List<DetectionResult>,
        iouThreshold: Float
    ): List<DetectionResult> {
        if (results.isEmpty()) return results

        // Sort by confidence (descending)
        val sorted = results.sortedByDescending { it.confidence }
        val selected = mutableListOf<DetectionResult>()

        for (result in sorted) {
            var keep = true
            for (selectedResult in selected) {
                val iou = calculateIoU(result.boundingBox, selectedResult.boundingBox)
                if (iou > iouThreshold) {
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
        iouThreshold: Float,
        iomThreshold: Float = 0.65f
    ): List<DetectionResult> {
        if (results.isEmpty()) return results

        val sorted = results.sortedByDescending { it.confidence }
        val merged = mutableListOf<DetectionResult>()
        val used = BooleanArray(sorted.size) { false }

        for (i in sorted.indices) {
            if (used[i]) continue

            val current = sorted[i]
            val overlapping = mutableListOf<DetectionResult>()
            overlapping.add(current)
            used[i] = true

            for (j in (i + 1) until sorted.size) {
                if (used[j]) continue
                val candidate = sorted[j]

                if (current.classId == candidate.classId) {
                    val iou = calculateIoU(current.boundingBox, candidate.boundingBox)
                    val iom = calculateIoM(current.boundingBox, candidate.boundingBox)

                    // Проверяем проекционное совпадение по осям (решает проблему сдвинутых боксов)
                    val hasAxisOverlap =
                        checkAxisOverlap(current.boundingBox, candidate.boundingBox)

                    // Объединяем по классическим метрикам ИЛИ по строгому осевому совпадению
                    if (iou > iouThreshold || iom > iomThreshold || hasAxisOverlap) {
                        overlapping.add(candidate)
                        used[j] = true
                    }
                }
            }

            if (overlapping.size > 1) {
                val mergedDetection = mergeDetectionsByUnion(overlapping)
                merged.add(mergedDetection)
            } else {
                merged.add(current)
            }
        }
        return merged
    }

    // НОВАЯ ФУНКЦИЯ: Проверяет, являются ли боксы частями одного объекта,
// у которого совпадает одна из осей (например, идеальный верх/низ, но сдвиг по бокам)
    private fun checkAxisOverlap(boxA: RectF, boxB: RectF): Boolean {
        // 1. Вычисляем перекрытие по вертикали (Y) и горизонтали (X)
        val overlapY = maxOf(0f, minOf(boxA.bottom, boxB.bottom) - maxOf(boxA.top, boxB.top))
        val overlapX = maxOf(0f, minOf(boxA.right, boxB.right) - maxOf(boxA.left, boxB.left))

        val heightA = boxA.bottom - boxA.top
        val heightB = boxB.bottom - boxB.top
        val widthA = boxA.right - boxA.left
        val widthB = boxB.right - boxB.left

        // 2. Считаем, какую долю высоты/ширины занимает перекрытие для меньшего из боксов
        val minHeight = minOf(heightA, heightB)
        val minWidth = minOf(widthA, widthB)

        val verticalRatio = if (minHeight > 0f) overlapY / minHeight else 0f
        val horizontalRatio = if (minWidth > 0f) overlapX / minWidth else 0f

        // Порог строгости совпадения осей (85% и выше)
        val axisThreshold = 0.85f

        // СЛУЧАЙ 1: Вертикальные границы (верх/низ) почти идеально совпадают (как в вашей проблеме),
        // и при этом боксы имеют хоть какое-то физическое пересечение по горизонтали
        if (verticalRatio > axisThreshold && overlapX > 0f) {
            return true
        }

        // СЛУЧАЙ 2: Горизонтальные границы (лево/право) почти идеально совпадают,
        // и есть пересечение по вертикали (для объектов, разрезанных горизонтальным стыком тайлов)
        if (horizontalRatio > axisThreshold && overlapY > 0f) {
            return true
        }

        return false
    }

    private fun mergeDetectionsByUnion(detections: List<DetectionResult>): DetectionResult {
        var minLeft = Float.MAX_VALUE
        var minTop = Float.MAX_VALUE
        var maxRight = Float.MIN_VALUE
        var maxBottom = Float.MIN_VALUE
        var maxConfidence = 0f

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
            classId = detections.first().classId
        )
    }

    private fun calculateIoM(boxA: RectF, boxB: RectF): Float {
        val intersectionX1 = maxOf(boxA.left, boxB.left)
        val intersectionY1 = maxOf(boxA.top, boxB.top)
        val intersectionX2 = minOf(boxA.right, boxB.right)
        val intersectionY2 = minOf(boxA.bottom, boxB.bottom)

        val intersectionWidth = maxOf(0f, intersectionX2 - intersectionX1)
        val intersectionHeight = maxOf(0f, intersectionY2 - intersectionY1)
        val intersectionArea = intersectionWidth * intersectionHeight

        if (intersectionArea == 0f) return 0f

        val areaA = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val areaB = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)

        return intersectionArea / minOf(areaA, areaB)
    }


    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight <= intersectionLeft || intersectionBottom <= intersectionTop) {
            return 0f
        }

        val intersectionArea =
            (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return intersectionArea / unionArea
    }

    private fun copyArrayToBuffer(array: Any, buffer: ByteBuffer, offset: Int): Int {
        return when (array) {
            is FloatArray -> {
                for (i in array.indices) {
                    buffer.putFloat(offset * 4 + i * 4, array[i])
                }
                array.size
            }

            is Array<*> -> {
                var currentOffset = offset
                for (element in array) {
                    if (element != null) {
                        val size = copyArrayToBuffer(element, buffer, currentOffset)
                        currentOffset += size
                    }
                }
                currentOffset - offset
            }

            else -> {
                Log.w(tag, "Unsupported array element type: ${array.javaClass}")
                0
            }
        }
    }

    private fun flattenArray(array: Any, flatList: MutableList<Float>) {
        when (array) {
            is FloatArray -> {
                array.forEach { flatList.add(it) }
            }

            is Array<*> -> {
                for (element in array) {
                    if (element != null) {
                        flattenArray(element, flatList)
                    }
                }
            }

            else -> {
                Log.w(tag, "Unsupported array element type: ${array.javaClass}")
            }
        }
    }

    fun close() {
        ortSession?.close()
        ortSession = null
        ortEnvironment.close()
    }
}
