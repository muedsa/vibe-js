package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
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

    /** 创建未初始化的 let 绑定（为完整 TDZ 预留在后续阶段使用）。 */
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
) : Environment(parent) {

    override fun hasBinding(name: String): Boolean =
        bindingObject.hasProperty(PropertyKey.Str(name)) || super.hasBinding(name)

    override fun hasOwnBinding(name: String): Boolean =
        bindingObject.hasProperty(PropertyKey.Str(name))

    override suspend fun getBinding(name: String): JSValue? {
        val key = PropertyKey.Str(name)
        return if (bindingObject.hasProperty(key)) {
            access.getProperty(bindingObject, key)
        } else {
            parent?.getBinding(name)
        }
    }

    override suspend fun setBinding(name: String, value: JSValue, strict: Boolean): Boolean {
        val key = PropertyKey.Str(name)
        if (bindingObject.hasProperty(key)) {
            access.setProperty(bindingObject, key, value, strict)
            return true
        }
        return parent?.setBinding(name, value, strict) ?: false
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
