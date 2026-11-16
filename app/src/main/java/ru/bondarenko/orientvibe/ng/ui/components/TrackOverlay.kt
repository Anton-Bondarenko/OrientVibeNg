package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.TrackPoint

/**
 * Слой наложения (Overlay) для отрисовки пройденной траектории пользователя
 * и указателя текущей позиции с вектором направления движения.
 */
class TrackOverlay {

    var calibration: MapCalibration? = null
    var currentFix: GpsFix? = null
    var northAngle: Double = 0.0
    var sourceToViewCoord: ((Double, Double) -> PointF?)? = null
    var sourceToViewCoordFast: ((Double, Double, PointF) -> Boolean)? = null
    var trackPoints: List<TrackPoint> = emptyList()

    // Переиспользуемые объекты для отрисовки без выделения памяти в цикле
    private val trackPath = Path()
    private val tempImagePt = PointF()
    private val tempViewPt = PointF()
    private val tempAheadPt = PointF()

    private val trackPaint = Paint().apply {
        color = Color.argb(180, 0, 150, 255)
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

    private fun shouldDraw(lat: Double, lon: Double): Boolean {
        val latBits = (lat * 1.0E7).toLong()
        val lonBits = (1.0E7 * lon).toLong()
        val combined = latBits xor lonBits
        return ((combined ushr 1) and 1L).toInt() == 0
    }

    private fun resolveViewPoint(imgX: Double, imgY: Double, outPt: PointF): Boolean {
        val fast = sourceToViewCoordFast
        if (fast != null) {
            return fast(imgX, imgY, outPt)
        }
        val fallback = sourceToViewCoord?.invoke(imgX, imgY) ?: return false
        outPt.set(fallback.x, fallback.y)
        return true
    }

    fun draw(canvas: Canvas) {
        val cal = calibration ?: return
        if (sourceToViewCoordFast == null && sourceToViewCoord == null) return

        val points = trackPoints
        val totalSize = points.size

        // 1. Отрисовка траектории трека (0 аллокаций в цикле)
        if (totalSize >= 2) {
            trackPath.reset()
            var first = true
            var ptsInPath = 0

            // Последние 500 точек рисуем полностью, более старые прореживаем с адаптивным шагом
            val recentStart = (totalSize - 500).coerceAtLeast(0)
            val step = if (recentStart > 2000) recentStart / 2000 else 1

            var idx = 0
            while (idx < totalSize) {
                val isRecent = idx >= recentStart
                val point = points[idx]
                val lat = point.latitude
                val lon = point.longitude

                if (isRecent || idx == 0 || idx == totalSize - 1 || shouldDraw(lat, lon)) {
                    MapGeometry.gpsToImagePrim(lat, lon, cal, northAngle, tempImagePt)
                    if (resolveViewPoint(tempImagePt.x.toDouble(), tempImagePt.y.toDouble(), tempViewPt)) {
                        ptsInPath++
                        if (first) {
                            trackPath.moveTo(tempViewPt.x, tempViewPt.y)
                            first = false
                        } else {
                            trackPath.lineTo(tempViewPt.x, tempViewPt.y)
                        }
                    }
                }
                idx += if (isRecent) 1 else step
            }

            if (!first && ptsInPath >= 2) {
                canvas.drawPath(trackPath, trackPaint)
            }
        }

        // 2. Отрисовка точки текущего местоположения (GpsFix)
        val fix = currentFix ?: return
        MapGeometry.gpsToImagePrim(fix.coordinate.latitude, fix.coordinate.longitude, cal, northAngle, tempImagePt)
        if (!resolveViewPoint(tempImagePt.x.toDouble(), tempImagePt.y.toDouble(), tempViewPt)) return

        canvas.drawCircle(tempViewPt.x, tempViewPt.y, 20.0f, positionPaint)
        canvas.drawCircle(tempViewPt.x, tempViewPt.y, 20.0f, positionStrokePaint)

        // 3. Вычисление и отрисовка вектора направления движения
        val bearing = if (totalSize >= 2) {
            val last = points[totalSize - 1]
            val prev = points[totalSize - 2]
            MapCalibrationUtils.bearing(
                GpsCoordinate(prev.latitude, prev.longitude),
                GpsCoordinate(last.latitude, last.longitude)
            )
        } else {
            fix.bearing
        }

        val bearingDeg = bearing % 360.0
        val aheadGps = MapGeometry.offsetGps(fix.coordinate, bearingDeg, 200.0)
        MapGeometry.gpsToImagePrim(aheadGps.latitude, aheadGps.longitude, cal, northAngle, tempImagePt)

        if (resolveViewPoint(tempImagePt.x.toDouble(), tempImagePt.y.toDouble(), tempAheadPt)) {
            canvas.drawLine(
                tempViewPt.x,
                tempViewPt.y,
                tempAheadPt.x,
                tempAheadPt.y,
                directionPaint
            )
        }
    }
}
