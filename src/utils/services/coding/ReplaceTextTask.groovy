package utils.services.coding

/**
 * Created by nick on 12/10/2017.
 */
class ReplaceTextTask {
    CodeBlock block
    String text

    PositionChange execute(List<String> lines) {
        CodePosition originEnd = block.end

        List<String> linesToInsert = text.split(/\n/)

        String head = lines[block.start.lineNumber - 1].substring(0, block.start.columnNumber - 1)
        String tail = lines[block.end.lineNumber - 1].substring(block.end.columnNumber - 1)

        // delete old text
        while (block.start.lineNumber < block.end.lineNumber) {
            block.end = block.end.move(-1, 0)
            lines.remove(block.end.lineNumber)
        }
        lines[block.start.lineNumber - 1] = head + tail
        block.end = block.start

        // add new text
        if (linesToInsert.size() == 0) {
            //
        } else if (linesToInsert.size() == 1) {
            lines[block.start.lineNumber - 1] = head + linesToInsert[0] + tail
            block.end = block.end.move(0, linesToInsert[0].size())
        } else if (linesToInsert.size() >= 2) {
            lines[block.start.lineNumber - 1] = head + linesToInsert[0]
            lines.addAll(block.start.lineNumber, linesToInsert[1..-1])
            block.end = block.end.move(linesToInsert.size() - 1, 0)
            block.end = block.end.move(0, lines[block.end.lineNumber - 1].size() + 1 - block.end.columnNumber)
            lines[block.end.lineNumber - 1] += tail
        }

        return new PositionChange(block.end.lineNumber - originEnd.lineNumber, block.end.columnNumber - originEnd.columnNumber)
    }

    static void executeAll(List<String> lines, List<ReplaceTextTask> tasks) {
        tasks = tasks.toSorted { x, y ->
            x.block.start <=> y.block.start
        }
        for (int i = 0; i < tasks.size(); i++) {
            ReplaceTextTask task = tasks[i]
            // println(task)
            PositionChange change = task.execute(lines)
            if (change.empty) continue
            CodePosition taskOriginEnd = task.block.end.back(change)
            for (int j = i + 1; j < tasks.size(); j++) {
                ReplaceTextTask task2 = tasks[j]
                if (change.columnChange != 0 &&
                        (taskOriginEnd.lineNumber != task2.block.start.lineNumber ||
                                taskOriginEnd.columnNumber > task2.block.start.columnNumber)) {
                    // discard column change
                    change = new PositionChange(change.lineChange, 0)
                }

                task2.block.move(change)
            }
        }
    }

    @Override
    String toString() {
        "Replace Text $block <- $text"
    }
}
