package ru.bondarenko.orientvibe.ng.auto

/** Собыие пересечения радиуса — срабатывает ОДИН РАЗ в момент входа в зону */
data class ApproachEvent(
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
    private val _list = mutableListOf<ApproachEvent>()

    /** События текущей кадра (может быть несколько при пересечении нескольких порогов) */
    fun items(): List<ApproachEvent> = _list

    fun add(event: ApproachEvent) { _list.add(event) }
    fun clear() { _list.clear() }
}
