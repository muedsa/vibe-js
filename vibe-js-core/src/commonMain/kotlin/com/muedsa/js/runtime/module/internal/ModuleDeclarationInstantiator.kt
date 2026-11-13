@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ModuleBindingKind
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 模块声明实例化（ES2024 ModuleDeclarationInstantiation 的同步子集）。
 *
 * 只创建环境、函数对象和未初始化词法绑定，不执行任何用户语句。输入来自
 * Parser 预计算的 `ModuleProgram.bindings` / `varDeclarations` /
 * `functionDeclarations`，运行时不再扫描 AST。
 */
internal class ModuleDeclarationInstantiator(
    private val interpreter: Interpreter,
) {

    fun instantiate(record: ModuleRecord): ModuleEnvironment {
        val environment = ModuleEnvironment(
            parent = interpreter.context.globalEnvironment,
            record = record,
        )

        // 模块顶层 var 属于模块环境，不进入 globalThis。
        for (name in record.program.varDeclarations) {
            environment.ensureVarBinding(name)
        }

        // 顶层 function 在模块中是词法声明；先创建函数对象并初始化绑定。
        for (declaration in record.program.functionDeclarations) {
            val function = interpreter.createFunction(
                // 匿名 `export default function` 的绑定名是内部的 `*default*`，
                // 但函数对象自身的 name 必须是 "default"。
                name = if (declaration.name == "*default*") "default" else declaration.name,
                params = declaration.params,
                body = declaration.body,
                closure = environment,
                strict = true,
                varDeclarations = declaration.varDeclarations,
                functionDeclarations = declaration.functionDeclarations,
                isAsync = declaration.isAsync,
                isGenerator = declaration.isGenerator,
                sourceText = declaration.sourceText,
                sourceInfo = SourceInfo(record.source.sourceName),
            )
            // 声明实例化发生在模块体求值之前，currentModuleRecord 尚未设置，
            // 因此显式把所属模块写到函数上，供延迟回调使用 import.meta / import()。
            function.sourceModule = record
            environment.createLetBinding(declaration.name, function)
        }

        for (binding in record.program.bindings) {
            when (binding.kind) {
                ModuleBindingKind.IMPORT -> Unit // 由 ModuleEnvironment 直接转发
                ModuleBindingKind.VAR -> environment.ensureVarBinding(binding.name)
                ModuleBindingKind.FUNCTION -> Unit // 已在上面创建
                ModuleBindingKind.LET -> environment.declareLexical(binding.name, mutable = true)
                ModuleBindingKind.USING -> environment.declareLexical(binding.name, mutable = true)
                ModuleBindingKind.CONST -> environment.declareLexical(binding.name, mutable = false)
                // 模块顶层 class 是可变词法绑定；只有 const 不可重新赋值。
                ModuleBindingKind.CLASS -> environment.declareLexical(binding.name, mutable = true)

                ModuleBindingKind.DEFAULT -> environment.declareLexical(binding.name, mutable = true)
            }
        }

        record.environment = environment
        if (record.moduleSourceObject == null) {
            record.source.moduleSourceClassName?.let { className ->
                record.moduleSourceObject = interpreter.intrinsics
                    .createModuleSourceObject(className)
                    .attachAllocationBudget(interpreter.context.objectAllocations)
            }
        }
        if (record.namespace == null) {
            record.namespace = JSModuleNamespace(
                record = record,
                symbolToStringTag = interpreter.intrinsics.symbolToStringTag,
                interpreter = interpreter,
            ).attachAllocationBudget(interpreter.context.objectAllocations)
        }
        if (record.deferredNamespace == null) {
            record.deferredNamespace = JSDeferredModuleNamespace(
                record = record,
                symbolToStringTag = interpreter.intrinsics.symbolToStringTag,
                interpreter = interpreter,
            ).attachAllocationBudget(interpreter.context.objectAllocations)
        }
        if (record.importMeta == null) {
            val importMeta = JSObject(prototype = null)
            importMeta.defineDataProperty(
                key = PropertyKey.Str("url"),
                value = JSString(record.source.url ?: "module:${record.id}"),
                writable = true,
                enumerable = true,
                configurable = true,
            )
            importMeta.defineDataProperty(
                key = PropertyKey.Str("resolve"),
                value = JSNativeFunction(
                    name = "resolve",
                    length = 1,
                    prototype = interpreter.intrinsics.functionPrototype,
                ) { inner, _, args ->
                    val current = inner.resolveModuleRecord()
                        ?: inner.typeError("import.meta.resolve is only available during module evaluation")
                    val runtime = current.runtime ?: inner.moduleRuntime
                        ?: inner.typeError("import.meta.resolve is only available during module evaluation")
                    val specifier = inner.toStringValue(args.getOrElse(0) { JSUndefined })
                    val resolved = runtime.resolve(specifier, current)
                        ?: inner.typeError("Cannot resolve module specifier '$specifier'")
                    JSString(resolved)
                },
                writable = true,
                enumerable = true,
                configurable = true,
            )
            record.importMeta = importMeta.attachAllocationBudget(interpreter.context.objectAllocations)
        }
        return environment
    }
}
