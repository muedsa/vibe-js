package benchmark

import com.muedsa.js.ast.Program
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNumber
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
import kotlinx.benchmark.Warmup
import kotlinx.coroutines.runBlocking

/**
 * 解析与缓存基准。
 *
 * 四个方法分别隔离：
 * - `parseOnly`：词法 + 语法 + AST 构建；
 * - `runSource`：解析 + 执行；
 * - `runParsed`：仅执行（复用 AST）；
 * - `runCached`：同源码重复 run 时的 AST 缓存命中路径。
 *
 * 引擎在 teardown 中关闭：不关闭会让事件循环与调度器随基准轮次累积。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class ParsingBenchmark {

    private lateinit var engine: Engine
    private lateinit var cachedEngine: Engine
    private lateinit var source: String
    private lateinit var program: Program

    @Setup
    open fun setUp() {
        engine = Engine()
        cachedEngine = Engine(parseCacheSize = 4)
        source = "var total = 0; for (var i = 0; i < 100; i++) { total += i; } total;"
        program = engine.parse(source)
    }

    @TearDown
    open fun tearDown() = runBlocking {
        engine.close()
        cachedEngine.close()
    }

    @Benchmark
    fun parseOnly(): Program = engine.parse(source)

    @Benchmark
    fun runSource(): Double = runBlocking {
        (engine.run(source) as JSNumber).value
    }

    @Benchmark
    fun runParsed(): Double = runBlocking {
        (engine.run(program) as JSNumber).value
    }

    @Benchmark
    fun runCached(): Double = runBlocking {
        (cachedEngine.run(source) as JSNumber).value
    }
}
