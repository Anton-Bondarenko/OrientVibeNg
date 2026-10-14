package ru.bondarenko.orientvibe.ng.model

import ru.bondarenko.orientvibe.ng.gps.MapCalibration

/** Тип аудиосигнала навигации */
enum class AudioSignalType {
    /** Нет сигнала */
    NONE,
    /** Дистанция < 40м — звуковая трель (один раз) */
    ARRIVED,
    /** Отклонение > 10° — сигнал в канал поворота, частота падает с углом */
    DEVIATION,
    /** Подход: один двойной сигнал (100м) или два двойных (50м) */
    APPROACH_SINGLE,
    /** Подход: два двойных сигнала (50м) */
    APPROACH_DOUBLE,
}

/** Состояние аудиосигнала навигации */
data class AudioSignalState(
    val type: AudioSignalType = AudioSignalType.NONE,
    /** Текущая частота (для UI отрисовки стрелки/индикатора) */
    val frequencyHz: Int = 0,
    /** Баланс лево-право: 0.0 = только правый, 0.5 = оба равны, 1.0 = только левый */
    val leftBalance: Float = 0.5f,
    /** Интервал повтора сигнала (0 если не периодический) */
    val intervalMs: Long = 0L,
    /** Угол отклонения в градусах */
    val deviationDeg: Double = 0.0,
    /** Дистанция до КП в метрах */
    val distanceToTargetMeters: Double = 0.0,
    /** Номер КП для отслеживания подхода (чтобы двойной сигнал играл только один раз на дистанции) */
    val controlNum: Int = 0,
    /** Сколько раз уже сыграли подходной сигнал на этой дистанции */
    val approachCount: Int = 0,
) {
    /** Является ли сигнал периодическим (нужен таймер повтора) */
    fun isPeriodic(): Boolean =
        type == AudioSignalType.DEVIATION
}

/** Направление отклонения от курса на КП */
enum class DeviationDirection { LEFT, CENTER, RIGHT }

/** Результаты анализа навигации к текущей КП */
data class NavigationData(
    val bearingToTargetDeg: Double,   // курс от текущей позиции до КП (0..360)
    val distanceToTargetMeters: Double, // дистанция (Haversine)
    val deviationDeg: Double,          // отклонение от курса на КП (−180…+180), >0 = вправо, <0 = влево
    val direction: DeviationDirection,
)

/** Дистанция до КП для сигнала "прибыл" (м) */
const val AUDIO_ARRIVED_DIST = 40f

/** Дистанция для первого двойного сигнала (м) */
const val APPROACH_SINGLE_DIST = 100f

/** Дистанция для двух двойных сигналов (м) */
const val APPROACH_DOUBLE_DIST = 50f

/** Сектор "на цель" отклонения (градусы) — используется для DEVIATION boundary */
const val ON_TARGET_DEG = 10.0

/** Интервал периодического сигнала при дистанции > 100м (мс) */
const val FAR_INTERVAL_MS = 30_000L

/** Интервал периодического сигнала при дистанции <= 100м (мс) */
const val CLOSE_INTERVAL_MS = 10_000L

/** Текущий статус движения*/
data class MovementState(
    var gpsFix: GpsFix? = null,
    var currentControl: CurrentControl = CurrentControl(1),
    var prevControl: CurrentControl = CurrentControl(0),
    var distanceToTarget: Double = 0.0,
    var newMapCalibration: MapCalibration? = null,
    /** Аудиосигнал навигации */
    var audioSignalState: AudioSignalState = AudioSignalState(),
    /** Последнее время воспроизведения сигнала (мс) */
    var _lastAudioPlayMs: Long = 0,
    /** Номер КП, для которого играли подходной сигнал (чтобы не играть повторно) */
    var _approachControlNum: Int = 0,
    /** Дистанция, на которую сыграли подходной сигнал (100 или 50) */
    var _approachDistKey: Int = 0,
) {
    /** Является ли сигнал периодическим (нужен таймер повтора) */
    fun isPeriodicSignal(): Boolean =
        audioSignalState.type == AudioSignalType.DEVIATION

    /** Проверка: прошло ли достаточно времени для следующего периодического сигнала */
    fun canPlayAudioSignal(): Boolean {
        val interval = audioSignalState.intervalMs
        return interval <= 0L ||
            System.currentTimeMillis() - _lastAudioPlayMs >= interval
    }
}

/** Проверка: переключился ли КП (для авто-свитч сигналов) */
fun MovementState.didSwitchControl(): Boolean {
    return currentControl.num != prevControl.num && prevControl.num > 0
}

/** Перейти к следующему КП в списке */
fun MovementState.nextControl(mapState: AutoMapState) {
    prevControl = currentControl
    val nextNum = currentControl.num + 1
    val nextBoundingBox = mapState.controlsBoundingBoxes.find { it.number == nextNum }
    currentControl = CurrentControl(num = nextNum, boundingBox = nextBoundingBox)
}

/** Установить заданный КП и предыдущий */
fun MovementState.setControl(num: Int, mapState: AutoMapState) {
    val nextBoundingBox = mapState.controlsBoundingBoxes.find { it.number == num }
    val prevBoundingBox = mapState.controlsBoundingBoxes.find { it.number == num - 1 }
    currentControl = CurrentControl(num = num, boundingBox = nextBoundingBox)
    prevControl = CurrentControl(num = num - 1, boundingBox = prevBoundingBox)
}