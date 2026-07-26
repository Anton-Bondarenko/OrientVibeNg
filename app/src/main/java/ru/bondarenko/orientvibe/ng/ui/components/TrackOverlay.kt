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
     * The calibration bearing correctly maps GPS north to the image orientation,
     * so no additional rotation is needed.
     */
    private fun gpsToImageAbs(gps: GpsCoordinate): PointF? {
        val cal = calibration ?: return null
        val (sWidth, sHeight) = imageDimensions ?: return null
        if (sWidth <= 0 || sHeight <= 0) return null
        val imageCoords = MapCalibrationUtils.gpsToImage(gps, cal) ?: return null
        return PointF(imageCoords.first * sWidth, imageCoords.second * sHeight)
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
        val cal = calibration ?: return
        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return

        // --- Draw track line ---
        if (trackPoints.size >= 2) {
            val path = Path()
            var first = true
            for (point in trackPoints) {
                val imagePt = gpsToImageAbs(point.gpsFix.coordinate) ?: continue
                val viewPoint = sourceToViewCoord?.invoke(imagePt.x, imagePt.y)
                if (viewPoint != null) {
                    if (first) {
                        path.moveTo(viewPoint.x, viewPoint.y)
                        first = false
                    } else {
                        path.lineTo(viewPoint.x, viewPoint.y)
                    }
                }
            }
            if (!first) {
                canvas.drawPath(path, trackPaint)
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

        // Direction line via GPS coordinates: compute a point ~50m ahead along bearing,
        // convert to view space, and draw the line. This correctly accounts for calibration.
        val bearingDeg = fix.bearing % 360f
        val aheadGps = offsetGps(fix.coordinate, bearingDeg.toDouble(), 200.0)
        val aheadImage = gpsToImageAbs(aheadGps) ?: return
        val aheadView = sourceToViewCoord?.invoke(aheadImage.x, aheadImage.y) ?: return

        val dx = aheadView.x - currentView.x
        val dy = aheadView.y - currentView.y
        val visualAngle = ((Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360).toFloat()
        android.util.Log.d(TAG, "direction: fix.bearing=$bearingDeg, viewAngle=$visualAngle, " +
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
