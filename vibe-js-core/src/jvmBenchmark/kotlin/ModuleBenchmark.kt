package benchmark

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.PropertyKey
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
 * 模块图加载 + 链接 + 环境 + namespace 求值的端到端观测。
 *
 * 不承诺优化；与 B1 基准分开运行，避免模块实验性路径影响原有脚本基线。
 */
@OptIn(ExperimentalModuleApi::class)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class ModuleBenchmark {

    private lateinit var engine: Engine
    private lateinit var entry: ModuleSource
    private lateinit var provider: ModuleProvider

    @Setup
    open fun setUp() {
        engine = Engine()
        entry = ModuleSource(
            id = "entry",
            source = "import { value } from \"./dep.js\"; export { value };",
            sourceName = "entry.mjs",
        )
        provider = InMemoryModuleProvider(
            mapOf("./dep.js" to "export const value = 1;"),
        )
    }

    @TearDown
    open fun tearDown() = runBlocking {
        engine.close()
    }

    @Benchmark
    fun runModule(): Double = runBlocking {
        val namespace = engine.runModule(entry, provider)
        (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value
    }
}
