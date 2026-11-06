package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSGenerator
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * %AsyncGeneratorFunction% 构造器、%AsyncGeneratorFunction.prototype%
 * 与 %AsyncGeneratorPrototype%（ES2018 27.6）。
 *
 * 与同步生成器的结构一一对应，但 `next`/`return`/`throw` 返回 Promise：
 * - async generator 函数的 [[Prototype]] 是 %AsyncGeneratorFunction.prototype%；
 * - 其 `.prototype` 对象的 [[Prototype]] 是 %AsyncGeneratorPrototype%；
 * - %AsyncGeneratorPrototype% 的 [[Prototype]] 是 %AsyncIteratorPrototype%。
 */
object AsyncGeneratorBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val asyncGeneratorPrototype = intrinsics.asyncGeneratorPrototype
        val asyncGeneratorFunctionPrototype = intrinsics.asyncGeneratorFunctionPrototype

        // ---------------- %AsyncGeneratorPrototype% ----------------

        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = BuiltinHelpers.method(intrinsics, "next", 1) { interpreter, thisValue, args ->
                requireAsyncGenerator(interpreter, thisValue)
                    .nextAsync(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.Str("return"),
            value = BuiltinHelpers.method(intrinsics, "return", 1) { interpreter, thisValue, args ->
                requireAsyncGenerator(interpreter, thisValue)
                    .returnAsync(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.Str("throw"),
            value = BuiltinHelpers.method(intrinsics, "throw", 1) { interpreter, thisValue, args ->
                requireAsyncGenerator(interpreter, thisValue)
                    .throwAsync(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // 规范：%AsyncGeneratorPrototype%[@@asyncIterator] 返回 this（自身即可异步迭代）
        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolAsyncIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.asyncIterator]", 0) { _, thisValue, _ ->
                thisValue
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("AsyncGenerator"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        // %AsyncGeneratorPrototype%.constructor === %AsyncGeneratorFunction.prototype%
        asyncGeneratorPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = asyncGeneratorFunctionPrototype,
            writable = false,
            enumerable = false,
            configurable = true,
        )

        // ---------------- %AsyncGeneratorFunction% ----------------

        val asyncGeneratorFunctionConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "AsyncGeneratorFunction",
            length = 1,
            prototypeObject = asyncGeneratorFunctionPrototype,
        ) { interpreter, _, args, newTarget ->
            // 与 Function/GeneratorFunction 构造器相同的参数与函数体转换顺序
            val params = if (args.size > 1) {
                args.dropLast(1).map { interpreter.toStringValue(it) }
            } else {
                emptyList()
            }
            val body = if (args.isEmpty()) "" else interpreter.toStringValue(args.last())
            interpreter.createDynamicAsyncGeneratorFunction(
                params,
                body,
                interpreter.prototypeFromNewTarget(newTarget, asyncGeneratorFunctionPrototype),
            )
        }
        // 规范：%AsyncGeneratorFunction% 的 [[Prototype]] 是 %Function%
        (intrinsics.functionConstructor as? JSObject)?.let {
            asyncGeneratorFunctionConstructor.prototype = it
        }
        intrinsics.asyncGeneratorFunctionConstructor = asyncGeneratorFunctionConstructor

        // ---------------- %AsyncGeneratorFunction.prototype% ----------------

        // 规范：constructor 不可写、不可枚举、可配置
        asyncGeneratorFunctionPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = asyncGeneratorFunctionConstructor,
            writable = false,
            enumerable = false,
            configurable = true,
        )
        // 规范：prototype 指向 %AsyncGeneratorPrototype%，不可写、不可配置
        asyncGeneratorFunctionPrototype.defineDataProperty(
            key = PropertyKey.PROTOTYPE,
            value = asyncGeneratorPrototype,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        asyncGeneratorFunctionPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("AsyncGeneratorFunction"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
    }

    /** AsyncGenerator.prototype 方法的品牌检查：接收者必须是 async generator 对象。 */
    private fun requireAsyncGenerator(interpreter: Interpreter, thisValue: JSValue): JSGenerator {
        val generator = thisValue as? JSGenerator
        if (generator == null || !generator.isAsync) {
            interpreter.typeError("Method called on incompatible receiver")
        }
        return generator
    }
}
