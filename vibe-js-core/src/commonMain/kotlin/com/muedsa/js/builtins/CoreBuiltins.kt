package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry

/**
 * ECMAScript 标准内建模块的安装入口。
 *
 * 安装顺序有依赖关系：
 * Object -> Function -> Array -> String -> Number -> Boolean
 * -> Error -> Math -> JSON -> Global。
 */
object CoreBuiltins {

    fun install(registry: BuiltinRegistry) {
        ObjectBuiltins.install(registry)
        FunctionBuiltins.install(registry)
        AsyncFunctionBuiltins.install(registry)
        SymbolBuiltins.install(registry)
        IteratorBuiltins.install(registry)
        ArrayBuiltins.install(registry)
        StringBuiltins.install(registry)
        NumberBuiltins.install(registry)
        BooleanBuiltins.install(registry)
        RegExpBuiltins.install(registry)
        DateBuiltins.install(registry)
        MapBuiltins.install(registry)
        SetBuiltins.install(registry)
        WeakMapBuiltins.install(registry)
        WeakSetBuiltins.install(registry)
        PromiseBuiltins.install(registry)
        ErrorBuiltins.install(registry)
        MathBuiltins.install(registry)
        JsonBuiltins.install(registry)
        GlobalBuiltins.install(registry)
    }
}
