package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 变量绑定。
 *
 * @property value 当前值
 * @property mutable 是否可重新赋值（const 为 false）
 * @property initialized 是否已初始化（用于 let/const 的暂时性死区）
 */
class Binding(
    var value: JSValue,
    val mutable: Boolean,
    var initialized: Boolean = true,
    /** 是否为词法（let/const/class）绑定；var/参数为 false */
    val lexical: Boolean = false,
    /**
     * 是否为严格绑定（规范 SetMutableBinding 的 S 参数置真）。
     *
     * const/class 的不可变绑定始终严格，非严格代码赋值也抛 TypeError；
     * 具名函数表达式的名字绑定则是非严格不可变绑定（sloppy 静默忽略）。
     */
    val strict: Boolean = false,
    /** 是否为可删除绑定（sloppy eval 在函数 var 环境新建的 var/function） */
    val deletable: Boolean = false,
)

/**
 * 执行环境（作用域）。
 *
 * 两类实现：
 * - [DeclarativeEnvironment]：普通的词法作用域 / 函数作用域，绑定存放在 Map 中；
 * - [ObjectEnvironment]：把变量绑定映射到对象属性上（用于全局对象）。
 *
 * 后续性能优化可以把 DeclarativeEnvironment 的 Map 查找替换为槽位索引，
 * 外部调用方（Interpreter）只依赖这里的接口，不需要改语义。
 */
sealed class Environment(val parent: Environment?) {

    /** 是否为 var 作用域（函数作用域或全局对象环境）。 */
    open val isVarScope: Boolean get() = false

    /** 当前作用域链（含父级）是否存在该绑定。 */
    open fun hasBinding(name: String): Boolean = parent?.hasBinding(name) ?: false

    /** 仅当前作用域是否存在该绑定。 */
    open fun hasOwnBinding(name: String): Boolean = false

    /**
     * 读取绑定值；不存在返回 null（由调用方决定是否抛 ReferenceError）。
     */
    open suspend fun getBinding(name: String): JSValue? = parent?.getBinding(name)

    /**
     * 写入绑定值；返回是否找到绑定。
     * 未找到时由调用方决定严格模式抛错或创建全局变量。
     */
    open suspend fun setBinding(name: String, value: JSValue, strict: Boolean): Boolean =
        parent?.setBinding(name, value, strict) ?: false

    /** 创建/更新 var 绑定（由 var 作用域实现）。 */
    open fun createVarBinding(name: String, value: JSValue) = Unit

    /** 确保 var 绑定存在（初始化为 undefined），不覆盖已有绑定。 */
    open fun ensureVarBinding(name: String) = Unit

    /** 标识符引用解析用：当前环境是否真正绑定该名字（with 环境会检查 @@unscopables）。 */
    open suspend fun hasBindingForReference(name: String): Boolean = hasOwnBinding(name)

    /** 在当前作用域创建 let 绑定。 */
    open fun createLetBinding(name: String, value: JSValue) = Unit

    /** 在当前作用域创建 const 绑定。 */
    open fun createConstBinding(name: String, value: JSValue) = Unit

    /** 删除绑定；返回是否成功。 */
    open fun deleteBinding(name: String): Boolean = parent?.deleteBinding(name) ?: false

    /** 沿作用域链找到最近的 var 作用域。 */
    fun findVarScope(): Environment {
        var current: Environment = this
        while (!current.isVarScope) {
            current = current.parent ?: return current
        }
        return current
    }
}

/**
 * 声明式环境：函数作用域、块作用域、catch 作用域等。
 */
class DeclarativeEnvironment(
    parent: Environment? = null,
    override val isVarScope: Boolean = false,
) : Environment(parent) {

    private val bindings: MutableMap<String, Binding> = LinkedHashMap()

    override fun hasBinding(name: String): Boolean =
        bindings.containsKey(name) || super.hasBinding(name)

    override fun hasOwnBinding(name: String): Boolean = bindings.containsKey(name)

    override suspend fun getBinding(name: String): JSValue? {
        val binding = bindings[name]
        if (binding != null) {
            if (!binding.initialized) {
                throw JSException(JSError("ReferenceError", "Cannot access '$name' before initialization"))
            }
            return binding.value
        }
        return parent?.getBinding(name)
    }

    override suspend fun setBinding(name: String, value: JSValue, strict: Boolean): Boolean {
        val binding = bindings[name]
        if (binding != null) {
            if (!binding.initialized) {
                throw JSException(JSError("ReferenceError", "Cannot access '$name' before initialization"))
            }
            if (!binding.mutable) {
                // 规范 SetMutableBinding：严格绑定或严格代码下抛 TypeError，否则静默忽略
                if (binding.strict || strict) {
                    throw JSException(JSError("TypeError", "Assignment to constant variable."))
                }
                return true
            }
            binding.value = value
            return true
        }
        return parent?.setBinding(name, value, strict) ?: false
    }

    override fun createVarBinding(name: String, value: JSValue) {
        val existing = bindings[name]
        if (existing == null) {
            bindings[name] = Binding(value, mutable = true)
        } else if (existing.mutable) {
            existing.initialized = true
            existing.value = value
        }
    }

    override fun ensureVarBinding(name: String) {
        if (!bindings.containsKey(name)) {
            bindings[name] = Binding(JSUndefined, mutable = true)
        }
    }

    /** 新建可删除的 var 绑定（仅供 sloppy eval 的 VariableEnvironment 使用）。 */
    fun createDeletableVarBinding(name: String, value: JSValue) {
        val existing = bindings[name]
        if (existing == null) {
            bindings[name] = Binding(value, mutable = true, deletable = true)
        } else if (existing.mutable) {
            existing.value = value
        }
    }

    /** 确保可删除 var 绑定存在（初始化为 undefined），不覆盖已有绑定。 */
    fun ensureDeletableVarBinding(name: String) {
        if (!bindings.containsKey(name)) {
            bindings[name] = Binding(JSUndefined, mutable = true, deletable = true)
        }
    }

    /** 删除绑定：仅可删除绑定会被移除；返回是否成功删除。 */
    override fun deleteBinding(name: String): Boolean {
        val binding = bindings[name]
        if (binding == null) return parent?.deleteBinding(name) ?: false
        if (!binding.deletable) return false
        bindings.remove(name)
        return true
    }

    override fun createLetBinding(name: String, value: JSValue) {
        if (bindings.containsKey(name)) {
            throw JSException(JSError("SyntaxError", "Identifier '$name' has already been declared"))
        }
        bindings[name] = Binding(value, mutable = true, lexical = true)
    }

    override fun createConstBinding(name: String, value: JSValue) {
        if (bindings.containsKey(name)) {
            throw JSException(JSError("SyntaxError", "Identifier '$name' has already been declared"))
        }
        bindings[name] = Binding(value, mutable = false, lexical = true, strict = true)
    }

    /** 具名函数表达式的名字绑定：非严格不可变绑定（规范 CreateImmutableBinding(name, false)）。 */
    fun createFunctionNameBinding(name: String, value: JSValue) {
        if (bindings.containsKey(name)) {
            throw JSException(JSError("SyntaxError", "Identifier '$name' has already been declared"))
        }
        bindings[name] = Binding(value, mutable = false)
    }

    /** 创建未初始化的 let 绑定，用于 TDZ 期间的访问检查。 */
    fun declareUninitialized(name: String) {
        if (!bindings.containsKey(name)) {
            bindings[name] = Binding(JSUndefined, mutable = true, initialized = false)
        }
    }

    /**
     * 预先声明 let/const/class 绑定（暂时性死区）。
     *
     * 作用域内重复声明词法绑定属于语法错误。
     */
    fun declareLexical(name: String, mutable: Boolean) {
        if (bindings.containsKey(name)) {
            throw JSException(JSError("SyntaxError", "Identifier '$name' has already been declared"))
        }
        bindings[name] = Binding(
            JSUndefined,
            mutable = mutable,
            initialized = false,
            lexical = true,
            strict = !mutable,
        )
    }

    /** 当前作用域是否存在尚未初始化的绑定。 */
    fun hasUninitializedLocal(name: String): Boolean = bindings[name]?.initialized == false

    /** 当前作用域是否存在同名词法绑定（let/const/class）。 */
    fun hasOwnLexicalBinding(name: String): Boolean = bindings[name]?.lexical == true

    /** 当前作用域的全体词法绑定名（GlobalDeclarationInstantiation 冲突检查使用）。 */
    fun ownLexicalNames(): Set<String> =
        bindings.entries.filter { it.value.lexical }.mapTo(LinkedHashSet()) { it.key }

    /** 初始化通过 [declareLexical] 预声明的绑定。 */
    fun initializeLexical(name: String, value: JSValue) {
        val binding = bindings[name] ?: return
        binding.value = value
        binding.initialized = true
    }

    /** 初始化之前通过 [declareUninitialized] 创建的绑定。 */
    fun initializeBinding(name: String, value: JSValue) {
        val binding = bindings[name]
        if (binding != null) {
            binding.value = value
            binding.initialized = true
        } else {
            bindings[name] = Binding(value, mutable = true, lexical = true)
        }
    }

    /** 当前作用域是否已有同名绑定。 */
    fun hasLocal(name: String): Boolean = bindings.containsKey(name)
}

/**
 * 对象环境：变量绑定委托给对象的属性。
 *
 * 用于全局对象环境：全局 var/function 声明会成为全局对象属性，
 * 从而实现 `window.foo` / `globalThis.foo` 与变量联动。
 */
class ObjectEnvironment(
    val bindingObject: JSObject,
    parent: Environment? = null,
    private val access: PropertyAccess,
    override val isVarScope: Boolean = false,
    /** with 环境传入 @@unscopables 键；全局对象环境为 null */
    private val unscopablesKey: PropertyKey? = null,
) : Environment(parent) {

    /**
     * with 环境中同名方法被 @@unscopables 标记时不可绑定。
     *
     * 按规范通过 Get 读取（可能触发 getter）；非对象值不参与屏蔽。
     */
    private suspend fun isUnscopable(name: String): Boolean {
        val key = unscopablesKey ?: return false
        val unscopables = access.getProperty(bindingObject, key)
        if (unscopables !is JSObject) return false
        return access.getProperty(unscopables, PropertyKey.Str(name)).toPrimitiveBoolean()
    }

    override fun hasBinding(name: String): Boolean =
        bindingObject.hasProperty(PropertyKey.Str(name)) || super.hasBinding(name)

    /** WithBaseObject：with 环境的绑定对象（全局对象环境返回 null）。 */
    fun withBaseObject(): JSObject? =
        if (unscopablesKey != null) bindingObject else null

    override fun hasOwnBinding(name: String): Boolean =
        bindingObject.hasProperty(PropertyKey.Str(name))

    /** 标识符引用解析：先 HasProperty，再（with 环境）检查 @@unscopables。 */
    override suspend fun hasBindingForReference(name: String): Boolean {
        if (unscopablesKey == null) return hasOwnBinding(name)
        val key = PropertyKey.Str(name)
        if (!bindingObject.hasProperty(key)) return false
        return !isUnscopable(name)
    }

    override suspend fun getBinding(name: String): JSValue? {
        if (unscopablesKey == null) {
            val legacyKey = PropertyKey.Str(name)
            return if (bindingObject.hasProperty(legacyKey)) {
                access.getProperty(bindingObject, legacyKey)
            } else {
                parent?.getBinding(name)
            }
        }
        val key = PropertyKey.Str(name)
        if (!bindingObject.hasProperty(key)) return parent?.getBinding(name)
        if (isUnscopable(name)) return parent?.getBinding(name)
        // GetBindingValue：unscopables getter 可能删除绑定
        if (!bindingObject.hasProperty(key)) {
            if (access.isStrictContext()) {
                throw JSException(JSError("ReferenceError", "$name is not defined"))
            }
            return JSUndefined
        }
        return access.getProperty(bindingObject, key)
    }

    override suspend fun setBinding(name: String, value: JSValue, strict: Boolean): Boolean {
        val key = PropertyKey.Str(name)
        if (!bindingObject.hasProperty(key)) {
            return parent?.setBinding(name, value, strict) ?: false
        }
        if (unscopablesKey != null && isUnscopable(name)) {
            return parent?.setBinding(name, value, strict) ?: false
        }
        if (!bindingObject.hasProperty(key) && strict) {
            throw JSException(JSError("ReferenceError", "$name is not defined"))
        }
        access.setProperty(bindingObject, key, value, strict)
        return true
    }

    override fun createVarBinding(name: String, value: JSValue) {
        val key = PropertyKey.Str(name)
        val existing = bindingObject.getOwnProperty(key)
        if (existing == null) {
            bindingObject.defineDataProperty(
                key = key,
                value = value,
                writable = true,
                enumerable = true,
                configurable = false,
            )
        } else if (existing.isData && existing.writable) {
            existing.value = value
        }
    }

    override fun ensureVarBinding(name: String) {
        val key = PropertyKey.Str(name)
        if (bindingObject.getOwnProperty(key) == null) {
            bindingObject.defineDataProperty(
                key = key,
                value = JSUndefined,
                writable = true,
                enumerable = true,
                configurable = false,
            )
        }
    }

    /**
     * CreateGlobalFunctionBinding：创建/更新全局函数绑定。
     *
     * 已存在的可配置属性按数据属性重定义；不可配置但可写的数据属性只更新值。
     * [deletable] 对应规范的 D 参数（间接/直接 eval 为 true，脚本为 false）。
     */
    fun createGlobalFunctionBinding(name: String, value: JSValue, deletable: Boolean = false) {
        val key = PropertyKey.Str(name)
        val existing = bindingObject.getOwnProperty(key)
        if (existing == null || existing.configurable) {
            bindingObject.defineOwnProperty(
                key,
                PropertyDescriptor.data(
                    value,
                    writable = true,
                    enumerable = true,
                    configurable = deletable,
                ),
            )
        } else if (existing.isData && existing.writable) {
            existing.value = value
        }
    }

    /**
     * CreateGlobalVarBinding：全局 var 绑定。
     *
     * 属性已存在时保持原值/描述符；不存在时创建可写、可枚举的 undefined 属性。
     */
    fun createGlobalVarBinding(name: String, deletable: Boolean = false) {
        val key = PropertyKey.Str(name)
        if (bindingObject.getOwnProperty(key) == null) {
            bindingObject.defineOwnProperty(
                key,
                PropertyDescriptor.data(
                    JSUndefined,
                    writable = true,
                    enumerable = true,
                    configurable = deletable,
                ),
            )
        }
    }

    override fun deleteBinding(name: String): Boolean =
        bindingObject.deleteOwnProperty(PropertyKey.Str(name))

    /** 创建隐式全局变量（非严格模式下对未声明标识符赋值）。 */
    fun createImplicitGlobal(name: String, value: JSValue) {
        bindingObject.defineDataProperty(
            key = PropertyKey.Str(name),
            value = value,
            writable = true,
            enumerable = true,
            configurable = true,
        )
    }
}
