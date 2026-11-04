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
 * 对象与环境基准。
 *
 * 覆盖对象字面量创建、属性读写与闭包变量查找——这三条路径决定了解释器
 * 在"业务型脚本"（大量小对象与嵌套闭包）上的成本，也是后续
 * 槽位化环境 / 内联缓存优化的观察点。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class ObjectEnvironmentBenchmark {

    private lateinit var engine: Engine
    private lateinit var objectCreate: Program
    private lateinit var propertyGet: Program
    private lateinit var propertySet: Program
    private lateinit var closureLookup: Program

    @Setup
    open fun setUp() {
        engine = Engine()
        objectCreate = engine.parse(
            "var o = { a: 1, b: 2, c: 3, d: 4, e: 5 }; o.a + o.e;"
        )
        propertyGet = engine.parse(
            "var target = { x: 1 }; var sum = 0; " +
                "for (var i = 0; i < 1000; i++) { sum += target.x; } sum;"
        )
        propertySet = engine.parse(
            "var target = {}; for (var i = 0; i < 1000; i++) { target.x = i; } target.x;"
        )
        closureLookup = engine.parse(
            """
                function outer() {
                  var a = 1, b = 2, c = 3, d = 4;
                  return function inner() {
                    var sum = 0;
                    for (var i = 0; i < 1000; i++) { sum += a + b + c + d; }
                    return sum;
                  };
                }
                outer()();
            """.trimIndent()
        )
    }

    @TearDown
    open fun tearDown() = runBlocking { engine.close() }

    @Benchmark
    fun objectCreate(): JSValue = runBlocking { engine.run(objectCreate) }

    @Benchmark
    fun propertyGet(): JSValue = runBlocking { engine.run(propertyGet) }

    @Benchmark
    fun propertySet(): JSValue = runBlocking { engine.run(propertySet) }

    @Benchmark
    fun closureLookup(): JSValue = runBlocking { engine.run(closureLookup) }
}
