package ru.bondarenko.orientvibe.ng.model

import ru.bondarenko.orientvibe.ng.audio.AudioSignalType

/**
 * Направление отклонения пользователя от целевого маршрута.
 */
enum class DeviationDirection {
    LEFT,
    CENTER,
    RIGHT
}

/**
 * Модель состояния текущего аудиосигнала.
 */
data class AudioSignalState(
    val type: AudioSignalType = AudioSignalType.NONE,
    val frequencyHz: Int = 0,
    val leftBalance: Float = 0.5f,
    val intervalMs: Long = 0L,
    val deviationDeg: Double = 0.0,
    val distanceToTargetMeters: Double = 0.0,
    val controlNum: Int = 0,
    val approachCount: Int = 0
) {
    val isPeriodic: Boolean
        get() = type == AudioSignalType.DEVIATION
}

/**
 * Математические данные текущего навигационного шага.
 */
data class NavigationData(
    val bearingToTargetDeg: Double,
    val distanceToTargetMeters: Double,
    val deviationDeg: Double,
    val direction: DeviationDirection
)

/**
 * Основная модель состояния движения пользователя по контрольным точкам.
 */
data class MovementState(
    var gpsFix: GpsFix? = null,
    var currentControl: CurrentControl = CurrentControl(1, null),
    var prevControl: CurrentControl = CurrentControl(0, null),
    var distanceToTarget: Double = 0.0,
    var newMapCalibration: MapCalibration? = null,
    var autoCalibrations: MutableList<MapCalibration?> = ArrayList(),
    var audioSignalState: AudioSignalState = AudioSignalState(),
    var lastAudioPlayMs: Long = 0L,
    var approachControlNum: Int = 0,
    var approachDistKey: Int = 0
) {
    val isPeriodicSignal: Boolean
        get() = audioSignalState.type == AudioSignalType.DEVIATION

    /**
     * Проверка возможности воспроизведения очередного навигационного аудио-сигнала по таймауту.
     */
    fun canPlayAudioSignal(): Boolean {
        val interval = audioSignalState.intervalMs
        return interval <= 0L || (System.currentTimeMillis() - lastAudioPlayMs) >= interval
    }

    /**
     * Флаг, определяющий, произошло ли переключение на следующий контрольный пункт.
     */
    fun didSwitchControl(): Boolean {
        return currentControl.num != prevControl.num && prevControl.num > 0
    }

    /**
     * Сдвиг индексации навигации на следующее BoundingBox препятствие.
     */
    fun nextControl(mapState: AutoMapState) {
        prevControl = currentControl
        val nextNum = currentControl.num + 1

        val nextBoundingBox = mapState.controlsBoundingBoxes
            .firstOrNull { (it as BoundingBox).number == nextNum } as? BoundingBox

        currentControl = CurrentControl(nextNum, nextBoundingBox)
    }

    /**
     * Установка конкретного контрольного пункта с автоматическим вычислением предыдущего.
     */
    fun setControl(num: Int, mapState: AutoMapState) {
        val nextBoundingBox = mapState.controlsBoundingBoxes
            .firstOrNull { (it as BoundingBox).number == num } as? BoundingBox

        val prevBoundingBox = mapState.controlsBoundingBoxes
            .firstOrNull { (it as BoundingBox).number == (num - 1) } as? BoundingBox

        currentControl = CurrentControl(num, nextBoundingBox)
        prevControl = CurrentControl(num - 1, prevBoundingBox)
    }

    companion object {
        // Константы, перенесённые из глобального файла утилит AutomationModelKt
        const val APPROACH_DOUBLE_DIST = 50.0f
        const val APPROACH_SINGLE_DIST = 100.0f
        const val AUDIO_ARRIVED_DIST = 40.0f
        const val CLOSE_INTERVAL_MS = 10000L
        const val FAR_INTERVAL_MS = 30000L
        const val ON_TARGET_DEG = 10.0
    }
}
