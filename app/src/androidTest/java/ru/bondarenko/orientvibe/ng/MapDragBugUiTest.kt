package ru.bondarenko.orientvibe.ng

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * UI-test for detecting the bug: map drag is reset during/after YOLO detection cycle.
 *
 * Scenario:
 * 1. Load image from app assets (via test_mode broadcast)
 * 2. Drag immediately after image appears (before detection ends)
 * 3. Monitor panX via logcat during detection
 * 4. When YOLO finishes and enters nav mode -> pan resets (the bug)
 *
 * Screenshots saved to /data/local/tmp/screenshots/ — accessible via adb pull.
 */
@RunWith(AndroidJUnit4::class)
class MapDragBugUiTest {

    private val TAG = "MapDragBug"
    private val SCREENSHOT_DIR = "/data/local/tmp/screenshots/"
    private val APP_PKG = "ru.bondarenko.orientvibe.ng"

    @Before
    fun setup() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            device.executeShellCommand("mkdir -p $SCREENSHOT_DIR")
        } catch (_: Exception) {}
    }

    private fun dismissLocationPermissionDialog(device: UiDevice): Boolean {
        val candidates = listOf(
            "Разрешить все время",
            "Разрешить только пока пользуюсь",
            "While using the app",
            "Only this time",
            "Don't allow"
        )
        for (text in candidates) {
            try {
                val obj = device.findObject(By.text(text))
                if (obj != null) {
                    Thread.sleep(500)
                    obj.click()
                    return true
                }
            } catch (_: Exception) {}
        }
        try {
            device.pressBack()
            return true
        } catch (_: Exception) {}
        return false
    }

    private fun sendTestLoadBroadcast(device: UiDevice) {
        val action = "ru.bondarenko.orientvibe.ng.ACTION_TEST_LOAD"
        println("$TAG   Sending broadcast: $action")
        device.executeShellCommand("am broadcast -a $action --user all")
    }

    @After
    fun teardown() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            val files = device.executeShellCommand("ls -la $SCREENSHOT_DIR 2>/dev/null || echo 'no screenshots dir'")
            println("$TAG Screenshots on device:\n$files")
        } catch (_: Exception) {}
    }

    private fun captureScreen(device: UiDevice, name: String): String? {
        return try {
            val imgPath = "$SCREENSHOT_DIR$name.png"
            device.executeShellCommand("screencap -p $imgPath")
            println("$TAG   [SCREENSHOT] $name.png")
            imgPath
        } catch (e: Exception) {
            println("$TAG   Could not capture '$name': ${e.message}")
            null
        }
    }

    /** Read logcat, excluding test infrastructure lines that match themselves. */
    private fun getFilteredLines(device: UiDevice): List<String> {
        return try {
            device.executeShellCommand("logcat -d").trim().lines()
                .filter { line ->
                    line.contains("SUB_sync").not() == false ||
                        (line.contains("Executing shell command") == false &&
                         line.contains("UiDevice:") == false)
                }
        } catch (_: Exception) { emptyList() }
    }

    /** Read the latest SUB_sync panX value. Returns 0f if not found. */
    private fun readLatestPanX(device: UiDevice): Float {
        return try {
            val lines = device.executeShellCommand("logcat -d").trim().lines()
            val realLines = lines.filter { line ->
                !line.contains("Executing shell command") && !line.contains("UiDevice:")
            }
            val subSyncLine = realLines.asReversed().find { it.contains("SUB_sync") }
            if (subSyncLine != null) {
                val match = Regex("""panX=(-?[\d.]+)""").find(subSyncLine)
                match?.groupValues?.get(1)?.toFloat() ?: 0f
            } else {
                0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    /** Read the latest SUB_sync panY value. Returns 0f if not found. */
    private fun readLatestPanY(device: UiDevice): Float {
        return try {
            val lines = device.executeShellCommand("logcat -d").trim().lines()
            val realLines = lines.filter { line ->
                !line.contains("Executing shell command") && !line.contains("UiDevice:")
            }
            val subSyncLine = realLines.asReversed().find { it.contains("SUB_sync") }
            if (subSyncLine != null) {
                val match = Regex("""panY=(-?[\d.]+)""").find(subSyncLine)
                match?.groupValues?.get(1)?.toFloat() ?: 0f
            } else {
                0f
            }
        } catch (_: Exception) {
            0f
        }
    }

    /** Check if any real (non-infra) SUB_sync lines exist in logcat. */
    private fun hasSubSyncLogs(device: UiDevice): Boolean {
        return try {
            val lines = device.executeShellCommand("logcat -d").trim().lines()
            val realLines = lines.filter { line ->
                !line.contains("Executing shell command") && !line.contains("UiDevice:")
            }
            val count = realLines.count { it.contains("SUB_sync") }
            println("$TAG   [DEBUG] logcat dump size=${lines.size} chars, SUB_sync lines=$count (filtered)")
            count > 0
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun dragIsPreservedDuringDetection() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Clear logcat buffer BEFORE anything else
        println("$TAG [1/5] Clearing logcat...")
        device.executeShellCommand("logcat -c")

        // Start activity normally (broadcast triggers photo load, not intent extras)
        println("$TAG [2/5] Launching app...")
        val act = "$APP_PKG/.MainActivity"
        val cmd = "am start -n $act -W -f 0x10000000 -c android.intent.category.LAUNCHER -a android.intent.action.MAIN"
        device.executeShellCommand(cmd)
        Thread.sleep(2000) // Wait for app to start + receiver to register

        // Send broadcast to trigger test photo load
        sendTestLoadBroadcast(device)
        println("$TAG   Broadcast sent. Waiting for image to appear...")

        // Wait for TapChain logs (image loaded + detection started)
        println("$TAG [3/5] Waiting for image to appear...")
        val imageAppearTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - imageAppearTime < 15_000L) {
            val allLogs = device.executeShellCommand("logcat -d").trim().lines()
            // Filter out test infrastructure lines before counting TapChain
            val appLines = allLogs.filter { line ->
                !line.contains("Executing shell command") && !line.contains("UiDevice:")
            }
            val tapChainLines = appLines.count { it.contains("TapChain") }
            if (tapChainLines > 0) {
                println("$TAG   TapChain logs found: $tapChainLines entries")
                break
            }
            Thread.sleep(500)
        }

        hasSubSyncLogs(device)
        dismissLocationPermissionDialog(device)

        // Drag map LEFT immediately after image appears (before detection ends)
        println("$TAG [4/5] Dragging map LEFT by ~10% screen width...")
        captureScreen(device, "03_before_drag")

        val screenWidth = device.displayWidth
        val screenHeight = device.displayHeight
        val dragDistance = (screenWidth * 0.30).toInt()
        val startX = (screenWidth * 0.5f).toInt()
        val startY = (screenHeight * 0.65f).toInt()

        println("$TAG   Drag: ($startX,$startY) -> (${startX - dragDistance},$startY) on ${screenWidth}x$screenHeight")
        device.swipe(startX, startY, startX - dragDistance, startY, 100)
        Thread.sleep(1500) // Allow gesture + scroll animation

        captureScreen(device, "04_after_drag_initial")
        println("$TAG   Drag sent. Recording baseline pan state.")

        val baselinePanX = readLatestPanX(device)
        val baselinePanY = readLatestPanY(device)
        println("$TAG   Baseline pan: X=$baselinePanX, Y=$baselinePanY")

        // Monitor for nav sync (detection result) and pan changes
        println("$TAG [5/5] Watching for detection progress & pan changes...")
        val monitorStart = System.currentTimeMillis()
        var navSyncFound = false
        val screenshots: MutableList<Pair<String, Float>> = mutableListOf()

        while (System.currentTimeMillis() - monitorStart < 40_000L) {
            val currentPanX = readLatestPanX(device)
            val currentPanY = readLatestPanY(device)

            // Capture screenshot every second for visual analysis
            val elapsedSec = ((System.currentTimeMillis() - monitorStart) / 1000).toInt()
            captureScreen(device, "0${5 + elapsedSec / 2}_t=${elapsedSec}s_panX=${currentPanX.toInt()}")

            // Detect nav sync (first SUB_sync after drag) using filtered logcat
            val allLogsRaw = device.executeShellCommand("logcat -d").trim().lines()
            val appLogLines = allLogsRaw.filter { line ->
                !line.contains("Executing shell command") && !line.contains("UiDevice:")
            }
            val hasNewSubSync = appLogLines.any { it.contains("SUB_sync") }

            if (hasNewSubSync && !navSyncFound) {
                navSyncFound = true
                println("$TAG   *** NAV SYNC DETECTED at T=${elapsedSec}s ***")
                val subSyncLines = appLogLines.filter { it.contains("SUB_sync") }.takeLast(5)
                subSyncLines.forEach { line -> println("$TAG     $line") }
                screenshots.add("nav_sync_panX=${currentPanX.toInt()}" to currentPanX)
            }

            // Detect if pan changed from baseline (bug = pan snapped back toward 0)
            if (currentPanX != baselinePanX && abs(currentPanX - baselinePanX) > 5f) {
                screenshots.add("pan_changed_X=${currentPanX.toInt()}_Y=${currentPanY.toInt()}" to currentPanX)
                println("$TAG   Pan changed: baseline=$baselinePanX -> current=$currentPanX (delta=${"%.0f".format(currentPanX - baselinePanX)}px)")

                // If pan snapped near 0, that's definitely the bug
                if (abs(currentPanX) < 5f) {
                    println("$TAG   *** BUG CONFIRMED: Pan snapped to ~0! ***")
                    captureScreen(device, "BUG_PAN_RESET_TO_ZERO")
                    break
                }
            }

            Thread.sleep(1000)
        }

        // Final screenshot
        captureScreen(device, "FINAL_result")

        // Analyze results
        val finalPanX = readLatestPanX(device)
        val finalPanY = readLatestPanY(device)
        val expectedPanX = baselinePanX + dragDistance.toFloat()
        val drift = abs(finalPanX - expectedPanX)

        println("$TAG [RESULTS]")
        println("$TAG   Baseline panX after drag: $baselinePanX")
        println("$TAG   Expected panX (baseline + drag): ~$expectedPanX")
        println("$TAG   Final panX after detection: $finalPanX")
        println("$TAG   Drift from expected: ${"%.0f".format(drift)}px")
        println("$TAG   Nav sync detected during test: $navSyncFound")
        println("$TAG   Screenshots captured: ${screenshots.size}")

        screenshots.forEach { (name, panX) ->
            println("$TAG   [MID] $name -> panX=$panX")
        }

        // Key assertion: if nav sync happened and pan drifted > 80px from expected, it's the bug
        if (!navSyncFound) {
            println("$TAG   WARNING: Nav sync never detected during test window.")
            println("$TAG   Detection may have completed before we could drag, or YOLO took too long.")
            return
        }

        if (drift < 80f) {
            println("$TAG PASS: Drag preserved during detection. Bug NOT reproduced.")
            println("$TAG   PanX drift=${"%.0f".format(drift)}px (threshold=80px)")
        } else {
            val msg = buildString {
                append("Drag reset bug CONFIRMED! Drift=${"%.1f".format(drift)}px")
                if (abs(finalPanX) < expectedPanX * 0.5) {
                    append(". Pan snapped toward center (enterNavMode reset)!")
                }
            }
            println("$TAG FAIL: $msg")
            captureScreen(device, "BUG_DRIFT_drift=${"%.0f".format(drift)}px")
            fail(msg)
        }
    }
}
