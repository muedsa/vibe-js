package com.muedsa.js.runtime.value

/**
 * Map/Set 的键规范化：规范要求集合中的 `-0` 归一化为 `+0`。
 *
 * 规范化必须发生在**传给回调之前**（例如 `Map.prototype.getOrInsertComputed` 的回调
 * 会收到规范化后的键），因此单独提供该函数，而不是只在写入时处理。
 */
internal fun normalizeCollectionKey(key: JSValue): JSValue =
    if (key is JSNumber && key.value == 0.0) JSNumber.ZERO else key

/**
 * Map/Set 的键包装：按 ECMAScript 的 SameValueZero 比较与哈希。
 *
 * 为什么需要包装而不是直接用 [JSValue]：
 * - `JSNumber`/`JSString` 没有重写 equals/hashCode，默认是引用比较，
 *   而 Map 的键必须按值比较；
 * - `NaN` 在 `Double.equals` 中不等于自身，但 Map 中必须视为同一个键；
 * - `-0` 与 `+0` 必须是同一个键。
 *
 * 对象、函数与 Symbol 按引用比较，因此直接使用其身份哈希。
 * 哈希只要求"同一个键得到同一个值"，碰撞由 [equals] 兜底。
 */
internal class CollectionKey(val value: JSValue) {

    private val hash: Int = hashOf(value)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        other is CollectionKey && sameValueZero(value, other.value)

    companion object {

        /** NaN 的统一哈希；所有 NaN 在 Map 中相等。 */
        private const val NAN_HASH = 0x7fc00000

        private const val TRUE_HASH = 1231
        private const val FALSE_HASH = 1237
        private const val UNDEFINED_HASH = 0
        private const val NULL_HASH = 1

        /**
         * 计算与 [sameValueZero] 一致的哈希。
         *
         * 注意 `-0.0` 与 `0.0` 必须得到同一个哈希（`Double.hashCode` 会区分二者）。
         */
        fun hashOf(value: JSValue): Int = when (value) {
            is JSUndefined -> UNDEFINED_HASH
            is JSNull -> NULL_HASH
            is JSBoolean -> if (value.value) TRUE_HASH else FALSE_HASH
            is JSNumber -> {
                val number = value.value
                when {
                    number.isNaN() -> NAN_HASH
                    number == 0.0 -> 0
                    else -> number.hashCode()
                }
            }
            is JSString -> value.value.hashCode()
            // BigInt 是原始值，按数值哈希/相等（JSBigInt 已重写 hashCode）
            is JSBigInt -> value.hashCode()
            // 对象、函数、Symbol：引用身份，同一对象哈希稳定
            else -> value.hashCode()
        }

        /**
         * SameValueZero：与 `Conversions.sameValueZero` 语义一致。
         *
         * 与严格相等唯一的差别是 `NaN` 等于自身；`-0` 与 `+0` 相等。
         */
        fun sameValueZero(a: JSValue, b: JSValue): Boolean {
            if (a === b) return true
            return when {
                a is JSBoolean && b is JSBoolean -> a.value == b.value
                a is JSNumber && b is JSNumber ->
                    a.value == b.value || (a.value.isNaN() && b.value.isNaN())
                a is JSString && b is JSString -> a.value == b.value
                a is JSBigInt && b is JSBigInt -> a == b
                // undefined/null 是单例（上面已由 === 处理）；Symbol/对象只按引用相等
                else -> false
            }
        }
    }
}
