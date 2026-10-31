package com.muedsa.js.runtime.value

/**
 * 私有名（Private Name）。
 *
 * 每次 class 求值都会为每个私有声明创建唯一实例；品牌检查与槽位查找
 * 都依赖引用相等，因此不同 class（甚至同一 class 的不同求值）之间互不干扰。
 */
class PrivateName(val description: String) {
    override fun toString(): String = "#$description"
}

/** 私有元素类别。 */
enum class PrivateElementKind {
    /** 私有字段：可读写的数据槽位。 */
    FIELD,

    /** 私有方法：只读数据槽位（值为函数）。 */
    METHOD,

    /** 私有访问器：getter/setter 可为 null。 */
    ACCESSOR,
}

/**
 * 对象上的私有槽位。
 *
 * 实例字段在每次构造时创建独立槽位；私有方法/访问器在类求值时创建，
 * 由所有实例共享（其值在类定义完成后不再变化）。
 */
class PrivateSlot(val kind: PrivateElementKind) {
    /** FIELD 的当前值 / METHOD 的函数。 */
    var value: JSValue? = null

    /** ACCESSOR 的 getter。 */
    var getter: JSValue? = null

    /** ACCESSOR 的 setter。 */
    var setter: JSValue? = null
}

/**
 * 私有名词法环境。
 *
 * class 体在求值时创建，成员函数与嵌套 class 通过闭包捕获父环境，
 * 因此嵌套 class 可以访问外层 class 的私有名。
 */
class PrivateEnvironment(val parent: PrivateEnvironment?) {

    private val names = mutableMapOf<String, PrivateName>()

    /** 声明（或取得已声明的）私有名。 */
    fun declare(name: String): PrivateName = names.getOrPut(name) { PrivateName(name) }

    /** 在当前层与外层环境中解析私有名。 */
    fun resolve(name: String): PrivateName? = names[name] ?: parent?.resolve(name)

    /** 收集当前层与外层的全部私有名（供直接 eval 语法校验）。 */
    fun collectNames(into: MutableSet<String>) {
        into.addAll(names.keys)
        parent?.collectNames(into)
    }
}
