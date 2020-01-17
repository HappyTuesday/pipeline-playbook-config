package utils.services

import com.yit.deploy.core.utils.Utils
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.syntax.Types
import utils.services.coding.CodeBlock
import utils.services.coding.CodePosition
import utils.services.coding.PropertyAssignBlock
import utils.services.coding.ReplaceTextTask

/**
 * Created by nick on 08/09/2017.
 */
class AutoPrettifyCodeService {
    List<List<String>> predefinedVariableOrder = [
            "HOST,PORT,USERNAME,PASSWORD,ROOT,URL,READONLY",
            "USER,PASSWORD",
            "USER,PWD"
    ].collect {it.tokenize(',')}

    String environmentScriptFolder = Utils.rootPath + "/src/envs"

    static void main(String[] args) {
        def service = new AutoPrettifyCodeService()
        service.convertAllFiles()
    }

    void convertAllFiles() {
        for (String folder in [environmentScriptFolder]) {
            new File(environmentScriptFolder).traverse { file ->
                if (file.file) {
                    convertOneFile(file)
                }
            }
        }
    }

    private void convertOneFile(File file) {
        if (file.name.startsWith(".")) return

        List<String> lines = file.readLines()
        lines = convertEnvironmentScript(lines, file.name)
        String newContent = lines.join('\n')
        if (file.text != newContent) {
            System.err.println("[AutoPrettifyCodeService] convert file $file.path")
            file.text = newContent
        }
    }

    private List<String> convertEnvironmentScript(List<String> lines, String filename) {
        List<List<PropertyAssignBlock>> allBlocks = []
        parseEnvScript(lines.join('\n'), filename, allBlocks)
        if (allBlocks.empty) {
            return lines
        }
        List<ReplaceTextTask> tasks = []
        for (List<PropertyAssignBlock> blocks in allBlocks) {
            includingCommentsToBlocks(blocks, lines)

            List<PropertyAssignCodeUnit> units = blocks.collect {
                new PropertyAssignCodeUnit(property: it.property, code: it.block.getCode(lines))
            }
            units.sort { x, y ->
                comparePropertyName(x.property, y.property)
            }
            insertWhiteLinesBeforeSection(blocks, units, lines)

            for (int i = 0; i < blocks.size(); i++) {
                tasks << new ReplaceTextTask(block: blocks[i].block, text: units[i].code)
            }
        }

        ReplaceTextTask.executeAll(lines, tasks)
        return lines
    }

    private int comparePropertyName(String property1, String property2) {
        List<String> p1 = property1.tokenize('_'), p2 = property2.tokenize('_')
        for (int i = 0; true; i++) {
            if (i < p1.size() && i < p2.size()) {
                String x = p1[i], y = p2[i]
                if (x != y) {
                    for (List<String> order in predefinedVariableOrder) {
                        int xi = order.indexOf(x)
                        if (xi >= 0) {
                            int yi = order.indexOf(y)
                            if (yi >= 0) {
                                return xi <=> yi
                            }
                        }
                    }
                    return x <=> y
                } else {
                    // compare next segment of version
                }
            } else if (i < p1.size()) {
                if (i > 1 && p2[i-1] == "DB") {
                    return -1
                }
                return 1
            } else if (i < p2.size()) {
                if (i > 1 && p1[i-1] == "DB") {
                    return 1
                }
                return -1
            } else {
                return 0
            }
        }
        return 0
    }

    private static void includingCommentsToBlocks(List<PropertyAssignBlock> blocks, List<String> lines) {
        for (PropertyAssignBlock b in blocks) {
            if (lines[b.block.start.lineNumber-1][0 ..< b.block.start.columnNumber-1].allWhitespace) {
                boolean inCommentBlock
                int commentLineIndex = -1
                for (int i = b.block.start.lineNumber - 2; i >= 0; i--) {
                    String line = lines[i]
                    if (line.allWhitespace) {
                        continue
                    }
                    if (line =~ /^[\s\t]*\/\//) {
                        commentLineIndex = i
                        continue
                    }
                    if (line =~ /\*\/[\s\t]*$/) {
                        if (line =~ /^[\s\t]*\/\*/) {
                            commentLineIndex = i
                            continue
                        }
                        inCommentBlock = true
                        continue
                    }
                    if (line =~ /^[\s\t]*\/\*/) {
                        inCommentBlock = false
                        commentLineIndex = i
                        continue
                    }
                    if (inCommentBlock) {
                        continue
                    }
                    break
                }
                if (commentLineIndex >= 0) {
                    int commentColumnIndex = lines[commentLineIndex].findIndexOf { !it.allWhitespace}
                    if (commentColumnIndex >= 0) {
                        CodePosition newStart = new CodePosition(commentLineIndex + 1, commentColumnIndex + 1)
                        if (!blocks.any {it.block.contains(newStart)}) {
                            b.block.start = newStart
                        }
                    }
                }
            }
        }
    }

    private static void insertWhiteLinesBeforeSection(List<PropertyAssignBlock> blocks, List<PropertyAssignCodeUnit> units, List<String> lines) {
        String lastSectionName = null
        for (int i = 0; i < units.size(); i++) {
            String sectionName = units[i].property.tokenize('_')[0]
            if (sectionName != lastSectionName) { // start new section
                lastSectionName = sectionName
                int lineNumber = blocks[i].block.start.lineNumber - 1
                while (lineNumber > 0 && lines[lineNumber-1].allWhitespace) lineNumber--
                int lineDelta = blocks[i].block.start.lineNumber - lineNumber
                if (lineDelta < 3 && i > 0 && blocks[i-1].block.end.lineNumber == lineNumber) {
                    def block = blocks[i].block
                    units[i].code = '\n' * (3 - lineDelta) + lines[block.start.lineNumber - 1][0 ..< block.start.columnNumber-1] + units[i].code
                }
            }
        }
    }

    static class PropertyAssignCodeUnit {
        String property
        String code
    }

    private void parseEnvScript(String code, String filename, List<List<PropertyAssignBlock>> allBlocks) {
        CompilerConfiguration cc = new CompilerConfiguration()
        cc.addCompilationCustomizers(new CodeCompiler(allBlocks))
        GroovyShell shell = new GroovyShell(cc)
        shell.parse(code, filename)
    }

    class CodeCompiler extends CompilationCustomizer {
        List<List<PropertyAssignBlock>> allBlocks
        List<PropertyAssignBlock> current = null

        CodeCompiler(List<List<PropertyAssignBlock>> allBlocks) {
            super(CompilePhase.CANONICALIZATION)
            this.allBlocks = allBlocks
        }

        void addPropertyAssignBlock(String propertyName, BinaryExpression assignExpression) {
            assert current != null
            current << new PropertyAssignBlock(
                    property: propertyName,
                    block: CodeBlock.parse(assignExpression)
            )
        }

        @Override
        void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
            CodeVisitor visitor = new CodeVisitor()
            MethodNode method = classNode.getMethod("run", new Parameter[0])
            method.code.visit(visitor)
        }

        class CodeVisitor extends CodeVisitorSupport {

            @Override
            void visitBlockStatement(BlockStatement block) {
                List<PropertyAssignBlock> previous = current
                current = []
                super.visitBlockStatement(block)
                if (!current.empty) {
                    allBlocks << current
                }
                current = previous
            }

            @Override
            void visitBinaryExpression(BinaryExpression expression) {
                def leftExpression = expression.leftExpression
                if (expression.operation.type == Types.ASSIGN && leftExpression instanceof VariableExpression) {
                    if (leftExpression.name == leftExpression.name.toUpperCase()) {
                        addPropertyAssignBlock(leftExpression.name, expression)
                    }
                }
            }
        }
    }
}
