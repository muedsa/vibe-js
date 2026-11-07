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
 * %GeneratorFunction% 构造器、%GeneratorFunction.prototype% 与 %GeneratorPrototype%。
 *
 * 三者都不是全局对象：
 * - 生成器函数的 [[Prototype]] 是 %GeneratorFunction.prototype%；
 * - 生成器函数的 `.prototype` 对象的 [[Prototype]] 是 %GeneratorPrototype%；
 * - %GeneratorPrototype% 的 [[Prototype]] 是 %IteratorPrototype%。
 */
object GeneratorBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val generatorPrototype = intrinsics.generatorPrototype
        val generatorFunctionPrototype = intrinsics.generatorFunctionPrototype

        // ---------------- %GeneratorPrototype% ----------------

        generatorPrototype.defineDataProperty(
            key = PropertyKey.Str("next"),
            value = BuiltinHelpers.method(intrinsics, "next", 1) { interpreter, thisValue, args ->
                requireGenerator(interpreter, thisValue)
                    .next(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        generatorPrototype.defineDataProperty(
            key = PropertyKey.Str("return"),
            value = BuiltinHelpers.method(intrinsics, "return", 1) { interpreter, thisValue, args ->
                requireGenerator(interpreter, thisValue)
                    .returnValue(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        generatorPrototype.defineDataProperty(
            key = PropertyKey.Str("throw"),
            value = BuiltinHelpers.method(intrinsics, "throw", 1) { interpreter, thisValue, args ->
                requireGenerator(interpreter, thisValue)
                    .throwValue(args.getOrElse(0) { JSUndefined })
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // %GeneratorPrototype%[@@iterator] 返回 this，使生成器对象自身可迭代
        generatorPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { _, thisValue, _ ->
                thisValue
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        generatorPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Generator"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        // %GeneratorPrototype%.constructor === %GeneratorFunction.prototype%
        generatorPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = generatorFunctionPrototype,
            writable = false,
            enumerable = false,
            configurable = true,
        )

        // ---------------- %GeneratorFunction% ----------------

        val generatorFunctionConstructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "GeneratorFunction",
            length = 1,
            prototypeObject = generatorFunctionPrototype,
        ) { interpreter, _, args, newTarget ->
            // 与 Function/AsyncFunction 构造器相同的参数与函数体转换顺序
            val params = if (args.size > 1) {
                args.dropLast(1).map { interpreter.toStringValue(it) }
            } else {
                emptyList()
            }
            val body = if (args.isEmpty()) "" else interpreter.toStringValue(args.last())
            interpreter.createDynamicGeneratorFunction(
                params,
                body,
                interpreter.prototypeFromNewTarget(newTarget, generatorFunctionPrototype),
            )
        }
        // 规范：%GeneratorFunction% 的 [[Prototype]] 是 %Function%
        (intrinsics.functionConstructor as? JSObject)?.let {
            generatorFunctionConstructor.prototype = it
        }
        intrinsics.generatorFunctionConstructor = generatorFunctionConstructor

        // ---------------- %GeneratorFunction.prototype% ----------------

        // 规范：constructor 不可写、不可枚举、可配置
        generatorFunctionPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = generatorFunctionConstructor,
            writable = false,
            enumerable = false,
            configurable = true,
        )
        // 规范：prototype 指向 %GeneratorPrototype%，不可写、不可枚举、可配置
        generatorFunctionPrototype.defineDataProperty(
            key = PropertyKey.PROTOTYPE,
            value = generatorPrototype,
            writable = false,
            enumerable = false,
            configurable = true,
        )
        generatorFunctionPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("GeneratorFunction"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
    }

    /** Generator.prototype 方法的品牌检查：接收者必须是同步生成器对象。 */
    private fun requireGenerator(interpreter: Interpreter, thisValue: JSValue): JSGenerator {
        val generator = thisValue as? JSGenerator
        // async generator 是另一条原型链，不能借用同步 Generator 的方法
        if (generator == null || generator.isAsync) {
            interpreter.typeError("Method called on incompatible receiver")
        }
        return generator
    }
}
