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

class TrackOverlay {

    var calibration: MapCalibration? = null
    var currentFix: GpsFix? = null
    var northAngle: Double = 0.0
    var sourceToViewCoord: ((Double, Double) -> PointF?)? = null
    var trackPoints: List<TrackPoint> = emptyList()

    // Переиспользуемый Path (без аллокации на каждом кадре)
    private val trackPath = Path()

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

    fun draw(canvas: Canvas) {
        val cal = calibration ?: return
        val toView = sourceToViewCoord ?: return
        val points = trackPoints
        val totalSize = points.size

        // 1. Отрисовка траектории трека без создания лишних объектов
        if (totalSize >= 2) {
            trackPath.reset()
            var first = true
            var ptsInPath = 0

            // Адаптивный шаг прореживания для старой части трека (если точек > 2000),
            // последние 500 точек всегда рисуем с максимальной точностью
            val recentStart = (totalSize - 500).coerceAtLeast(0)
            val step = if (recentStart > 2000) recentStart / 2000 else 1

            var idx = 0
            while (idx < totalSize) {
                val isRecent = idx >= recentStart
                val point = points[idx]
                val gp = point.gpsFix.coordinate

                // Для старых точек применяем хэш-фильтр shouldDraw, свежие и последнюю рисуем всегда
                if (isRecent || idx == 0 || idx == totalSize - 1 || shouldDraw(gp.latitude, gp.longitude)) {
                    val pair = MapGeometry.gpsToImage(gp, cal, northAngle)
                    if (pair != null) {
                        val viewPoint = toView(pair.first, pair.second)
                        if (viewPoint != null) {
                            ptsInPath++
                            if (first) {
                                trackPath.moveTo(viewPoint.x, viewPoint.y)
                                first = false
                            } else {
                                trackPath.lineTo(viewPoint.x, viewPoint.y)
                            }
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
        val currentPair = MapGeometry.gpsToImage(fix.coordinate, cal, northAngle) ?: return
        val currentView = toView(currentPair.first, currentPair.second) ?: return

        canvas.drawCircle(currentView.x, currentView.y, 20.0f, positionPaint)
        canvas.drawCircle(currentView.x, currentView.y, 20.0f, positionStrokePaint)

        // 3. Вычисление и отрисовка вектора направления движения
        val bearing = if (totalSize >= 2) {
            val last = points[totalSize - 1].gpsFix.coordinate
            val prev = points[totalSize - 2].gpsFix.coordinate
            MapCalibrationUtils.bearing(prev, last)
        } else {
            fix.bearing
        }

        val bearingDeg = bearing % 360.0
        val aheadGps = MapGeometry.offsetGps(fix.coordinate, bearingDeg, 200.0)
        val aheadPair = MapGeometry.gpsToImage(aheadGps, cal, northAngle)

        if (aheadPair != null) {
            val aheadView = toView(aheadPair.first, aheadPair.second)
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
