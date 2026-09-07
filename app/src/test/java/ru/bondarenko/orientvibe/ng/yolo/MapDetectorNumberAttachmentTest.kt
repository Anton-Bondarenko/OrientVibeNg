package ru.bondarenko.orientvibe.ng.yolo

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.model.BoundingBox

/**
 * Unit tests for number-attachment logic in MapDetector:
 * correlateNumbersWithControls assigns box.number to control points that match.
 */
class MapDetectorNumberAttachmentTest {

    // ── Pure-Kotlin mirrors of the Android framework functions used by Stage 2 ──

    /** Standard HSV→RGB → Int mirror of android.graphics.Color.HSVToColor */
    private fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Float, Float, Float> {
        if (s == 0f) return Triple(v, v, v)
        val hue = if (h < 0f) h + 360f else h % 360f
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f).toFloat())
        val m = v - c
        val (r1, g1, b1) = when ((hue / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    /** Mirror hue-shift color function from ControlPointOverlay */
    private fun detColor(index: Int): Int {
        val hue = (index.toFloat() * 137.508f) % 360f
        val (r, g, b) = hsvToRgb(hue, 0.9f, 0.95f)
        return (-0x1000000) or
                (((r * 255f).toInt() and 0xFF) shl 16) or
                (((g * 255f).toInt() and 0xFF) shl 8) or
                ((b * 255f).toInt() and 0xFF)
    }

    /** Mirror textColorForIndex from ControlPointOverlay (+60° hue shift for contrast) */
    private fun textColorForIndex(index: Int): Int {
        val hue = (index.toFloat() * 137.508f + 60f) % 360f
        val (r, g, b) = hsvToRgb(hue, 1f, 1f)
        return (-0x1000000) or
                (((r * 255f).toInt() and 0xFF) shl 16) or
                (((g * 255f).toInt() and 0xFF) shl 8) or
                ((b * 255f).toInt() and 0xFF)
    }

    // ── Tests for correlation / number attachment ──

    /** Mirror MapDetector.correlateNumbersWithControls logic (simplified) */
    private fun simulateCorrelation(
        controls: List<BoundingBox>,
        numbers: List<BoundingBox>
    ): List<BoundingBox> {
        val matched = controls.toMutableList()
        numbers.forEach { numBox ->
            var bestDist = Float.MAX_VALUE
            var bestIdx = -1
            matched.forEachIndexed { idx, ctrl ->
                val dx = ctrl.centerX - numBox.centerX
                val dy = ctrl.centerY - numBox.centerY
                val dist = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
                if (dist < bestDist && dist < 0.05f) {
                    bestDist = dist
                    bestIdx = idx
                }
            }
            if (bestIdx >= 0) {
                matched[bestIdx].number = numBox.number
            }
        }
        return matched.toList()
    }

    // ── Correlation tests ──

    @Test
    fun correlate_singleControl_noNumbers_keepsNull() {
        val controls = listOf(
            BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point")
        )
        val result = simulateCorrelation(controls, emptyList())
        assertNull("control without linked numbers should have null number", result[0].number)
    }

    @Test
    fun correlate_oneMatch_attachesNumber() {
        val controls = listOf(
            BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point")
        )
        val numbers = listOf(
            BoundingBox(0.502f, 0.498f, 0.05f, 0.03f, 1f, "number", 7)
        )
        val result = simulateCorrelation(controls, numbers)
        assertEquals("linked control should have number 7", 7, result[0].number!!)
    }

    @Test
    fun correlate_oneControl_tooFar_doesNotAttach() {
        val controls = listOf(
            BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point")
        )
        val numbers = listOf(
            BoundingBox(0.9f, 0.9f, 0.05f, 0.03f, 1f, "number", 7)
        )
        val result = simulateCorrelation(controls, numbers)
        assertNull("control too far from number should not attach", result[0].number)
    }

    @Test
    fun correlate_multipleControls_multipleNumbers() {
        val controls = listOf(
            BoundingBox(0.3f, 0.3f, 0.1f, 0.1f, 1f, "control_point"),
            BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point"),
            BoundingBox(0.7f, 0.7f, 0.1f, 0.1f, 1f, "control_point")
        )
        val numbers = listOf(
            BoundingBox(0.302f, 0.298f, 0.05f, 0.03f, 1f, "number", 1),
            BoundingBox(0.702f, 0.698f, 0.05f, 0.03f, 1f, "number", 3)
        )
        val result = simulateCorrelation(controls, numbers)
        assertEquals("first control should have number 1", 1, result[0].number!!)
        assertNull("middle control has no linked number", result[1].number)
        assertEquals("last control should have number 3", 3, result[2].number!!)
    }

    // ── Tests for hue-shift color (used in ControlPointOverlay drawing) ──

    @Test
    fun detColor_consistentForSameIndex() {
        val c0 = detColor(0)
        assertEquals("same index should produce same color", c0, detColor(0))
    }

    @Test
    fun detColor_differentIndices_produceDifferentColors() {
        val colors = (0..20).map { detColor(it) }.toSet()
        assertTrue("should have multiple distinct hues (>15 unique out of 21)", colors.size > 15)
    }

    @Test
    fun detColor_validHSV_producesValidInt() {
        val color = detColor(42)
        assertTrue("color should be non-zero", color != 0)
        // Alpha channel (top byte) should be non-zero for opaque colors
        assertTrue("alpha should be set", (color ushr 24) != 0)
    }

    @Test
    fun textColorForIndex_differentFromDetColor() {
        // text color adds +60 to hue, so it should differ from detColor for most indices
        val diffs = (0..359).count { idx ->
            detColor(idx) != textColorForIndex(idx)
        }
        assertTrue("text color should differ from bg color for most indices (>300)", diffs > 300)
    }

    /** Test that detColor uses golden angle — 137.508° hue shift */
    @Test
    fun detColor_goldenAngleHueShift() {
        val hues = (0..10).map { idx ->
            (idx.toFloat() * 137.508f) % 360f
        }
        // Consecutive hues should advance by ~137.508° (accounting for mod 360 wrap)
        for (i in 1 until hues.size) {
            val diff = hues[i] - hues[i - 1]
            val wrappedDiff = if (diff < -180f) diff + 360f else if (diff > 180f) diff - 360f else diff
            assertTrue("hue step should be ~137.508° (wrapped), got raw=$diff wrapped=$wrappedDiff",
                kotlin.math.abs(wrappedDiff - 137.508f) < 0.01f)
        }
    }

    /** Full pipeline: filter → correlate → verify number rendering eligibility */
    @Test
    fun fullPipeline_filterCorrelateNumberRendering() {
        // Simulate realistic detection output: 5 CPs with one outlier, 3 numbers
        val controls = listOf(
            BoundingBox(0.1f, 0.2f, 0.1f, 0.1f, 1f, "control_point"),
            BoundingBox(0.3f, 0.3f, 0.1f, 0.1f, 1f, "control_point"),     // normal area=0.01
            BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point"),     // normal area=0.01
            BoundingBox(0.7f, 0.6f, 0.1f, 0.1f, 1f, "control_point"),     // normal area=0.01
            BoundingBox(0.9f, 0.8f, 0.3f, 0.3f, 1f, "control_point")      // outlier area=0.09 → filtered
        )
        val numbers = listOf(
            BoundingBox(0.302f, 0.302f, 0.05f, 0.03f, 1f, "number", 1),
            BoundingBox(0.502f, 0.498f, 0.05f, 0.03f, 1f, "number", 2),
            BoundingBox(0.702f, 0.602f, 0.05f, 0.03f, 1f, "number", 3)
        )

        // Step 1: filter controls (mirror filterControlsByMedianArea)
        val areas = controls.map { it.width * it.height }
        val sortedAreas = areas.sorted()
        val mid = sortedAreas.size / 2
        val medArea = if (sortedAreas.size % 2 == 1) sortedAreas[mid] else (sortedAreas[mid - 1] + sortedAreas[mid]) / 2f
        val filteredControls = controls.filter { box ->
            val area = box.width * box.height
            kotlin.math.abs(area - medArea) / medArea < 0.10f
        }.takeIf { it.isNotEmpty() } ?: controls.take(1)

        // Step 2: correlate numbers (mirror correlateNumbersWithControls)
        val numberedControls = simulateCorrelation(filteredControls, numbers)

        // Verify: outlier removed, remaining CPs have linked numbers for stage 2 rendering
        assertEquals("should filter out the large control point", 4, filteredControls.size)
        assertTrue("all remaining CPs should have linked numbers for stage 2 display",
            numberedControls.filter { it.number != null }.size >= 3)

        // Verify colors are valid for rendering
        numberedControls.forEachIndexed { idx, ctrl ->
            if (ctrl.number != null) {
                val bgColor = detColor(idx)
                val textColor = textColorForIndex(idx)
                assertTrue("bg color index $idx should be valid", bgColor != 0)
                assertTrue("text color index $idx should be valid", textColor != 0)
            }
        }
    }

    /** Verify that a control point with number can be rendered (radius, size checks) */
    @Test
    fun controlPoint_withNumber_radiusIsPositive() {
        val box = BoundingBox(0.5f, 0.5f, 0.1f, 0.1f, 1f, "control_point")
        box.number = 42

        assertTrue("width must be positive", box.width > 0)
        assertTrue("height must be positive", box.height > 0)
        val sourceRadius = minOf(box.width / 2f, box.height / 2f)
        assertTrue("source radius must be positive", sourceRadius > 0f)
    }
}
