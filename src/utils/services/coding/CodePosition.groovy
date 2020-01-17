package utils.services.coding

/**
 * Created by nick on 12/10/2017.
 */
class CodePosition implements Comparable<CodePosition> {
    final int lineNumber
    final int columnNumber

    CodePosition(int lineNumber, int columnNumber) {
        this.lineNumber = lineNumber
        this.columnNumber = columnNumber
    }

    CodePosition move(int lineNumberChange, int columnNumberChange) {
        new CodePosition(lineNumber + lineNumberChange, columnNumber + columnNumberChange)
    }

    CodePosition back(PositionChange change) {
        move(-change.lineChange, -change.columnChange)
    }

    @Override
    int compareTo(CodePosition o) {
        if (lineNumber == o.lineNumber) {
            columnNumber <=> o.columnNumber
        } else {
            lineNumber <=> o.lineNumber
        }
    }

    @Override
    String toString() {
        "($lineNumber, $columnNumber)"
    }
}