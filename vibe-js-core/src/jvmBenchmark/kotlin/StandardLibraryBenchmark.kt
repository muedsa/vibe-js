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
 * 标准库基准。
 *
 * 覆盖 JSON、Array、String 与 RegExp 的常见热路径；
 * 这些内建经常出现在真实库的启动与数据处理路径上。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class StandardLibraryBenchmark {

    private lateinit var engine: Engine
    private lateinit var jsonParse: Program
    private lateinit var jsonStringify: Program
    private lateinit var arrayMap: Program
    private lateinit var regexpExec: Program
    private lateinit var stringSlice: Program

    @Setup
    open fun setUp() {
        engine = Engine()
        jsonParse = engine.parse(
            """
                var text = '{"a":[1,2,3,4,5],"b":{"c":"d","e":[true,false,null]},"f":"g"}';
                var sum = 0;
                for (var i = 0; i < 100; i++) { sum += JSON.parse(text).a.length; }
                sum;
            """.trimIndent()
        )
        jsonStringify = engine.parse(
            """
                var value = { a: [1, 2, 3, 4, 5], b: { c: 'd', e: [true, false, null] }, f: 'g' };
                var total = 0;
                for (var i = 0; i < 100; i++) { total += JSON.stringify(value).length; }
                total;
            """.trimIndent()
        )
        arrayMap = engine.parse(
            """
                var items = [];
                for (var i = 0; i < 1000; i++) { items.push(i); }
                items.map(function (value) { return value * 2; }).length;
            """.trimIndent()
        )
        regexpExec = engine.parse(
            """
                var pattern = /(\d+)-(\d+)/;
                var text = 'abc 123-456 def';
                var total = 0;
                for (var i = 0; i < 1000; i++) { total += pattern.exec(text)[1].length; }
                total;
            """.trimIndent()
        )
        stringSlice = engine.parse(
            """
                var text = 'abcdefghij'.repeat(100);
                var total = 0;
                for (var i = 0; i < 1000; i++) { total += text.slice(i % 10, 50).length; }
                total;
            """.trimIndent()
        )
    }

    @TearDown
    open fun tearDown() = runBlocking { engine.close() }

    @Benchmark
    fun jsonParse(): JSValue = runBlocking { engine.run(jsonParse) }

    @Benchmark
    fun jsonStringify(): JSValue = runBlocking { engine.run(jsonStringify) }

    @Benchmark
    fun arrayMap(): JSValue = runBlocking { engine.run(arrayMap) }

    @Benchmark
    fun regexpExec(): JSValue = runBlocking { engine.run(regexpExec) }

    @Benchmark
    fun stringSlice(): JSValue = runBlocking { engine.run(stringSlice) }
}
