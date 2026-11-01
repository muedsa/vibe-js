package benchmark

import com.muedsa.js.ast.Program
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.runBlocking

/**
 * 解释器吞吐基准。
 *
 * 说明：`*` 方法衡量 Engine.run 的整体吞吐（含词法/语法解析与执行），
 * `parsed*` 方法复用 Engine.parse 得到的 AST，仅衡量执行期吞吐。
 */
@State(Scope.Benchmark)
@Measurement(iterations = 10, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
open class InterpreterBenchmark {

    private lateinit var engine: Engine
    private lateinit var programs: List<String>
    private lateinit var parsedPrograms: List<Program>

    @Setup
    open fun setUp() {
        engine = Engine()
        programs = listOf(
            // 算术运算
            "1 + 2 * 3",
            // 字符串与对象拼接
            "\"123\" + [] + {} + 456",
            // 函数调用
            "function main() { return \"hello world\"; } main();",
            // 数组迭代
            "var arr = []; for (var i = 0; i < 100; i++) { arr.push(i); } arr.length;",
        )
        parsedPrograms = programs.map { engine.parse(it) }
    }

    @TearDown
    open fun tearDown() {
        programs = emptyList()
        parsedPrograms = emptyList()
    }

    @Benchmark
    open fun arithmetic(): Double = runBlocking {
        (engine.run(programs[0]) as JSNumber).value
    }

    @Benchmark
    open fun objectConcat(): String = runBlocking {
        (engine.run(programs[1]) as JSString).value
    }

    @Benchmark
    open fun functionCall(): String = runBlocking {
        (engine.run(programs[2]) as JSString).value
    }

    @Benchmark
    open fun arrayLoop(): Double = runBlocking {
        (engine.run(programs[3]) as JSNumber).value
    }

    @Benchmark
    open fun parsedArithmetic(): Double = runBlocking {
        (engine.run(parsedPrograms[0]) as JSNumber).value
    }

    @Benchmark
    open fun parsedObjectConcat(): String = runBlocking {
        (engine.run(parsedPrograms[1]) as JSString).value
    }

    @Benchmark
    open fun parsedFunctionCall(): String = runBlocking {
        (engine.run(parsedPrograms[2]) as JSString).value
    }

    @Benchmark
    open fun parsedArrayLoop(): Double = runBlocking {
        (engine.run(parsedPrograms[3]) as JSNumber).value
    }
}
