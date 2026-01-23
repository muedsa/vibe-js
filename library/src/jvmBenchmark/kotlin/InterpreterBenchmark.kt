package benchmark

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSNumber
import kotlinx.benchmark.*

@State(Scope.Benchmark)
@Measurement(iterations = 10, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
open class InterpreterBenchmark {

    private val interpreter: Interpreter = Interpreter()


    private val casePrograms: MutableList<BlockStatement> = mutableListOf()

    @Setup
    open fun setUp() {
        casePrograms.add(parseCode("1 + 2 * 3 ^ 4;"))
        casePrograms.add(parseCode("\"123\" + [] + {} + 456;"))
        casePrograms.add(
            parseCode("""
                function main() {
                    return "hello world";
                }
                main();
            """)
        )
        interpreter.getGlobalEnv().assign(
            name = "arr",
            value = JSArray(buildList {
                for (i in 0 until 1000) {
                    add(JSNumber(i.toDouble()))
                }
            }.toMutableList())
        )
    }

    @TearDown
    open fun teardown() {
        interpreter.getGlobalEnv().removeLocal("arr")
        casePrograms.clear()
    }

    @Benchmark
    open fun numericalCalculation(): Double {
        val code = """
            1 + 2 * 3 ^ 4;
        """.trimIndent()
        val jsValue = interpreter.interpret(casePrograms[0])
        return jsValue.toPrimitiveNumber()
    }

    @Benchmark
    open fun objectConcat(): String {
        val code = """
            "123" + [] + {} + 456;
        """.trimIndent()
        val jsValue = interpreter.interpret(casePrograms[1])
        return jsValue.toPrimitiveString()
    }

    @Benchmark
    open fun functionCall(): String {
        val code = """
            function main() {
                return "hello world";
            }
            
            main();
        """.trimIndent()
        val jsValue = interpreter.interpret(casePrograms[2])
        return jsValue.toPrimitiveString()
    }

    private fun parseCode(code: String): BlockStatement =
        Parser(Lexer(code).tokenize()).parse()
}