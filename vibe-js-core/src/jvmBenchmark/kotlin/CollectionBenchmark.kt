package benchmark

import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSValue
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup

/**
 * Map/Set 集合存储基准（M1 的验收依据，同时作为 B1 的集合分组）。
 *
 * 目标是用不同规模证明：
 * - 单键 get/has/set 为均摊 O(1)：1k/10k/100k 的**单次操作**耗时基本持平；
 * - `size` 读取为 O(1)：与集合规模无关；
 * - 批量删除+重插不因墓碑线性增长（墓碑在无迭代器时压缩）。
 *
 * 单键方法每次只操作一个元素，因此 AverageTime 直接反映单次操作成本；
 * 批量方法处理整个集合，数值会随规模线性增长，属于预期行为。
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 300, timeUnit = BenchmarkTimeUnit.MILLISECONDS)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
open class CollectionBenchmark {

    /** 集合规模：用于观察单次操作耗时是否随规模增长。 */
    @Param("1000", "10000", "100000")
    var size: Int = 1_000

    private lateinit var keys: Array<JSValue>
    private lateinit var map: JSMap
    private lateinit var set: JSSet

    /** 探测键固定取中间位置，避免首尾元素带来的缓存偏差。 */
    private lateinit var probe: JSValue

    @Setup
    open fun setUp() {
        keys = Array(size) { JSNumber(it.toDouble()) }
        probe = keys[size / 2]
        map = JSMap(prototype = null)
        set = JSSet(prototype = null)
        for (key in keys) {
            map.setEntry(key, key)
            set.addValue(key)
        }
    }

    @Benchmark
    fun mapGetSingle(): JSValue? = map.getEntry(probe)?.second

    @Benchmark
    fun mapHasSingle(): Boolean = map.containsKey(probe)

    @Benchmark
    fun mapSetSingle(): Int {
        map.setEntry(probe, probe)
        return map.liveSize
    }

    /** 读取 size 与规模无关：循环 size 次，单次成本即 O(1)。 */
    @Benchmark
    fun mapSizeRead(): Int {
        var total = 0
        for (index in 0 until size) total += map.liveSize
        return total
    }

    /** 批量删除 + 重插：观察墓碑压缩后的稳定表现。 */
    @Benchmark
    fun mapDeleteInsertBatch(): Int {
        for (key in keys) map.deleteEntry(key)
        for (key in keys) map.setEntry(key, key)
        return map.liveSize
    }

    @Benchmark
    fun mapIterateBatch(): Double {
        var sum = 0.0
        for ((_, value) in map.liveEntries()) {
            sum += (value as? JSNumber)?.value ?: 0.0
        }
        return sum
    }

    @Benchmark
    fun setHasSingle(): Boolean = set.containsValue(probe)

    @Benchmark
    fun setAddSingle(): Int {
        set.addValue(probe)
        return set.liveSize
    }

    @Benchmark
    fun setDeleteInsertBatch(): Int {
        for (key in keys) set.deleteValue(key)
        for (key in keys) set.addValue(key)
        return set.liveSize
    }
}
