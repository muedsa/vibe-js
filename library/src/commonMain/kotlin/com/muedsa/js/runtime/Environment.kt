package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue

class Environment(
    val parent: Environment? = null,
    val isVarScope: Boolean = false,
) {
    private val variables = mutableMapOf<String, VariableSlot>()

    /**
     * 定义变量
     * @param name 变量名
     * @param value 初始值
     * @param kind 声明类型 (var, let, const)
     */
    fun define(name: String, value: JSValue, kind: VariableKind) {
        // 确定目标环境：
        // let/const 定义在当前环境
        // var 定义在最近的函数作用域/全局环境
        val targetEnv = if (kind == VariableKind.VAR) getNearestVarScope() else this

        // 检查重复声明 (Re-declaration)
        // 规则：
        // 1. 同一作用域内，let/const 不能重名
        // 2. var 可以重复声明 var，但不能覆盖 let/const (虽然 let/const 不会出现在 var 查找路径的同一层，但需防御)
        val existing = targetEnv.variables[name]
        if (existing != null) {
            if (kind != VariableKind.VAR || existing.kind != VariableKind.VAR) {
                // JS 抛出 SyntaxError，这里简化为 JSException
                throw JSException(JSError("SyntaxError", "Identifier '$name' has already been declared"))
            }
            // var 覆盖 var：更新值
            existing.value = value
        } else {
            // 新定义
            targetEnv.variables[name] = VariableSlot(value, kind)
        }
    }

    /**
     * 赋值操作
     */
    fun assign(name: String, value: JSValue) {
        val env = resolve(name)
        if (env != null) {
            val slot = env.variables[name]!!
            // const 检查
            if (slot.kind == VariableKind.CONST) {
                throw JSException(JSError("TypeError", "Assignment to constant variable."))
            }
            slot.value = value
        } else {
            // 严格模式下应该抛错，非严格模式下自动定义全局 var
            // 这里为了简化，我们沿用非严格模式：在全局定义
            val global = getGlobalScope()
            global.define(name, value, VariableKind.VAR)
        }
    }

    fun get(name: String): JSValue {
        val env = resolve(name)
        if (env == null) {
            throw JSException(JSError("ReferenceError", "$name is not defined"))
        }
        return env.variables[name]?.value ?: JSUndefined
    }

    /**
     * 安全获取变量值，如果不存在则返回 JSUndefined 而不抛出异常
     */
    fun getSafe(name: String): JSValue {
        val env = resolve(name)
        return env?.variables?.get(name)?.value ?: JSUndefined
    }

    // 查找变量定义所在的具体环境
    private fun resolve(name: String): Environment? {
        if (variables.containsKey(name)) return this
        return parent?.resolve(name)
    }

    // 获取最近的 var 作用域 (函数作用域或全局作用域)
    private fun getNearestVarScope(): Environment {
        return if (isVarScope || parent == null) this else parent.getNearestVarScope()
    }

    // 获取全局作用域
    private fun getGlobalScope(): Environment {
        return parent?.getGlobalScope() ?: this
    }

    fun getThis(): JSValue = get("this")

    /**
     * 获取所有定义在当前作用域的变量（不包括父作用域）
     */
    fun getLocalVariables(): Map<String, JSValue> = variables.mapValues { it.value.value }

    /**
     * 获取所有变量（包括从父作用域继承的）
     */
    fun getAllVariables(): Map<String, JSValue> {
        val all = mutableMapOf<String, JSValue>()
        var env: Environment? = this
        while (env != null) {
            env.variables.forEach { (k, v) ->
                if (!all.containsKey(k)) all[k] = v.value
            }
            env = env.parent
        }
        return all
    }

    fun hasLocal(name: String): Boolean {
        return variables.containsKey(name)
    }

    fun removeLocal(name: String) {
        variables.remove(name)
    }

    fun dumpEnv(indent: String = ""): String {
        val sb = StringBuilder()
        sb.append(indent).append("--- Environment (isVarScope: $isVarScope) ---\n")
        if (variables.isEmpty()) {
            sb.append(indent).append("  <empty>\n")
        }
        else {
            variables.forEach { (name, slot) ->
                val valueStr = try {
                    slot.value.toPrimitiveString()
                }
                catch (e: Exception) {
                    "Error getting value: ${e.message}"
                }
                sb.append(indent).append("  ${slot.kind.name.lowercase()} $name = $valueStr\n")
            }
        }
        parent?.let {
            sb.append(it.dumpEnv("$indent  "))
        }
        return sb.toString()
    }
}