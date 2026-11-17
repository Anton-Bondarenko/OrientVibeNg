package ru.bondarenko.orientvibe.ng.auto

import kotlin.math.min

import ru.bondarenko.orientvibe.ng.audio.ApproachState
import ru.bondarenko.orientvibe.ng.model.NavigationData

/** Константы, общие с AutoMoveManager (shouldSwitch) */
const val AUTO_APPROACHED_DIST = 80.0
const val LEAVING_CP_MIN_GAP = 30.0

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
        ON_100_RADIUS,
        /** Выход за DIST_GAP после подхода к КП — срабатывает ОДИН РАЗ */
        ON_LEAVING_CP,
        /** Движение каждый кадр — всегда, без условий */
        ON_MOVE;
    }

    /** Название события для логгирования */
    val name: String
        get() = when (type) {
            EventType.ON_MOVE_TO_CP -> "ON_MOVE_TO_CP"
            EventType.ON_SEARCH_RADIUS -> "ON_SEARCH_RADIUS"
            EventType.ON_APPROACH_RADIUS -> "ON_APPROACH_RADIUS"
            EventType.ON_100_RADIUS -> "ON_100_RADIUS"
            EventType.ON_LEAVING_CP -> "ON_LEAVING_CP"
            EventType.ON_MOVE -> "ON_MOVE"
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

    /** Константы, общие с AutoMoveManager */
    const val AUTO_APPROACHED_DIST = 80.0
    const val LEAVING_CP_MIN_GAP = 30.0

    fun evaluate(
        controlNum: Int,
        navData: ru.bondarenko.orientvibe.ng.model.NavigationData,
        confidenceProduct: Double,
        state: ApproachState,
        autoApproachedDist: Double = AUTO_APPROACHED_DIST,
        distGap: Double = LEAVING_CP_MIN_GAP
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

        // ON_MOVE — каждый кадр без условий
        state.events.add(FrameEvent(FrameEvent.EventType.ON_MOVE, controlNum, dist.toFloat(), devDeg))

        state.wasInSearchRadius = inSearchNow
        state.wasInApproachDouble = inApproachNow
        state.wasInApproachSingle = in100Now

// В FrameEventGenerator.evaluate:
// ── Detect ON_LEAVING_CP (точный аналог shouldSwitch) ───────────────────
        if (dist < autoApproachedDist) {
            state.wasApproachingCP = true
        }

        if (state.wasApproachingCP) {
            state.approachMinDist = min(state.approachMinDist, dist)
            if ((dist - state.approachMinDist) > distGap && !state.wasLeavingCP) {
                state.events.add(
                    FrameEvent(FrameEvent.EventType.ON_LEAVING_CP, controlNum, dist.toFloat(), devDeg)
                )
                state.wasLeavingCP = true
            }
        }
        return state.events.items()
    }

    private fun scaleFactorFor(confidenceProduct: Double): Double {
        return if (confidenceProduct > 0.0) 1.0 / confidenceProduct else Double.MAX_VALUE
    }
}
