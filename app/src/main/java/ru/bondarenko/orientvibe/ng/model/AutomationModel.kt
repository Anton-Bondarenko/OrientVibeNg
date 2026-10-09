package ru.bondarenko.orientvibe.ng.model


/** Дистанция КП взят*/
const val AUTO_MIN_DIST = 10
const val AUTO_APPROACH_DIST = 50

/** Текущий статус движения*/
data class MovementState(
    var gpsFix: GpsFix? = null,
    var currentControl: CurrentControl = CurrentControl(1),
    var prevControl: CurrentControl = CurrentControl(0),
    var distanceToTarget: Float = 0f,
) {
    /**
     * Сюда передаём следующий КП
     */
    fun nextControl(mapState: AutoMapState) {
        prevControl = currentControl
        val nextNum = currentControl.num + 1
        val nextBoundingBox = mapState.controlsBoundingBoxes.find { it.number == nextNum }
        val newCurrentControl = CurrentControl(
            num = nextNum,
            boundingBox = nextBoundingBox
        )
        currentControl = newCurrentControl;
    }

    fun setControl(num: Int, mapState: AutoMapState) {
        val nextBoundingBox = mapState.controlsBoundingBoxes.find { it.number == num }
        val prevBoundingBox = mapState.controlsBoundingBoxes.find { it.number == num - 1 }
        val newCurrentControl = CurrentControl(
            num = num,
            boundingBox = nextBoundingBox
        )
        prevControl = CurrentControl(
            num = num - 1,
            boundingBox = prevBoundingBox
        )
        currentControl = newCurrentControl;
    }
}