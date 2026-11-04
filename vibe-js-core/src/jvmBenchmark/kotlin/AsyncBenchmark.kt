package benchmark

import com.muedsa.js.ast.Program
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSValue
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
 * 异步与事件循环基准。
 *
 * 每个用例在 `Engine.run` 返回前完成微任务冲刷，因此测量包含：
 * Promise 状态机、then 链调度、async/await 协程恢复与微任务队列开销。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class AsyncBenchmark {

    private lateinit var engine: Engine
    private lateinit var promiseChain: Program
    private lateinit var asyncAwait: Program
    private lateinit var microtaskStorm: Program

    @Setup
    open fun setUp() {
        engine = Engine()
        promiseChain = engine.parse(
            """
                var p = Promise.resolve(0);
                for (var i = 0; i < 100; i++) {
                  p = p.then(function (value) { return value + 1; });
                }
                0;
            """.trimIndent()
        )
        asyncAwait = engine.parse(
            """
                async function run() {
                  var sum = 0;
                  for (var i = 0; i < 100; i++) { sum += await Promise.resolve(i); }
                  return sum;
                }
                run();
            """.trimIndent()
        )
        microtaskStorm = engine.parse(
            """
                for (var i = 0; i < 1000; i++) {
                  Promise.resolve(i).then(function () {});
                }
                0;
            """.trimIndent()
        )
    }

    @TearDown
    open fun tearDown() = runBlocking { engine.close() }

    @Benchmark
    fun promiseChain(): JSValue = runBlocking { engine.run(promiseChain) }

    @Benchmark
    fun asyncAwait(): JSValue = runBlocking { engine.run(asyncAwait) }

    @Benchmark
    fun microtaskStorm(): JSValue = runBlocking { engine.run(microtaskStorm) }
}
