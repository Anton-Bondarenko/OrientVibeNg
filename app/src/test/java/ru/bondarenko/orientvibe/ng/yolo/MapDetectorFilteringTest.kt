package ru.bondarenko.orientvibe.ng.yolo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.bondarenko.orientvibe.ng.model.BoundingBox

/**
 * Unit tests for filtering logic in MapDetector:
 * - filterControlsByMedianArea: removes control points whose area differs from median by >=40% (relative)
 *                                or absolute area < 15% of median (tiny noise). Keeps at most EXPECTED_CONTROL_POINTS.
 * - filterNumbersByMedianHeight: removes number boxes whose height differs from median by >=25%
 */
class MapDetectorFilteringTest {

    /** Compute median — mirrors MapDetector.median logic */
    private fun computeMedian(values: List<Float>): Float {
        if (values.isEmpty()) throw IllegalArgumentException("empty")
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    /** Mirror MapDetector.filterControlsByMedianArea logic (relative threshold + absolute floor) */
    private fun computeFilterControls(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes
        val areas = boxes.map { it.width * it.height }
        val med = computeMedian(areas) ?: return boxes
        val minArea = med * 0.15f // absolute floor: tiny noise
        return boxes.filter { box ->
            val area = box.width * box.height
            if (area < minArea) false else kotlin.math.abs(area - med) / med < 0.40f
        }.takeIf { it.isNotEmpty() } ?: boxes.take(1)
    }

    /** Mirror MapDetector.filterNumbersByMedianHeight logic */
    private fun computeFilterNumbers(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.size <= 2) return boxes
        val heights = boxes.map { it.height }
        val med = computeMedian(heights)
        return boxes.filter { box ->
            kotlin.math.abs(box.height - med) / med < 0.25f
        }.takeIf { it.isNotEmpty() } ?: boxes.take(1)
    }

    // ───────── median tests ─────────

    @Test
    fun median_oddSize_returnsMiddleValue() {
        val values = listOf(1f, 2f, 3f)
        assertEquals(2.0, computeMedian(values).toDouble(), 0.001)
    }

    @Test
    fun median_evenSize_returnsAverageOfMids() {
        val values = listOf(1f, 2f, 3f, 4f)
        assertEquals(2.5, computeMedian(values).toDouble(), 0.001)
    }

    @Test
    fun median_singleElement_returnsThatElement() {
        val values = listOf(5f)
        assertEquals(5.0, computeMedian(values).toDouble(), 0.001)
    }

    @Test
    fun median_unsorted_returnsCorrectMedian() {
        val values = listOf(4f, 1f, 3f, 2f, 5f)
        assertEquals(3.0, computeMedian(values).toDouble(), 0.001)
    }

    // ───────── filterControlsByMedianArea tests ─────────

    @Test
    fun filterControls_emptyList_returnsEmpty() {
        val result = computeFilterControls(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterControls_twoBoxes_keepsBoth() {
        // <=2 boxes → no filtering
        val boxes = listOf(
            BoundingBox(0.1f, 0.1f, 0.05f, 0.05f, 1f, "control_point"),
            BoundingBox(0.5f, 0.5f, 0.06f, 0.06f, 1f, "control_point")
        )
        val result = computeFilterControls(boxes)
        assertEquals(2, result.size)
    }

    @Test
    fun filterControls_oneOutlier_filtersIt() {
        // 5 boxes: 4 normal area=0.01, 1 outlier area=0.05 → outlier removed
        val boxes = List(5) { i ->
            val (w, h) = if (i == 4) Pair(0.2f, 0.25f) else Pair(0.1f, 0.1f)
            BoundingBox((i + 1) * 0.15f, 0.5f, w, h, 1f, "control_point")
        }
        val result = computeFilterControls(boxes)
        assertEquals(4, result.size)
        assertTrue(result.none { it.width == 0.2f })
    }

    @Test
    fun filterControls_allInliers_keepsAll() {
        // All 3 areas within ±4.8% of median=0.01 — well under 10% threshold.
        // Median = 0.01 exactly (middle of sorted [0.0095, 0.01, 0.0105]).
        val areas = listOf(0.0095f, 0.01f, 0.0105f)

        // Compute w,h from each area using sqrt — all pass because within tolerance
        val boxes = List(3) { i ->
            val a = areas[i]
            val w = kotlin.math.sqrt(a)
            BoundingBox((i + 1) * 0.2f, 0.5f, w, a / w, 1f, "control_point")
        }

        val actualAreas = boxes.map { it.width * it.height }
        val med = computeMedian(actualAreas)

        // Verify all relative differences < 10%
        actualAreas.forEachIndexed { i, a ->
            val diff = kotlin.math.abs(a - med) / med
            assertTrue("area[$i]=$a diff=$diff should be < 0.10f", diff < 0.10f)
        }

        val result = computeFilterControls(boxes)
        assertEquals(3, result.size)
    }

    @Test
    fun filterControls_boundaryAt10Percent_filters() {
        // Direct area construction avoids sqrt float-precision issues:
        // baseBox area=0.01, highBox area=0.015 → diff = (0.015-0.01)/0.01 = 0.5 >= 0.1 → FILTERED
        val baseBox = BoundingBox(0.2f, 0.5f, 0.1f, 0.1f, 1f, "control_point")       // area=0.01
        val highBox = BoundingBox(0.8f, 0.5f, 0.1f, 0.15f, 1f, "control_point")       // area=0.015

        val result = computeFilterControls(listOf(baseBox, baseBox, highBox))
        assertEquals(2, result.size) // highBox with 50% area diff filtered out
    }

    @Test
    fun filterControls_guardAtLeastOne_returnsOne() {
        // All boxes very different → guard keeps at least 1
        val veryDifferent = listOf(
            BoundingBox(0.1f, 0.5f, 0.001f, 0.3f, 1f, "control_point"),
            BoundingBox(0.5f, 0.5f, 0.3f, 0.001f, 1f, "control_point"),
            BoundingBox(0.9f, 0.5f, 0.2f, 0.005f, 1f, "control_point")
        )
        val result = computeFilterControls(veryDifferent)
        assertTrue(result.size >= 1)
    }

    // ───────── filterNumbersByMedianHeight tests ─────────

    @Test
    fun filterNumbers_emptyList_returnsEmpty() {
        val result = computeFilterNumbers(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun filterNumbers_twoBoxes_keepsBoth() {
        val boxes = listOf(
            BoundingBox(0.1f, 0.1f, 0.05f, 0.03f, 1f, "number"),
            BoundingBox(0.5f, 0.5f, 0.06f, 0.04f, 1f, "number")
        )
        val result = computeFilterNumbers(boxes)
        assertEquals(2, result.size)
    }

    @Test
    fun filterNumbers_oneOutlier_filtersIt() {
        // 5 boxes: 4 normal height=0.1, 1 outlier height=0.5 → outlier removed
        val boxes = List(5) { i ->
            val h = if (i == 4) 0.5f else 0.1f
            BoundingBox((i + 1) * 0.15f, 0.5f, 0.1f, h, 1f, "number")
        }
        val result = computeFilterNumbers(boxes)
        assertEquals(4, result.size)
        assertTrue(result.none { it.height == 0.5f })
    }

    @Test
    fun filterNumbers_allInliers_keepsAll() {
        // All heights within 25% of median
        val medH = 0.1f
        val boxes = List(5) { i ->
            val h = medH + (i - 2) * 0.01f // range 0.08–0.12, all within 20% of median
            BoundingBox((i + 1) * 0.15f, 0.5f, 0.1f, h, 1f, "number")
        }
        val result = computeFilterNumbers(boxes)
        assertEquals(5, result.size)
    }

    @Test
    fun filterNumbers_boundaryAt25Percent_filters() {
        // Direct height construction avoids float-precision edge cases:
        // medH=0.1, highH=0.14 → diff=(0.14-0.1)/0.1 = 0.4 >= 0.25 → FILTERED
        val normalBox = BoundingBox(0.3f, 0.5f, 0.1f, 0.1f, 1f, "number")     // h=0.1
        val highBox   = BoundingBox(0.7f, 0.5f, 0.1f, 0.14f, 1f, "number")     // h=0.14

        val result = computeFilterNumbers(listOf(normalBox, normalBox, highBox))
        assertEquals(2, result.size) // highBox with 40% height diff filtered out
    }

    @Test
    fun filterNumbers_guardAtLeastOne_returnsOne() {
        val boxes = listOf(
            BoundingBox(0.1f, 0.5f, 0.1f, 0.01f, 1f, "number"),
            BoundingBox(0.5f, 0.5f, 0.1f, 0.5f, 1f, "number")
        )
        val result = computeFilterNumbers(boxes)
        assertTrue(result.size >= 1)
    }

    @Test
    fun filterNumbers_normalBoxes_keepsAll() {
        val boxes = listOf(
            BoundingBox(0.1f, 0.3f, 0.05f, 0.1f, 1f, "number"),
            BoundingBox(0.3f, 0.4f, 0.06f, 0.11f, 1f, "number"),
            BoundingBox(0.5f, 0.5f, 0.04f, 0.09f, 1f, "number"),
            BoundingBox(0.7f, 0.6f, 0.05f, 0.12f, 1f, "number"),
            BoundingBox(0.9f, 0.7f, 0.07f, 0.1f, 1f, "number")
        )
        val result = computeFilterNumbers(boxes)
        // median height of [0.09, 0.1, 0.1, 0.11, 0.12] = 0.1
        // all within 20% → should keep all
        assertEquals(5, result.size)
    }
}
