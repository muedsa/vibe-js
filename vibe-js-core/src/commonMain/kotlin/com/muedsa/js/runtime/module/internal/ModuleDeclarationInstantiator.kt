@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ModuleBindingKind
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
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
                name = declaration.name,
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
            environment.createLetBinding(declaration.name, function)
        }

        for (binding in record.program.bindings) {
            when (binding.kind) {
                ModuleBindingKind.IMPORT -> Unit // 由 ModuleEnvironment 直接转发
                ModuleBindingKind.VAR -> environment.ensureVarBinding(binding.name)
                ModuleBindingKind.FUNCTION -> Unit // 已在上面创建
                ModuleBindingKind.LET -> environment.declareLexical(binding.name, mutable = true)
                ModuleBindingKind.USING -> environment.declareLexical(binding.name, mutable = true)
                ModuleBindingKind.CONST,
                ModuleBindingKind.CLASS,
                -> environment.declareLexical(binding.name, mutable = false)

                ModuleBindingKind.DEFAULT -> environment.declareLexical(binding.name, mutable = true)
            }
        }

        record.environment = environment
        if (record.namespace == null) {
            record.namespace = JSModuleNamespace(
                record = record,
                symbolToStringTag = interpreter.intrinsics.symbolToStringTag,
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
            record.importMeta = importMeta.attachAllocationBudget(interpreter.context.objectAllocations)
        }
        return environment
    }
}
