package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.TrackPoint
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry

/**
 * Слой наложения (Overlay) для отрисовки пройденной траектории пользователя
 * и указателя текущей позиции с вектором направления движения.
 */
class TrackOverlay {

    var calibration: MapCalibration? = null
    var currentFix: GpsFix? = null
    var northAngle: Double = 0.0
    var sourceToViewCoord: ((Double, Double) -> PointF?)? = null
    var trackPoints: List<TrackPoint> = emptyList()

    private val tag = "TrackOverlay"

    // Определение стилей кистей (Paint)
    private val trackPaint = Paint().apply {
        color = Color.argb(180, 0, 150, 255) // Исправлено: полупрозрачный синий (180 альфа вместо invocationKey)
        style = Paint.Style.STROKE
        strokeWidth = 8.0f
        isAntiAlias = true
    }

    private val positionPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val positionStrokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        isAntiAlias = true
    }

    private val directionPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6.0f
        isAntiAlias = true
    }

    private val directionArrowPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun gpsToImage(gps: GpsCoordinate?): PointF? {
        val targetGps = gps ?: return null
        val cal = calibration ?: return null
        val pair = MapGeometry.gpsToImage(targetGps, cal, northAngle) ?: return null
        return PointF(pair.first.toFloat(), pair.second.toFloat())
    }

    /**
     * Проверка необходимости отрисовки конкретной точки трека на основе хеша координат.
     */
    private fun shouldDraw(lat: Double, lon: Double): Boolean {
        val latBits = (lat * 1.0E7).toLong()
        val lonBits = (1.0E7 * lon).toLong()
        val combined = latBits xor lonBits
        return ((combined ushr 1) and 1L).toInt() == 0
    }

    fun draw(canvas: Canvas) {
        if (calibration == null) return
        val toView = sourceToViewCoord ?: return

        // 1. Отрисовка траектории трека (без битового фильтра shouldDraw)
        if (trackPoints.size >= 2) {
            val path = Path()
            var first = true
            var ptsInPath = 0

            trackPoints.forEachIndexed { idx, point ->
                val gp = point.gpsFix.coordinate
                val imagePt = gpsToImage(gp)
                if (imagePt == null) {
                    Log.w(tag, "  pt[$idx] gpsToImage returned NULL")
                } else {
                    val viewPoint = toView(imagePt.x.toDouble(), imagePt.y.toDouble())
                    if (viewPoint == null) {
                        Log.w(tag, "  pt[$idx] sourceToViewCoord returned NULL")
                    } else {
                        ptsInPath++
                        if (first) {
                            path.moveTo(viewPoint.x, viewPoint.y)
                            first = false
                        } else {
                            path.lineTo(viewPoint.x, viewPoint.y)
                        }
                    }
                }
            }

            if (!first && ptsInPath >= 2) {
                canvas.drawPath(path, trackPaint)
            } else {
                Log.w(tag, "  ptsInPath=$ptsInPath — no valid points for path")
            }
        }

        // 2. Отрисовка точки текущего местоположения (GpsFix)
        val fix = currentFix ?: return
        val currentImage = gpsToImage(fix.coordinate) ?: return
        val currentView = toView(currentImage.x.toDouble(), currentImage.y.toDouble()) ?: return

        canvas.drawCircle(currentView.x, currentView.y, 20.0f, positionPaint)
        canvas.drawCircle(currentView.x, currentView.y, 20.0f, positionStrokePaint)

        // 3. Вычисление и отрисовка вектора направления движения
        val bearing = if (trackPoints.size >= 2) {
            val last = trackPoints.last().gpsFix.coordinate
            val prev = trackPoints[trackPoints.size - 2].gpsFix.coordinate
            MapCalibrationUtils.bearing(prev, last)
        } else {
            fix.bearing
        }

        val bearingDeg = bearing % 360.0
        val aheadGps = MapGeometry.offsetGps(fix.coordinate, bearingDeg, 200.0)
        val aheadImage = gpsToImage(aheadGps)

        if (aheadImage != null) {
            val aheadView = toView(aheadImage.x.toDouble(), aheadImage.y.toDouble())
            if (aheadView != null) {
                canvas.drawLine(
                    currentView.x,
                    currentView.y,
                    aheadView.x,
                    aheadView.y,
                    directionPaint
                )
            }
        }
    }
}
