package ru.bondarenko.orientvibe.ng.auto

import ru.bondarenko.orientvibe.ng.audio.ApproachState
import ru.bondarenko.orientvibe.ng.model.NavigationData

/** Собыие пересечения радиуса — срабатывает ОДИН РАЗ в момент входа в зону */
data class FrameEvent(
    /** Тип зоны */
    val type: EventType,
    /** Номер точки калибровки на момент входа */
    val controlNum: Int,
    /** Дистанция в момент пересечения */
    val distM: Float,
    /** Отклонение в градусах */
    val devDeg: Double
) {
    enum class EventType {
        /** Движение вне всех радиусов — срабатывает каждый кадр while outside all radii */
        ON_MOVE_TO_CP,
        /** Пересечение радиуса поиска (40 м для confidence=1.0) */
        ON_SEARCH_RADIUS,
        /** Пересечение радиуса двойного писка (50 м) */
        ON_APPROACH_RADIUS,
        /** Пересечение радиуса одиночного писка (100 м) */
        ON_100_RADIUS;

        /** Дистанция по умолчанию для confidence=1.0 */
        val baseRadiusM: Float
            get() = when (this) {
                ON_MOVE_TO_CP -> 0f      // не радиус, а состояние "вне всех"
                ON_SEARCH_RADIUS -> 40f
                ON_APPROACH_RADIUS -> 50f
                ON_100_RADIUS -> 100f
            }
    }

    /** Название события для логгирования */
    val name: String
        get() = when (type) {
            EventType.ON_MOVE_TO_CP -> "ON_MOVE_TO_CP"
            EventType.ON_SEARCH_RADIUS -> "ON_SEARCH_RADIUS"
            EventType.ON_APPROACH_RADIUS -> "ON_APPROACH_RADIUS"
            EventType.ON_100_RADIUS -> "ON_100_RADIUS"
        }
}

/** Контейнер для событий текущего кадра — заполняется playSignal, читается caller */
class ApproachEvents {
    private val _list = mutableListOf<FrameEvent>()

    /** События текущей кадра (может быть несколько при пересечении нескольких порогов) */
    fun items(): List<FrameEvent> = _list

    fun add(event: FrameEvent) { _list.add(event) }
    fun clear() { _list.clear() }
}

/**
 * Статический генератор Frame-событий из дистанции и состояния.
 */
object FrameEventGenerator {

    // Пороги для confidence = 1.0
    private const val BASE_SEARCH_RADIUS_M = 40.0
    private const val BASE_APPROACH_DOUBLE_M = 50.0
    private const val BASE_APPROACH_SINGLE_M = 100.0

    fun evaluate(
        controlNum: Int,
        navData: ru.bondarenko.orientvibe.ng.model.NavigationData,
        confidenceProduct: Double,
        state: ApproachState
    ): List<FrameEvent> {
        if (state.controlNum != controlNum) {
            state.resetForControl(controlNum)
        } else {
            state.events.clear()
        }

        val scale = scaleFactorFor(confidenceProduct)
        val searchRadius = BASE_SEARCH_RADIUS_M * scale
        val approachDoubleDist = BASE_APPROACH_DOUBLE_M * scale
        val approachSingleDist = BASE_APPROACH_SINGLE_M * scale

        val dist = navData.distanceToTargetMeters
        val devDeg = navData.deviationDeg

        val inSearchNow = dist < searchRadius
        val inApproachNow = dist < approachDoubleDist
        val in100Now = dist < approachSingleDist

        if (inSearchNow && !state.wasInSearchRadius) {
            state.events.add(FrameEvent(FrameEvent.EventType.ON_SEARCH_RADIUS, controlNum, dist.toFloat(), devDeg))
        }
        if (inApproachNow && !state.wasInApproachDouble) {
            state.events.add(FrameEvent(FrameEvent.EventType.ON_APPROACH_RADIUS, controlNum, dist.toFloat(), devDeg))
        }
        if (in100Now && !state.wasInApproachSingle) {
            state.events.add(FrameEvent(FrameEvent.EventType.ON_100_RADIUS, controlNum, dist.toFloat(), devDeg))
        }

        if (!inSearchNow) {
            state.events.add(FrameEvent(FrameEvent.EventType.ON_MOVE_TO_CP, controlNum, dist.toFloat(), devDeg))
        }

        state.wasInSearchRadius = inSearchNow
        state.wasInApproachDouble = inApproachNow
        state.wasInApproachSingle = in100Now

        return state.events.items()
    }

    private fun scaleFactorFor(confidenceProduct: Double): Double {
        return if (confidenceProduct > 0.0) 1.0 / confidenceProduct else Double.MAX_VALUE
    }
}

class ApproachEventDetector {
    private var lastControlNum: Int = -1
    private var wasInSearchRadius: Boolean = false
    private var wasInApproachRadius: Boolean = false
    private var wasIn100Radius: Boolean = false

    fun evaluate(
        controlNum: Int,
        distM: Float,
        devDeg: Double,
        confidenceProduct: Double
    ): List<FrameEvent> {
        // 1. При смене КП сбрасываем историю пересечений
        if (controlNum != lastControlNum) {
            lastControlNum = controlNum
            wasInSearchRadius = false
            wasInApproachRadius = false
            wasIn100Radius = false
        }

        val scale = if (confidenceProduct > 0.0) (1.0 / confidenceProduct).toFloat() else Float.MAX_VALUE
        val searchRadius = FrameEvent.EventType.ON_SEARCH_RADIUS.baseRadiusM * scale
        val approachRadius = FrameEvent.EventType.ON_APPROACH_RADIUS.baseRadiusM * scale
        val radius100 = FrameEvent.EventType.ON_100_RADIUS.baseRadiusM * scale

        val inSearchNow = distM < searchRadius
        val inApproachNow = distM < approachRadius
        val in100Now = distM < radius100

        val frameEvents = mutableListOf<FrameEvent>()

        // 2. Одноразовые события пересечения радиусов (срабатывают только в момент входа)
        if (inSearchNow && !wasInSearchRadius) {
            frameEvents.add(FrameEvent(FrameEvent.EventType.ON_SEARCH_RADIUS, controlNum, distM, devDeg))
        }
        if (inApproachNow && !wasInApproachRadius) {
            frameEvents.add(FrameEvent(FrameEvent.EventType.ON_APPROACH_RADIUS, controlNum, distM, devDeg))
        }
        if (in100Now && !wasIn100Radius) {
            frameEvents.add(FrameEvent(FrameEvent.EventType.ON_100_RADIUS, controlNum, distM, devDeg))
        }

        // 3. Непрерывное событие движения (каждый фрейм, пока не вошли в радиус поиска)
        if (!inSearchNow) {
            frameEvents.add(FrameEvent(FrameEvent.EventType.ON_MOVE_TO_CP, controlNum, distM, devDeg))
        }

        // 4. Фиксируем текущее состояние для следующего фрейма
        wasInSearchRadius = inSearchNow
        wasInApproachRadius = inApproachNow
        wasIn100Radius = in100Now

        return frameEvents
    }
}
