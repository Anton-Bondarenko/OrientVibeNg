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
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
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
    private fun gpsToImage(gps: GpsCoordinate): PointF? {
        val pair = MapGeometry.gpsToImage(gps, calibration, northAngle)
        return pair?.let { PointF(it.first, it.second) }
    }

    /** Debug log tag */
    private val TAG = "TrackOverlay"

    fun draw(canvas: Canvas) {
        calibration ?: run { android.util.Log.w(TAG, "draw() CAL NULL"); return }

        // --- Draw track line ---
        if (trackPoints.size >= 2) {
            val path = Path()
            var first = true
            var ptsInPath = 0
            for ((i, point) in trackPoints.withIndex()) {
                val gp = point.gpsFix.coordinate
                val imagePt = gpsToImage(gp)
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
                    } else {
                        path.lineTo(viewPoint.x, viewPoint.y)
                    }
                } else {
                    android.util.Log.w(TAG, "  pt[$i] sourceToViewCoord returned NULL")
                }
            }

            if (!first) {
                canvas.drawPath(path, trackPaint)
            } else {
                android.util.Log.w(TAG, "  ptsInPath=0 — no valid points for path")
            }
        }

        // --- Draw current position with direction ---
        val fix = currentFix ?: return
        val currentImage = gpsToImage(fix.coordinate) ?: return
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
            fix.bearing
        } % 360f

        val aheadGps = MapGeometry.offsetGps(fix.coordinate, bearingDeg, 200.0)
        val aheadImage = gpsToImage(aheadGps) ?: return
        val aheadView = sourceToViewCoord?.invoke(aheadImage.x, aheadImage.y) ?: return

        val dx = aheadView.x - currentView.x
        val dy = aheadView.y - currentView.y

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
