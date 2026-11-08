package com.muedsa.js.experimental

import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSProxy
import com.muedsa.js.runtime.value.JSSet
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.JSWeakMap
import com.muedsa.js.runtime.value.JSWeakSet
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 【实验性 · M2 可行性验证】Route A 的最小可达性模型：影子堆 + 显式根 + 标记清除。
 *
 * 这个文件只存在于测试源码中，不进生产路径：
 * - 不注册内建、不修改 [JSWeakMap]/[JSWeakSet]，现有 API 行为完全不受影响；
 * - 只用于回答一个问题：**在 commonMain 内能否用"登记全部对象 + 枚举根 +
 *   标记清除"的方式实现 WeakMap 条目回收**，以及为此需要补齐多少可达性信息。
 *
 * 模型由四部分组成：
 * 1. [managedObjects] 模拟"分配钩子"登记的全部 JSObject。真实实现可以在
 *    `JSObject` 构造 / `ObjectAllocationBudget` 上挂钩（现有代码已经有分配
 *    计数点），但必须覆盖 null 原型、`arguments` 等当前不计数的对象。
 * 2. [roots] 是显式根集合，模拟真实引擎的根枚举。测试手动增删根，
 *    从而可以确定性地构造"键不可达"的瞬间。
 * 3. [ephemerons] 保存 WeakMap/WeakSet 的弱键条目。标记阶段先沿强引用图
 *    从根出发遍历，再对"键已标记"的条目追踪其值，循环到不动点——这正是
 *    ECMAScript WeakMap 的 ephemeron 语义：值能否存活取决于键是否可达，
 *    而不是值本身是否被其他强引用持有。
 * 4. 清除阶段删除"键未标记"的条目，并把影子堆中"未标记"的对象移除。
 *    生产实现中这些对象会同时失去唯一强引用，随后由平台 GC 真正回收。
 *
 * 为什么它只能证明机制、不能直接上线（详见 M2 可行性报告）：
 * - 根集合完全由测试显式维护。真实引擎的根还包括解释器 Kotlin 局部变量与
 *   栈帧、挂起的协程（async / generator 的函数体）、`JSNativeFunction` 的
 *   Kotlin 闭包、`Environment` 的私有绑定表、事件循环里的 suspend lambda 等，
 *   这些都无法在 commonMain 中枚举；
 * - 引用图只覆盖本文件能通过公共 API 枚举的部分（属性表、原型、Map/Set 存储、
 *   函数的部分公开字段等）。闭包环境、Promise 的 `deferred`、生成器协程帧、
 *   `internalState` 里的私有状态对象等都无法枚举，漏掉任何一条边都会导致
 *   "误回收仍可达的对象"，比内存泄漏更危险。
 */
internal class ExperimentalWeakReclamationHeap {

    /** 影子堆：强持有所有登记对象，清除阶段根据标记结果决定去留。 */
    private val managedObjects = LinkedHashSet<JSObject>()

    /** 显式根集合；真实实现会替换为引擎的全部根枚举。 */
    private val roots = LinkedHashSet<JSValue>()

    /** 全部弱键条目（WeakMap 与 WeakSet 共用同一套 ephemeron 处理）。 */
    private val ephemerons = ArrayList<ExperimentalEphemeronEntry>()

    /** 本次回收的标记集合；元素只可能是 JSObject 或 JSSymbol。 */
    private val marked = LinkedHashSet<JSValue>()

    /** 创建并登记一个普通对象，模拟"所有分配都会被分配钩子登记"。 */
    fun newObject(prototype: JSObject? = null): JSObject =
        JSObject(prototype).also { managedObjects.add(it) }

    /** 登记一个外部创建的对象（例如 JSMap、JSArray 等子类实例）。 */
    fun register(value: JSValue): JSValue {
        if (value is JSObject) managedObjects.add(value)
        return value
    }

    /** 增加一个显式根。 */
    fun addRoot(value: JSValue) {
        roots.add(value)
    }

    /** 移除一个显式根；移除后该对象若没有其他引用就会在下次 [collect] 中被回收。 */
    fun removeRoot(value: JSValue) {
        roots.remove(value)
    }

    /** 对象当前是否仍在影子堆中（回收后为 false）。 */
    fun isManaged(value: JSValue): Boolean = value is JSObject && managedObjects.contains(value)

    /** 影子堆当前登记的对象数。 */
    val managedObjectCount: Int get() = managedObjects.size

    /** 供 WeakMap/WeakSet 原型登记弱键条目。 */
    internal fun addEphemeron(entry: ExperimentalEphemeronEntry) {
        ephemerons.add(entry)
    }

    /** 条目被显式删除（delete/clear）时从回收器摘除。 */
    internal fun removeEphemeron(entry: ExperimentalEphemeronEntry) {
        ephemerons.remove(entry)
    }

    /**
     * 执行一次完整的标记-清除。
     *
     * 步骤与真实 GC 一致：根标记 → 强引用图遍历 → ephemeron 不动点 → 清除。
     */
    fun collect(): ExperimentalCollectionReport {
        marked.clear()
        val worklist = ArrayDeque<JSValue>()

        // 1. 从显式根出发标记所有强可达对象。
        for (root in roots) markValue(root, worklist)
        drain(worklist)

        // 2. ephemeron 不动点：只有键已标记时才追踪值。
        //    值可能引用另一个弱键（链式 WeakMap），因此需要反复扫描；
        //    单遍扫描会漏掉"本次扫描中才被标记的键"。
        var changed = true
        while (changed) {
            changed = false
            for (entry in ephemerons) {
                if (!entry.alive || !marked.contains(entry.key)) continue
                if (markValue(entry.value, worklist)) changed = true
            }
            drain(worklist)
        }

        // 3. 清除键不可达的弱键条目（包括对应的真实 WeakMap/WeakSet 存储）。
        var reclaimedEntries = 0
        // toList 快照：reclaim 内部会回调 removeEphemeron 修改 ephemerons。
        for (entry in ephemerons.toList()) {
            if (!entry.alive || marked.contains(entry.key)) continue
            entry.owner.reclaim(entry)
            ephemerons.remove(entry)
            reclaimedEntries++
        }

        // 4. 清除影子堆中不可达的对象；生产实现中它们随后会被平台 GC 回收。
        val scannedObjects = managedObjects.size
        val reclaimedObjects = managedObjects.count { !marked.contains(it) }
        managedObjects.removeAll { !marked.contains(it) }

        return ExperimentalCollectionReport(
            scannedObjects = scannedObjects,
            reclaimedObjects = reclaimedObjects,
            reclaimedEntries = reclaimedEntries,
        )
    }

    /**
     * 把一个值加入标记集合。
     *
     * @return 是否产生了新的标记（用于 ephemeron 不动点循环）
     */
    private fun markValue(value: JSValue, worklist: ArrayDeque<JSValue>): Boolean {
        // 只有对象与 Symbol 有身份、可能成为弱键或持有引用；其余原始值无需标记。
        if (value !is JSObject && value !is JSSymbol) return false
        if (!marked.add(value)) return false
        if (value is JSObject) worklist.addLast(value)
        return true
    }

    /** 深度优先地处理工作队列中的对象引用。 */
    private fun drain(worklist: ArrayDeque<JSValue>) {
        while (true) {
            val value = worklist.removeFirstOrNull() ?: return
            if (value is JSObject) {
                traceReferences(value) { reference -> markValue(reference, worklist) }
            }
        }
    }

    /**
     * 枚举一个对象能直接引用的全部 JS 值。
     *
     * 覆盖范围刻意与"公共 API 可枚举"对齐，同时把缺口显式标注出来：
     * - 普通对象：属性描述符（数据值 / getter / setter）、原型、internalState；
     * - 数组：索引属性通过 `ownPropertyKeys` + `getOwnProperty` 覆盖（含访问器）；
     * - 强 Map/Set：完整遍历存储；WeakMap/WeakSet：键是弱边，跳过，值交给
     *   ephemeron 阶段按"键是否标记"处理；
     * - 函数：公开字段（lexicalThis、homeObject、superBase、superConstructor 等），
     *   **闭包环境 [JSFunction.closure] 的绑定表无法枚举**；
     * - Promise：reactions 与 capability；`deferred` 内部状态无法枚举；
     * - Proxy：target / handler。
     *
     * 生成器、arguments、私有槽位、internalState 中的私有状态对象等仍未覆盖，
     * 这是原型与生产实现之间最大的差距。
     */
    private inline fun traceReferences(value: JSObject, mark: (JSValue) -> Unit) {
        // 原型链：对象 → 原型是强边（原型 → 对象不是）。
        value.prototype?.let(mark)

        // 自有属性表：数据属性的值、访问器属性的 getter/setter 都是强引用。
        for (key in value.ownPropertyKeys()) {
            val descriptor = value.getOwnProperty(key) ?: continue
            descriptor.value?.let(mark)
            descriptor.get?.let(mark)
            descriptor.set?.let(mark)
        }

        // internalState 可能直接存放 JSValue（部分迭代器实现），保守追踪。
        (value.internalState as? JSValue)?.let(mark)

        when (value) {
            // WeakMap/WeakSet：键是弱边，不能在强引用图遍历中标记；值由 ephemeron 阶段处理。
            is JSWeakMap -> Unit
            is JSWeakSet -> Unit

            // 强 Map/Set：键与值都是普通强引用。
            is JSMap -> for ((mapKey, mapValue) in value.liveEntries()) {
                mark(mapKey)
                mark(mapValue)
            }
            is JSSet -> for (element in value.liveValues()) mark(element)

            // 用户函数：只追踪公开字段；closure.bindings 是私有表（见类注释）。
            is JSFunction -> {
                value.lexicalThis?.let(mark)
                value.lexicalNewTarget?.let(mark)
                value.homeObject?.let(mark)
                value.superBase?.let(mark)
                value.superConstructor?.let(mark)
                value.functionPrototypeObject?.let(mark)
            }

            is JSProxy -> {
                mark(value.target)
                mark(value.handler)
            }

            is JSPromise -> for (reaction in value.reactions) {
                reaction.onFulfilled?.let(mark)
                reaction.onRejected?.let(mark)
                reaction.child?.let(mark)
                reaction.capability?.let { capability ->
                    mark(capability.promise)
                    mark(capability.resolve)
                    mark(capability.reject)
                }
            }

            // 其余对象（JSError、JSDate、RegExp、TypedArray 等）只有属性表引用，
            // 已由上面的 ownPropertyKeys 覆盖；其内部数据槽在此原型中不展开。
            else -> Unit
        }
    }
}

/**
 * 一个弱键条目的元数据。
 *
 * @property owner 条目所属的 WeakMap/WeakSet 原型，用于清除时同步真实存储
 * @property key 弱键（对象或非注册 Symbol）；存强引用只是为了在原型里
 *   能按身份删除对应条目——标记阶段不会把它当作根
 * @property value WeakMap 的值；WeakSet 使用 [JSUndefined] 占位
 * @property alive 是否仍然有效（delete/clear 后置为 false）
 */
internal class ExperimentalEphemeronEntry(
    val owner: ExperimentalWeakCollection,
    val key: JSValue,
    var value: JSValue,
    var alive: Boolean = true,
)

/** WeakMap/WeakSet 原型在回收器中的统一回调。 */
internal interface ExperimentalWeakCollection {
    /** 键不可达时同步删除真实存储中的条目。 */
    fun reclaim(entry: ExperimentalEphemeronEntry)
}

/**
 * 实验性 WeakMap：在真实 [JSWeakMap] 之上补一层 ephemeron 元数据。
 *
 * 存储仍然使用 [JSWeakMap]（也就是现有的 JSMap + 强引用实现），但条目的
 * 存活由外部 [ExperimentalWeakReclamationHeap] 的标记结果决定：键不可达时
 * 调用 `target.deleteEntry(key)`。这样验收就能证明"回收后 JS 侧语义同步变化"
 * （has/get 立刻反映删除），而不是只操作一个孤立的测试数据结构。
 */
internal class ExperimentalWeakMap(
    private val heap: ExperimentalWeakReclamationHeap,
    prototype: JSObject? = null,
) : ExperimentalWeakCollection {

    /** 真实 WeakMap 对象；测试通过它验证回收后的 JS 可见语义。 */
    internal val target: JSWeakMap = JSWeakMap(prototype)

    /** 键 → 条目；键保持身份语义（JSObject/JSSymbol 未重写 equals/hashCode）。 */
    private val entries = LinkedHashMap<JSValue, ExperimentalEphemeronEntry>()

    /** 写入条目：同键覆盖值，不新增条目。 */
    fun set(key: JSValue, value: JSValue): ExperimentalWeakMap {
        require(isWeakKey(key)) { "Invalid value used as WeakMap key" }
        val existing = entries[key]
        if (existing != null) {
            existing.value = value
        } else {
            val entry = ExperimentalEphemeronEntry(this, key, value)
            entries[key] = entry
            heap.addEphemeron(entry)
        }
        target.setEntry(key, value)
        return this
    }

    /** 读取条目；回收后返回 null（JS 侧表现为 undefined）。 */
    fun get(key: JSValue): JSValue? = target.getEntry(key)?.second

    /** 是否存在条目。 */
    fun has(key: JSValue): Boolean = target.containsKey(key)

    /** 删除条目并同步摘除 ephemeron 元数据。 */
    fun delete(key: JSValue): Boolean {
        val entry = entries.remove(key)
        if (entry != null) {
            entry.alive = false
            heap.removeEphemeron(entry)
        }
        return target.deleteEntry(key)
    }

    /** 有效条目数（与 JSWeakMap 不暴露 size 的语义一致，仅供测试观察）。 */
    val liveSize: Int get() = target.liveSize

    /** 物理槽位数（墓碑诊断，仅供测试）。 */
    fun storageSize(): Int = target.storageSize()

    override fun reclaim(entry: ExperimentalEphemeronEntry) {
        if (!entry.alive) return
        entry.alive = false
        entries.remove(entry.key)
        target.deleteEntry(entry.key)
    }
}

/**
 * 实验性 WeakSet：与 [ExperimentalWeakMap] 对称，成员因标记结果被删除。
 *
 * WeakSet 没有值，条目用 [JSUndefined] 占位；ephemeron 阶段只关心键是否被标记。
 */
internal class ExperimentalWeakSet(
    private val heap: ExperimentalWeakReclamationHeap,
    prototype: JSObject? = null,
) : ExperimentalWeakCollection {

    /** 真实 WeakSet 对象。 */
    internal val target: JSWeakSet = JSWeakSet(prototype)

    /** 成员 → 条目。 */
    private val entries = LinkedHashMap<JSValue, ExperimentalEphemeronEntry>()

    /** 添加成员；重复添加忽略。 */
    fun add(key: JSValue): ExperimentalWeakSet {
        require(isWeakKey(key)) { "Invalid value used in WeakSet" }
        if (!entries.containsKey(key)) {
            val entry = ExperimentalEphemeronEntry(this, key, JSUndefined)
            entries[key] = entry
            heap.addEphemeron(entry)
        }
        target.addValue(key)
        return this
    }

    /** 是否存在成员。 */
    fun has(key: JSValue): Boolean = target.containsValue(key)

    /** 删除成员并同步摘除 ephemeron 元数据。 */
    fun delete(key: JSValue): Boolean {
        val entry = entries.remove(key)
        if (entry != null) {
            entry.alive = false
            heap.removeEphemeron(entry)
        }
        return target.deleteValue(key)
    }

    /** 有效成员数（仅供测试观察）。 */
    val liveSize: Int get() = target.liveSize

    override fun reclaim(entry: ExperimentalEphemeronEntry) {
        if (!entry.alive) return
        entry.alive = false
        entries.remove(entry.key)
        target.deleteValue(entry.key)
    }
}

/** 一次标记-清除的统计结果，用于在测试里断言回收范围。 */
internal data class ExperimentalCollectionReport(
    /** 回收前影子堆中的对象数。 */
    val scannedObjects: Int,
    /** 本次被清除的对象数。 */
    val reclaimedObjects: Int,
    /** 本次被清除的弱键条目数。 */
    val reclaimedEntries: Int,
)

/**
 * CanBeHeldWeakly 的最小复刻：对象或非注册 Symbol。
 *
 * 与 `WeakMapBuiltins.canBeHeldWeakly` 保持一致；这里单独实现是为了让原型
 * 不依赖内建模块的内部函数，便于将来直接搬进 commonMain 的实验分支。
 */
private fun isWeakKey(value: JSValue): Boolean =
    value is JSObject || (value is JSSymbol && !value.registered)

/** 测试用的属性键快捷方式，避免每个用例重复写 PropertyKey.Str。 */
internal fun stringKey(text: String): PropertyKey = PropertyKey.Str(text)
