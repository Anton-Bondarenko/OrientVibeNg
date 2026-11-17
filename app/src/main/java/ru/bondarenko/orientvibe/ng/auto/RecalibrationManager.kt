package ru.bondarenko.orientvibe.ng.auto

import android.util.Log

/**
 * Менеджер калибровки — обрабатывает события ON_LEAVING_CP
 * и запускает логику автоматической калибровки при уходе от КП.
 */
object RecalibrationManager {

    private const val TAG = "RecalibrationManager"

    /**
     * Обработка событий кадра. Пока только лог ON_LEAVING_CP.
     */
    fun handleFrameEvents(events: List<FrameEvent>) {
        for (event in events) {
            when (event.type) {
                FrameEvent.EventType.ON_LEAVING_CP -> {
                    Log.d(TAG, "▶ LEAVING_CP: CP#${event.controlNum} dist=${"%.1f".format(event.distM)}m dev=${"%.1f".format(event.devDeg)}°")
                }

                else -> {}
            }
        }
    }
}
