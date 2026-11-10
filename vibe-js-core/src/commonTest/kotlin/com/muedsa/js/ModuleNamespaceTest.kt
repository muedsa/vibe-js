package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleDeclarationInstantiator
import com.muedsa.js.runtime.module.internal.ModuleGraph
import com.muedsa.js.runtime.module.internal.ModuleGraphLoader
import com.muedsa.js.runtime.module.internal.ModuleLinker
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 阶段 6：namespace exotic 对象、实时数据属性、Symbol.toStringTag 与嵌套 namespace。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleNamespaceTest {

    private class Provider(
        private val sources: Map<String, ModuleSource>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? = sources[request.specifier]
    }

    private suspend fun graph(
        entry: ModuleSource,
        sources: Map<String, ModuleSource> = emptyMap(),
    ): ModuleGraph = ModuleGraphLoader(EngineLimits()).load(entry, Provider(sources))

    private fun source(id: String, code: String) = ModuleSource(id, code)

    @Test
    fun `namespace 是空原型不可扩展对象且键排序`() = runTest {
        val engine = Engine()
        val graph = graph(
            source("a", "export const x = 1; export * as ns from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const y = 2;")),
        )
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        instantiator.instantiate(graph.records.getValue("b"))
        instantiator.instantiate(graph.entry)
        val namespace = assertNotNull(graph.entry.namespace)

        assertEquals(null, namespace.prototype)
        assertFalse(namespace.isExtensible())
        assertEquals(
            listOf("ns", "x"),
            namespace.ownPropertyKeys()
                .filterIsInstance<PropertyKey.Str>()
                .map { it.value },
        )
        assertEquals(1, namespace.ownPropertyKeys().count { it is PropertyKey.Sym })
    }

    @Test
    fun `导出名描述符是实时数据属性且 Symbol_toStringTag 正确`() = runTest {
        val engine = Engine()
        val graph = graph(source("a", "export let x = 1;"))
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        val environment = instantiator.instantiate(graph.entry)
        environment.initializeLexical("x", JSNumber(1.0))
        val namespace = assertNotNull(graph.entry.namespace)

        val descriptor = assertNotNull(namespace.getOwnProperty(PropertyKey.Str("x")))
        assertEquals(1.0, (descriptor.value as JSNumber).value)
        assertTrue(descriptor.writable)
        assertTrue(descriptor.enumerable)
        assertFalse(descriptor.configurable)

        environment.setBinding("x", JSNumber(2.0), strict = true)
        val updated = assertNotNull(namespace.getOwnProperty(PropertyKey.Str("x")))
        assertEquals(2.0, (updated.value as JSNumber).value)

        val tag = assertNotNull(
            namespace.getOwnProperty(PropertyKey.Sym(engine.intrinsics.symbolToStringTag)),
        )
        assertEquals("Module", (tag.value as JSString).value)
        assertFalse(tag.writable)
        assertFalse(tag.enumerable)
        assertFalse(tag.configurable)
    }

    @Test
    fun `namespace 写删重定义全部被拒绝`() = runTest {
        val engine = Engine()
        val graph = graph(source("a", "export let x = 1;"))
        ModuleLinker.link(graph)
        val environment = ModuleDeclarationInstantiator(engine.interpreter).instantiate(graph.entry)
        environment.initializeLexical("x", JSNumber(1.0))
        val namespace = assertNotNull(graph.entry.namespace)

        assertFailsWith<JSException> {
            engine.interpreter.setProperty(namespace, PropertyKey.Str("x"), JSNumber(2.0), strict = true)
        }
        assertFalse(namespace.deleteOwnProperty(PropertyKey.Str("x")))
        assertFalse(
            namespace.defineOwnProperty(
                PropertyKey.Str("x"),
                PropertyDescriptor.data(JSNumber(3.0)),
            ),
        )
    }

    @Test
    fun `export 星号 as 的嵌套 namespace 同模块缓存`() = runTest {
        val engine = Engine()
        val graph = graph(
            source("a", "export * as ns from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const y = 2;")),
        )
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        val bEnvironment = instantiator.instantiate(graph.records.getValue("b"))
        bEnvironment.initializeLexical("y", JSNumber(2.0))
        instantiator.instantiate(graph.entry)
        val aNamespace = assertNotNull(graph.entry.namespace)
        val bNamespace = assertNotNull(graph.records.getValue("b").namespace)

        val nsDescriptor = assertNotNull(aNamespace.getOwnProperty(PropertyKey.Str("ns")))
        assertSame(bNamespace, nsDescriptor.value)
        val nestedY = assertNotNull(bNamespace.getOwnProperty(PropertyKey.Str("y")))
        assertEquals(2.0, (nestedY.value as JSNumber).value)
    }

    @Test
    fun `星号歧义访问 namespace 属性抛 SyntaxError`() = runTest {
        val engine = Engine()
        val graph = graph(
            source("a", "export * from \"./b.js\"; export * from \"./c.js\";"),
            mapOf(
                "./b.js" to source("b", "export const x = 1;"),
                "./c.js" to source("c", "export const x = 2;"),
            ),
        )
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        instantiator.instantiate(graph.records.getValue("b"))
        instantiator.instantiate(graph.records.getValue("c"))
        instantiator.instantiate(graph.entry)
        val namespace = assertNotNull(graph.entry.namespace)

        assertFailsWith<JSException> {
            namespace.getOwnProperty(PropertyKey.Str("x"))
        }
    }
}
