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
        ProxyBuiltins.install(registry)
        ReflectBuiltins.install(registry)
        FunctionBuiltins.install(registry)
        AsyncFunctionBuiltins.install(registry)
        // 生成器内建依赖 Function.prototype 与 %IteratorPrototype%
        GeneratorBuiltins.install(registry)
        // async generator 内建依赖 %AsyncIteratorPrototype% 与 Function.prototype
        AsyncGeneratorBuiltins.install(registry)
        SymbolBuiltins.install(registry)
        IteratorBuiltins.install(registry)
        ArrayBuiltins.install(registry)
        // ArrayBuffer 需要 Symbol.species/toStringTag 与 Function.prototype
        ArrayBufferBuiltins.install(registry)
        // DataView 依赖 %ArrayBuffer% 的内部数据槽
        DataViewBuiltins.install(registry)
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
        DisposableStackBuiltins.install(registry)
        AsyncDisposableStackBuiltins.install(registry)
        MathBuiltins.install(registry)
        JsonBuiltins.install(registry)
        GlobalBuiltins.install(registry)
    }
}
