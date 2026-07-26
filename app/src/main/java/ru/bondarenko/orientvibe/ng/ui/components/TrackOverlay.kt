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
     * Convert a GPS coordinate to view coordinates using the current calibration.
     * This is resilient to scale and north direction changes because GPS
     * coordinates are absolute and conversion happens at render time.
     */
    private fun gpsToView(gps: GpsCoordinate): PointF? {
        val cal = calibration ?: return null
        val imageCoords = MapCalibrationUtils.gpsToImage(gps, cal) ?: return null
        val (sWidth, sHeight) = imageDimensions ?: return null
        if (sWidth <= 0 || sHeight <= 0) return null
        return sourceToViewCoord?.invoke(imageCoords.first * sWidth, imageCoords.second * sHeight)
    }

    /**
     * Convert a GPS coordinate to image coordinates (absolute pixels).
     */
    private fun gpsToImageAbs(gps: GpsCoordinate): PointF? {
        val cal = calibration ?: return null
        val (sWidth, sHeight) = imageDimensions ?: return null
        if (sWidth <= 0 || sHeight <= 0) return null
        val imageCoords = MapCalibrationUtils.gpsToImage(gps, cal) ?: return null
        return PointF(imageCoords.first * sWidth, imageCoords.second * sHeight)
    }

    fun draw(canvas: Canvas) {
        val cal = calibration ?: return
        val (sWidth, sHeight) = imageDimensions ?: return
        if (sWidth <= 0 || sHeight <= 0) return

        // --- Draw track line ---
        if (trackPoints.size >= 2) {
            val path = Path()
            var first = true
            for (point in trackPoints) {
                val viewPoint = gpsToView(point.gpsFix.coordinate)
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
        val currentView = gpsToView(fix.coordinate) ?: return

        // Draw position circle
        val radius = 20f
        canvas.drawCircle(currentView.x, currentView.y, radius, positionPaint)
        canvas.drawCircle(currentView.x, currentView.y, radius, positionStrokePaint)

        // Draw direction line
        // The direction is computed in image space, then converted to view space.
        // This ensures the line is correctly oriented even when the map is rotated.
        //
        // GPS bearing is relative to true north.
        // calBearing is the angle of the image Y-axis relative to true north.
        // northAngle is the deviation of the map's north (magnetic) from true north.
        //
        // imageBearing = direction of GPS bearing in image space
        // = fix.bearing - calBearing - northAngle
        // This gives the direction relative to the image Y-axis, adjusted for
        // the magnetic north offset.
        val calBearing = cal.bearingDegrees
        val imageBearing = (fix.bearing - calBearing.toFloat() - northAngle + 360f) % 360f
        val bearingRad = Math.toRadians(imageBearing.toDouble())

        // Get current position in image coordinates (absolute pixels)
        val currentImage = gpsToImageAbs(fix.coordinate) ?: return

        // Compute endpoint in image coordinates
        val lineLengthPx = 100f // pixels in image space
        val endImageX = currentImage.x + lineLengthPx * sin(bearingRad).toFloat()
        val endImageY = currentImage.y - lineLengthPx * cos(bearingRad).toFloat()

        // Convert both points to view coordinates (handles map rotation)
        val startView = sourceToViewCoord?.invoke(currentImage.x, currentImage.y)
        val endView = sourceToViewCoord?.invoke(endImageX, endImageY)

        if (startView != null && endView != null) {
            canvas.drawLine(startView.x, startView.y, endView.x, endView.y, directionPaint)

            // Draw direction arrow at the end of the line
            val arrowSize = 15f
            val arrowAngle = 0.5f
            val dx = endView.x - startView.x
            val dy = endView.y - startView.y
            val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (len > 0) {
                val ux = dx / len
                val uy = dy / len

                val arrowPath = Path().apply {
                    moveTo(endView.x, endView.y)
                    lineTo(
                        endView.x - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                                uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                        endView.y - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                                ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                    )
                    lineTo(
                        endView.x - ux * arrowSize * cos(arrowAngle.toDouble()).toFloat() +
                                uy * arrowSize * sin(arrowAngle.toDouble()).toFloat(),
                        endView.y - uy * arrowSize * cos(arrowAngle.toDouble()).toFloat() -
                                ux * arrowSize * sin(arrowAngle.toDouble()).toFloat()
                    )
                    close()
                }
                canvas.drawPath(arrowPath, directionArrowPaint)
            }
        }
    }
}
