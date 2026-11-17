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
}
