package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `import defer` 的延迟求值、namespace 身份与基础触发边界。 */
@OptIn(ExperimentalModuleApi::class)
class ImportDeferTest {

    private class Provider(
        private val sources: Map<String, String>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? =
            sources[request.specifier]?.let {
                ModuleSource(id = request.specifier, source = it)
            }
    }

    private suspend fun engineWith(
        entry: String,
        vararg dependencies: Pair<String, String>,
    ): Pair<Engine, JSObject> {
        val engine = Engine()
        val provider = Provider(mapOf("entry" to entry, *dependencies))
        return engine to engine.runModule("entry", provider)
    }

    @Test
    fun `import defer 在首次访问时同步求值`() = runTest {
        val (engine, namespace) = engineWith(
            entry = """
                import defer * as ns from "./dep.js";
                export const before = globalThis.trace ? globalThis.trace.length : 0;
                export const value = ns.x;
                export const after = globalThis.trace.length;
            """.trimIndent(),
            "./dep.js" to """
                globalThis.trace = globalThis.trace || [];
                globalThis.trace.push("dep");
                export const x = 1;
            """.trimIndent(),
        )
        assertEquals(0.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("before")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("after")) as JSNumber).value)
    }

    @Test
    fun `then 与原型访问不触发延迟求值`() = runTest {
        val (engine, namespace) = engineWith(
            entry = """
                import defer * as ns from "./dep.js";
                globalThis.trace = [];
                const thenValue = ns.then;
                const prototype = Object.getPrototypeOf(ns);
                export const before = globalThis.trace.length;
                export const thenIsUndefined = thenValue === undefined;
                export const prototypeIsNull = prototype === null;
                export const x = ns.x;
                export const after = globalThis.trace.length;
            """.trimIndent(),
            "./dep.js" to """
                globalThis.trace = globalThis.trace || [];
                globalThis.trace.push("dep");
                export const x = 1;
            """.trimIndent(),
        )
        assertEquals(0.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("before")) as JSNumber).value)
        assertEquals(true, (engine.interpreter.getProperty(namespace, PropertyKey.Str("thenIsUndefined")) as com.muedsa.js.runtime.value.JSBoolean).value)
        assertEquals(true, (engine.interpreter.getProperty(namespace, PropertyKey.Str("prototypeIsNull")) as com.muedsa.js.runtime.value.JSBoolean).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("after")) as JSNumber).value)
    }

    @Test
    fun `deferred namespace 与 eager namespace 不同且共享模块记录`() = runTest {
        val (engine, namespace) = engineWith(
            entry = """
                import * as eager from "./dep.js";
                import defer * as lazy from "./dep.js";
                export const same = eager === lazy;
                export const eagerX = eager.x;
                export const lazyX = lazy.x;
            """.trimIndent(),
            "./dep.js" to """
                globalThis.trace = globalThis.trace || [];
                globalThis.trace.push("dep");
                export const x = 1;
            """.trimIndent(),
        )
        assertEquals(false, (engine.interpreter.getProperty(namespace, PropertyKey.Str("same")) as com.muedsa.js.runtime.value.JSBoolean).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("eagerX")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("lazyX")) as JSNumber).value)
    }
}
