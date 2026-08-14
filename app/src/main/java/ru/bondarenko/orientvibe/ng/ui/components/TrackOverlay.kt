package ru.bondarenko.orientvibe.ng.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Overlay for rendering GPS track and current position with direction indicator.
 *
 * Track points are stored in GPS coordinates and converted to image coordinates
 * at render time using the current calibration. This ensures the track is
 * correctly rendered even if the map scale or north direction changes.
 *
 * The direction line is computed in image space (accounting for calibration bearing
 * and north angle) and then converted to view coordinates, which automatically
 * handles map rotation.
 *
 * The map's "North" is magnetic north (as set by the user via the north indicator).
 * The GPS bearing is relative to true north. The northAngle represents the
 * deviation between the map's north (magnetic) and true north.
 */
class TrackOverlay {

    /** Track points stored as GPS coordinates — resilient to calibration changes */
    var trackPoints: List<TrackPoint> = emptyList()

    /** Current GPS fix for position indicator */
    var currentFix: GpsFix? = null

    /** Current calibration for GPS-to-image coordinate conversion */
    var calibration: MapCalibration? = null

    /** North angle adjustment (degrees, 0 = up, positive = CW) */
    var northAngle: Float = 0f

    // Source-to-view coordinate conversion — set externally
    var sourceToViewCoord: ((Float, Float) -> PointF?)? = null
    var imageDimensions: Pair<Float, Float>? = null // (width, height) in source pixels

    private val trackPaint = Paint().apply {
        color = Color.argb(200, 0, 150, 255) // semi-transparent blue
        style = Paint.Style.STROKE
        strokeWidth = 8f
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
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val directionPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val directionArrowPaint = Paint().apply {
        color = Color.argb(255, 0, 150, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Convert a GPS coordinate to image coordinates (absolute pixels).
     * Uses full calibration (scale + bearing rotation) plus optional northAngle adjustment.
     */
    private fun gpsToImageAbs(gps: GpsCoordinate): PointF? {
        val cal = calibration ?: run { android.util.Log.w(TAG, "gpsToImageAbs CAL NULL") ; return null }
        val (sWidth, sHeight) = imageDimensions ?: run { android.util.Log.w(TAG, "gpsToImageAbs DIMS null") ; return null }
        if (sWidth <= 0 || sHeight <= 0) run { android.util.Log.w(TAG, "gpsToImageAbs DIMS<=0 ($sWidth $sHeight)") ; return null }

        // gpsToImage returns ABSOLUTE pixels (pointA.imageX + relDx in pixels), NOT normalized 0..1
        val imageCoords = MapCalibrationUtils.gpsToImage(gps, cal)
        if (imageCoords == null) { android.util.Log.w(TAG, "gpsToImageRel NULL for lat=${gps.latitude} lon=${gps.longitude}") ; return null }
        var x = imageCoords.first
        var y = imageCoords.second

        // Step 2: Apply northAngle rotation around a fixed pivot.
        // Use calibration pointA (the map anchor) as the rotation center, NOT trackPoints.first()
        // because trim removes old points which changes the first element, causing visual drift.
        if (northAngle != 0f) {
            val calAnchorGps = calibration?.pointA?.gps
            val pivotGps = calAnchorGps ?: currentFix?.coordinate
            val pivotImg = pivotGps?.let { MapCalibrationUtils.gpsToImage(it, cal) }
            if (pivotImg != null) {
                val px = pivotImg.first
                val py = pivotImg.second
                val angleRad = Math.toRadians(northAngle.toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()
                val dx = x - px
                val dy = y - py
                x = px + dx * cosA - dy * sinA
                y = py + dx * sinA + dy * cosA
            } else {
                android.util.Log.w(TAG, "gpsToImageAbs pivot NULL lat=${gps.latitude}")
            }
        }

        return PointF(x, y)
    }

    /**
     * Offset a GPS coordinate by a given bearing (degrees from true north) and distance (meters).
     * Uses spherical earth approximation.
     */
    private fun offsetGps(from: GpsCoordinate, bearingDeg: Double, distanceMeters: Double): GpsCoordinate {
        val earthRadius = 6_371_000.0
        val angularDistance = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1Rad = Math.toRadians(from.latitude)
        val lon1Rad = Math.toRadians(from.longitude)

        val lat2Rad = kotlin.math.asin(
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(angularDistance) +
            kotlin.math.cos(lat1Rad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(bearingRad)
        )

        val lon2Rad = lon1Rad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(lat1Rad),
            kotlin.math.cos(angularDistance) - kotlin.math.sin(lat1Rad) * kotlin.math.sin(lat2Rad)
        )

        return GpsCoordinate(
            latitude = Math.toDegrees(lat2Rad),
            longitude = Math.toDegrees(lon2Rad)
        )
    }

    /** Debug log tag */
    private val TAG = "TrackOverlay"

    fun draw(canvas: Canvas) {
        val cal = calibration ?: run { android.util.Log.w(TAG, "draw() CAL NULL") ; return }
        val (sWidth, sHeight) = imageDimensions ?: run { android.util.Log.w(TAG, "draw() DIMS null ($imageDimensions)") ; return }
        if (sWidth <= 0 || sHeight <= 0) run { android.util.Log.w(TAG, "draw() DIMS <= 0 ($sWidth x $sHeight)") ; return }

        // === TAG LOGGING START ===
        android.util.Log.d(TAG, "== draw() START: calBearing=${cal.bearingDegrees}, scale=${cal.scaleMetersPerPixel}, northAngle=$northAngle, dims=$sWidth x $sHeight, points=${trackPoints.size} ==")

        // --- Draw track line ---
        if (trackPoints.size >= 2) {
            val path = Path()
            var first = true
            var firstGps: GpsCoordinate? = null
            var lastGps: GpsCoordinate? = null
            var firstView: PointF? = null
            var lastView: PointF? = null
            var ptsInPath = 0
            for ((i, point) in trackPoints.withIndex()) {
                val gp = point.gpsFix.coordinate
                val imagePt = gpsToImageAbs(gp)
                if (imagePt == null) {
                    android.util.Log.w(TAG, "  pt[$i] gpsToImageAbs returned NULL")
                    continue
                }

                val viewPoint = sourceToViewCoord?.invoke(imagePt.x, imagePt.y)
                if (viewPoint != null) {
                    ptsInPath++
                    if (first) {
                        path.moveTo(viewPoint.x, viewPoint.y)
                        first = false
                        firstGps = gp
                        firstView = viewPoint
                    } else {
                        path.lineTo(viewPoint.x, viewPoint.y)
                    }
                    lastGps = gp
                    lastView = viewPoint
                } else {
                    android.util.Log.w(TAG, "  pt[$i] sourceToViewCoord returned NULL")
                }
            }

            if (!first) {
                val dlen = if (firstView != null && lastView != null) {
                    val dx2 = lastView.x - firstView.x
                    val dy2 = lastView.y - firstView.y
                    sqrt((dx2*dx2 + dy2*dy2).toDouble())
                } else 0.0
                android.util.Log.d(TAG, "  drawn ptsInPath=$ptsInPath, firstView=($firstView), lastView=($lastView), pathLenPx=${dlen}, imgW=${sWidth.toInt()}, imgH=${sHeight.toInt()}")
                android.util.Log.d(TAG, "  path.moveTo/lineTo within canvas bounds? minX=${minOf(firstView?.x ?: Float.MAX_VALUE, (lastView?.x ?: Float.MAX_VALUE))}, maxX=${maxOf(firstView?.x ?: -Float.MAX_VALUE, (lastView?.x ?: -Float.MAX_VALUE))}, minY=${minOf(firstView?.y ?: Float.MAX_VALUE, (lastView?.y ?: Float.MAX_VALUE))}, maxY=${maxOf(firstView?.y ?: -Float.MAX_VALUE, (lastView?.y ?: -Float.MAX_VALUE))}")
                canvas.drawPath(path, trackPaint)
            } else {
                android.util.Log.w(TAG, "  ptsInPath=0 — no valid points for path")
            }
        }

        // --- Draw current position with direction ---
        val fix = currentFix ?: return
        val currentImage = gpsToImageAbs(fix.coordinate) ?: return
        val currentView = sourceToViewCoord?.invoke(currentImage.x, currentImage.y) ?: return

        // Draw position circle
        val radius = 20f
        canvas.drawCircle(currentView.x, currentView.y, radius, positionPaint)
        canvas.drawCircle(currentView.x, currentView.y, radius, positionStrokePaint)

        // Direction line: derive bearing from actual track movement (GPS, true north).
        // This matches the track which uses gpsToImage + northAngle (with declination correction).
        val bearingDeg = if (trackPoints.size >= 2) {
            val last = trackPoints.last().gpsFix.coordinate
            val prev = trackPoints[trackPoints.size - 2].gpsFix.coordinate
            MapCalibrationUtils.bearing(prev, last)
        } else {
            fix.bearing.toDouble()
        } % 360.0

        // Log both real GPS device bearing and calculated true-bearing for comparison
        val realGpsBearing = fix.bearing.toDouble()
        val calDeclination = if (cal.magneticDeclination != 0.0) MapCalibrationUtils.effectiveDeclination(cal) else 0.0
        android.util.Log.d(TAG, "DIR: real_gps_bearing=$realGpsBearing calc_bearing=$bearingDeg calDeclination=$calDeclination")

        val aheadGps = offsetGps(fix.coordinate, bearingDeg, 200.0)
        val aheadImage = gpsToImageAbs(aheadGps) ?: return
        val aheadView = sourceToViewCoord?.invoke(aheadImage.x, aheadImage.y) ?: return

        val dx = aheadView.x - currentView.x
        val dy = aheadView.y - currentView.y
        val visualAngle = ((Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360).toFloat()
        android.util.Log.d(TAG, "DIR: fix.bearing=$bearingDeg, viewAngle=$visualAngle, " +
                "northAngle=$northAngle, calBearing=${cal.bearingDegrees}")

        canvas.drawLine(currentView.x, currentView.y, aheadView.x, aheadView.y, directionPaint)

        // Draw direction arrow at ahead point
        val arrowSize = 15f
        val arrowAngle = 0.5f
        val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        if (len > 0) {
            val ux = dx / len
            val uy = dy / len

            val arrowPath = Path().apply {
                moveTo(aheadView.x, aheadView.y)
                lineTo(
                    aheadView.x - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                            uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                    aheadView.y - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                            ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                )
                lineTo(
                    aheadView.x - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                            uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                    aheadView.y - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                            ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                )
                close()
            }
            canvas.drawPath(arrowPath, directionArrowPaint)
        }
    }
}
