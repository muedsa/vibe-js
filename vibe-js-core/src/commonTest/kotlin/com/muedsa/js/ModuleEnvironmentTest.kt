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
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 阶段 5：ModuleEnvironment、声明实例化、import 转发与 TDZ。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleEnvironmentTest {

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
    fun `声明实例化创建 var 函数与词法 TDZ 但不执行模块体`() = runTest {
        val engine = Engine()
        val graph = graph(
            source(
                "a",
                """
                var v;
                let l;
                const c = 1;
                class K {}
                function f() {}
                """.trimIndent(),
            ),
        )
        ModuleLinker.link(graph)
        val environment = ModuleDeclarationInstantiator(engine.interpreter).instantiate(graph.entry)

        // var 已初始化为 undefined；模块环境是 var 作用域，不写入 globalThis。
        assertEquals(JSUndefined, environment.getBinding("v"))
        assertNull(engine.context.globalEnvironment.getBinding("v"))

        // let/const/class 在求值前处于 TDZ。
        assertFailsWith<JSException> { environment.getBinding("l") }
        assertFailsWith<JSException> { environment.getBinding("c") }
        assertFailsWith<JSException> { environment.getBinding("K") }

        // function 声明已创建函数对象。
        val function = environment.getBinding("f")
        assertNotNull(function)
        assertTrue(function is JSFunction)

        // 模块体没有执行：const/class 仍未初始化。
        assertFailsWith<JSException> { environment.getBinding("c") }
    }

    @Test
    fun `具名 import 是只读 live binding`() = runTest {
        val engine = Engine()
        val graph = graph(
            source("a", "import { x } from \"./b.js\"; export const a = x;"),
            mapOf("./b.js" to source("b", "export let x = 1;")),
        )
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        val b = graph.records.getValue("b")
        val a = graph.entry
        val bEnvironment = instantiator.instantiate(b)
        val aEnvironment = instantiator.instantiate(a)

        bEnvironment.initializeLexical("x", JSNumber(1.0))
        assertEquals(1.0, (aEnvironment.getBinding("x") as JSNumber).value)

        // 目标绑定变化后，import 读取实时值。
        bEnvironment.setBinding("x", JSNumber(2.0), strict = true)
        assertEquals(2.0, (aEnvironment.getBinding("x") as JSNumber).value)

        // import 本地名只读且不可删除。
        assertFailsWith<JSException> { aEnvironment.setBinding("x", JSNumber(3.0), strict = true) }
        assertEquals(false, aEnvironment.deleteBinding("x"))
    }

    @Test
    fun `命名空间 import 建立只读绑定但不创建 namespace 对象`() = runTest {
        val engine = Engine()
        val graph = graph(
            source("a", "import * as ns from \"./b.js\";"),
            mapOf("./b.js" to source("b", "export const x = 1;")),
        )
        ModuleLinker.link(graph)
        val instantiator = ModuleDeclarationInstantiator(engine.interpreter)
        instantiator.instantiate(graph.records.getValue("b"))
        val aEnvironment = instantiator.instantiate(graph.entry)

        assertTrue(aEnvironment.hasOwnBinding("ns"))
        assertFailsWith<JSException> { aEnvironment.getBinding("ns") }
        assertFailsWith<JSException> { aEnvironment.setBinding("ns", JSNumber(1.0), strict = true) }
        assertEquals(false, aEnvironment.deleteBinding("ns"))
    }

    @Test
    fun `匿名 default 建立 star default 绑定并保持 TDZ`() = runTest {
        val engine = Engine()
        val graph = graph(source("a", "export default 1;"))
        ModuleLinker.link(graph)
        val environment = ModuleDeclarationInstantiator(engine.interpreter).instantiate(graph.entry)

        assertTrue(environment.hasOwnBinding("*default*"))
        assertFailsWith<JSException> { environment.getBinding("*default*") }
    }
}
