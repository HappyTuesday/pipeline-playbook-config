package utils.services

import com.yit.deploy.core.exceptions.IllegalConfigException
import com.yit.deploy.core.utils.EncryptionUtils
import com.yit.deploy.core.utils.Utils
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import utils.services.coding.CodeBlock
import utils.services.coding.MethodCallBlock
import utils.services.coding.ReplaceTextTask

/**
 * Created by nick on 08/09/2017.
 */
class AutoEncryptVariablesService {
    boolean convertEncrypted
    boolean convertDecrypted

    String environmentScriptFolder = Utils.rootPath + "/src/envs"
    String projectScriptFolder = Utils.rootPath + "/src/projects"
    String resourceSecretFolder = Utils.rootPath + "/resources/secrets"

    static void main(String[] args) {
        def service = new AutoEncryptVariablesService()
        if (args[1] == "encrypt") {
            service.convertDecrypted = true
        } else if (args[1] == "decrypt") {
            service.convertEncrypted = true
        }

        if ("-p" in args || "-f" in args) {
            if (args.length >= 5 && args[2] == "-p" && args[3] == "-f") {
                service.pipeConvert(args[4])
            } else {
                throw new IllegalArgumentException("args: $args")
            }
        } else {
            service.convertAllFiles()
        }
    }

    void pipeConvert(String filepath) {
        convertOneFile(new File(filepath.replace('\'', '')), true)
    }

    void convertAllFiles() {
        for (String folder in [environmentScriptFolder, projectScriptFolder, resourceSecretFolder]) {
            new File(folder).traverse { file ->
                if (file.file) {
                    convertOneFile(file)
                }
            }
        }
    }

    private void convertOneFile(File file, pipe = false) {
        if (file.name.startsWith(".")) return

        if (file.name.endsWith(".groovy")) {
            List<String> lines = pipe ? System.in.readLines() : file.readLines()

            String envtype = null
            if (file.absolutePath.startsWith(environmentScriptFolder)) {
                String envName = file.name.substring(0, file.name.length() - ".groovy".length())
                String folder = file.parentFile.name
                envtype = envName == "prod" ? envName : folder in ["prod"] ? folder : "testenv"
            }

            lines = convertScript(lines, file.name, envtype)

            String newContent = lines.join('\n')

            if (pipe) {
                System.err.println("[AutoEncryptVariablesService] convert file $file.path")
                println(newContent)
            } else if (file.text != newContent) {
                System.err.println("[AutoEncryptVariablesService] convert file $file.path")
                file.text = newContent
            }
        } else if (file.absolutePath.startsWith(resourceSecretFolder)) {
            byte[] bytes = pipe ? System.in.bytes : file.bytes

            String envName = file.absolutePath.substring(resourceSecretFolder.length()).tokenize('/')[0]
            String envtype = envName == "prod" ? envName : "testenv"
            bytes = convertNormalFile(bytes, envtype)

            if (pipe) {
                System.err.println("[AutoEncryptVariablesService] convert file $file.path")
                System.out.write(bytes)
            } else if (!Arrays.equals(file.bytes, bytes)) {
                System.err.println("[AutoEncryptVariablesService] convert file $file.path")
                file.bytes = bytes
            }
        }
    }

    private List<String> convertScript(List<String> lines, String filename, String envtype) {
        List<MethodCallBlock> blocks = []
        parseScript(lines.join('\n'), filename, blocks)

        List<ReplaceTextTask> tasks = []
        for (int i = 0; i < blocks.size(); i++) {
            MethodCallBlock block = blocks[i]
            String finalEnvType = block.envType ?: envtype ?: "testenv"

            EncryptionUtils encryptionUtils = new EncryptionUtils(finalEnvType)

            if (block.methodName == "encrypted" && convertEncrypted) {
                tasks << new ReplaceTextTask(
                        block: block.methodNameBlock,
                        text: "decrypted"
                )
                tasks << new ReplaceTextTask(
                        block: block.parameterBlock,
                        text: toGroovyStringConstant(encryptionUtils.decryptToText(block.parameter))
                )
            } else if (block.methodName == "decrypted" && convertDecrypted) {
                tasks << new ReplaceTextTask(
                        block: block.methodNameBlock,
                        text: "encrypted"
                )
                tasks << new ReplaceTextTask(
                        block: block.parameterBlock,
                        text: toGroovyStringConstant(encryptionUtils.encryptFromText(block.parameter))
                )
            }
        }

        ReplaceTextTask.executeAll(lines, tasks)
        return lines
    }

    private byte[] convertNormalFile(byte[] bytes, String envtype) {
        if (bytes.length == 0) return bytes
        if (convertEncrypted) {
            String text = new String(bytes, Utils.DefaultCharset)
            if (EncryptionUtils.isEncrypted(text)) {
                return new EncryptionUtils(envtype).decrypt(text)
            }
        } else if (convertDecrypted) {
            if (!EncryptionUtils.isEncrypted(bytes)) {
                return new EncryptionUtils(envtype).encrypt(bytes).getBytes(Utils.DefaultCharset)
            }
        }
        return bytes
    }

    private static String toGroovyStringConstant(String s) {
        if (s.contains('\n')) {
            if (s.indexOf("'''") >= 0) {
                s = s.replaceAll("'", "\\\\'")
            }
            "'''\\\n" + s + "'''"
        } else {
            "'" + s.replaceAll("'", "\\\\'") + "'"
        }
    }

    private void parseScript(String code, String filename, List<MethodCallBlock> blocks) {
        CompilerConfiguration cc = new CompilerConfiguration()
        cc.addCompilationCustomizers(new CodeCompiler(blocks))
        GroovyShell shell = new GroovyShell(cc)
        shell.parse(code, filename)
    }

    class CodeCompiler extends CompilationCustomizer {
        List<MethodCallBlock> blocks

        CodeCompiler(List<MethodCallBlock> blocks) {
            super(CompilePhase.CANONICALIZATION)
            this.blocks = blocks
        }

        void addMethodCallBlock(ConstantExpression method, ConstantExpression exp, String envType) {
            blocks << new MethodCallBlock(
                methodName: method.value as String,
                methodNameBlock: CodeBlock.parse(method),
                parameter: exp.value as String,
                parameterBlock: CodeBlock.parse(exp),
                envType: envType
            )
        }

        @Override
        void call(SourceUnit source, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
            CodeVisitor visitor = new CodeVisitor(source.name)
            MethodNode method = classNode.getMethod("run", new Parameter[0])
            method.code.visit(visitor)
        }

        class CodeVisitor extends CodeVisitorSupport {
            String sourceName
            List<String> methodNameWhiteList = ["encrypted", "decrypted"]
            CurrentEnvType currentEnvType

            CodeVisitor(String sourceName) {
                this.sourceName = sourceName
            }

            enum CurrentEnvType {
                local,
                testenv,
                prod,
                conflict,
                unsupported
            }

            @Override
            void visitMethodCallExpression(MethodCallExpression call) {
                if (call.method instanceof ConstantExpression && ((call.method as ConstantExpression).value as String) in methodNameWhiteList) {
                    if (call.arguments instanceof ArgumentListExpression && (call.arguments as ArgumentListExpression).expressions.size() == 1) {
                        Expression exp = (call.arguments as ArgumentListExpression).expressions[0]
                        if (exp instanceof ConstantExpression) {
                            String envType
                            if (this.currentEnvType) {
                                switch (this.currentEnvType) {
                                    case CurrentEnvType.local:
                                    case CurrentEnvType.testenv:
                                    case CurrentEnvType.prod:
                                        envType = this.currentEnvType.toString()
                                        break
                                    case CurrentEnvType.conflict:
                                        throw new IllegalConfigException("Encrypted / Decrypted method cannot be used " +
                                            "inside an override method with both testenv and prod env: " +
                                            "$call.text in $sourceName:$call.lineNumber")
                                    case CurrentEnvType.unsupported:
                                        throw new IllegalConfigException("Encrypted / Decrypted method cannot be used " +
                                            "inside unsupported override signature: " +
                                            "$call.text in $sourceName:$call.lineNumber")
                                    default:
                                        throw new IllegalStateException()
                                }
                            } else {
                                envType = null
                            }
                            addMethodCallBlock(call.method as ConstantExpression, exp as ConstantExpression, envType)
                            return
                        }
                    }
                }

                if (call.method instanceof ConstantExpression && ((call.method as ConstantExpression).value as String) == "override") {
                    if (call.arguments instanceof ArgumentListExpression) {
                        CurrentEnvType envType = null
                        for (Expression exp : call.arguments as ArgumentListExpression) {
                            if (exp instanceof ConstantExpression) {
                                def value = exp.value

                                if (value instanceof String || value instanceof GString) {
                                    String envName = value as String

                                    CurrentEnvType newEnvType
                                    if (envName.contains("prod")) {
                                        newEnvType = CurrentEnvType.prod
                                    } else {
                                        newEnvType = CurrentEnvType.testenv
                                    }

                                    if (envType && envType != newEnvType) {
                                        envType = CurrentEnvType.conflict
                                    } else {
                                        envType = newEnvType
                                    }
                                }
                            } else if (exp instanceof ClosureExpression) {
                                // skip
                            } else {
                                envType = CurrentEnvType.unsupported
                            }
                        }

                        def old = this.currentEnvType
                        this.currentEnvType = envType
                        super.visitMethodCallExpression(call)
                        this.currentEnvType = old

                        return
                    }
                }

                super.visitMethodCallExpression(call)
            }
        }
    }
}
