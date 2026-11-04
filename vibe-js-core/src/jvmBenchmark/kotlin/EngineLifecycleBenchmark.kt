package benchmark

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSNumber
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlinx.coroutines.runBlocking

/**
 * Engine 生命周期基准。
 *
 * 单独测量创建、创建+关闭、以及"创建→执行→关闭"的完整开销：
 * 内建安装（CoreBuiltins）成本较高，只有把这部分与执行成本分开，
 * 才能判断批量创建引擎的调用方是否值得复用引擎。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class EngineLifecycleBenchmark {

    @Benchmark
    fun engineCreate(): Engine = Engine()

    @Benchmark
    fun engineCreateClose(): Boolean = runBlocking {
        val engine = Engine()
        engine.close()
        true
    }

    @Benchmark
    fun engineRunAndClose(): Double = runBlocking {
        val engine = Engine()
        val value = (engine.run("1 + 2 * 3") as JSNumber).value
        engine.close()
        value
    }
}
