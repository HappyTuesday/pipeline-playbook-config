package utils.services.coding

import org.codehaus.groovy.ast.ASTNode

/**
 * Created by nick on 12/10/2017.
 */
class CodeBlock {
    CodePosition start
    CodePosition end

    static CodeBlock parse(ASTNode node) {
        new CodeBlock(
                start: new CodePosition(node.lineNumber, node.columnNumber),
                end: new CodePosition(node.lastLineNumber, node.lastColumnNumber)
        )
    }

    void move(int lineNumberChange, int columnNumberChange) {
        if (start.lineNumber != end.lineNumber) {
            columnNumberChange = 0
        }
        start = start.move(lineNumberChange, columnNumberChange)
        end = end.move(lineNumberChange, columnNumberChange)
    }

    void move(PositionChange change) {
        move(change.lineChange, change.columnChange)
    }

    String getCode(List<String> lines) {
        lines = lines[start.lineNumber-1 .. end.lineNumber-1]
        if (start.lineNumber == end.lineNumber) {
            lines[0] = lines[0][start.columnNumber-1 ..< end.columnNumber-1]
        } else {
            lines[0] = lines[0][start.columnNumber-1 .. -1]
            lines[-1] = lines[-1][0 ..< end.columnNumber-1]
        }
        lines.join('\n')
    }

    boolean contains(CodePosition pos) {
        (
                start.lineNumber <= pos.lineNumber && pos.lineNumber <= end.lineNumber
        ) && (
                start.lineNumber != pos.lineNumber || start.columnNumber <= pos.columnNumber
        ) && (
                end.lineNumber != pos.lineNumber || pos.columnNumber <= end.columnNumber
        )
    }

    @Override
    String toString() {
        "[$start, $end]"
    }
}
