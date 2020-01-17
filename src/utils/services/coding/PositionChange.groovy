package utils.services.coding

/**
 * Created by nick on 12/10/2017.
 */
class PositionChange {
    final int lineChange
    final int columnChange

    PositionChange(int lineChange, int columnChange) {
        this.lineChange = lineChange
        this.columnChange = columnChange
    }

    boolean isEmpty() {
        lineChange == 0 && columnChange == 0
    }
}
