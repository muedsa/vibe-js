package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 阶段 3 回归矩阵：循环、依赖失败、namespace 描述符与 import.meta 缓存。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleExecutionMatrixTest {

    private class Provider(
        private val sources: Map<String, ModuleSource>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? = sources[request.specifier]
    }

    private fun source(id: String, code: String) = ModuleSource(id, code)

    @Test
    fun `循环依赖求值后函数与 live binding 可用`() = runTest {
        val engine = Engine()
        val entry = source(
            "a",
            """
            import { getB } from "./b.js";
            export { getB };
            export const a = 1;
            export function getA() { return a; }
            """.trimIndent(),
        )
        val namespace = engine.runModule(
            entry,
            Provider(
                mapOf(
                    "./a.js" to entry,
                    "./b.js" to source(
                        "b",
                        """
                        import { getA } from "./a.js";
                        export const b = 2;
                        export function getB() { return getA() + b; }
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val getB = engine.interpreter.getProperty(namespace, PropertyKey.Str("getB"))
        assertEquals(3.0, (engine.interpreter.callFunction(getB, JSUndefined, emptyList()) as JSNumber).value)
    }

    @Test
    fun `依赖求值失败后入口失败且 Engine 可复用`() = runTest {
        val engine = Engine()
        assertFailsWith<JSException> {
            engine.runModule(
                source("a", "import { x } from \"./b.js\"; export const y = x;"),
                Provider(mapOf("./b.js" to source("b", "export const x = 1; throw new Error(\"dep boom\");"))),
            )
        }
        assertEquals(1.0, (engine.run("1") as JSNumber).value)
    }

    @Test
    fun `namespace 键与描述符端到端`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export const x = 1; export * as ns from \"./b.js\";"),
            Provider(mapOf("./b.js" to source("b", "export const y = 2;"))),
        )

        assertEquals(
            listOf("ns", "x"),
            namespace.ownPropertyKeys()
                .filterIsInstance<PropertyKey.Str>()
                .map { it.value },
        )
        assertEquals(1, namespace.ownPropertyKeys().count { it is PropertyKey.Sym })

        val descriptor = assertNotNull(
            engine.interpreter.internalGetOwnProperty(namespace, PropertyKey.Str("x")),
        )
        assertEquals(1.0, (descriptor.value as JSNumber).value)
        assertTrue(descriptor.writable)
        assertTrue(descriptor.enumerable)
        assertTrue(!descriptor.configurable)

        val tag = assertNotNull(
            engine.interpreter.internalGetOwnProperty(
                namespace,
                PropertyKey.Sym(engine.intrinsics.symbolToStringTag),
            ),
        )
        assertEquals("Module", (tag.value as JSString).value)
        assertIs<JSObject>(engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")))
    }

    @Test
    fun `同一模块的 import_meta 对象保持同一引用`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("a", "export const m1 = import.meta; export const m2 = import.meta;"),
        )
        val m1 = engine.interpreter.getProperty(namespace, PropertyKey.Str("m1"))
        val m2 = engine.interpreter.getProperty(namespace, PropertyKey.Str("m2"))
        assertIs<JSObject>(m1)
        assertIs<JSObject>(m2)
        assertSame(m1, m2)
    }

    @Test
    fun `循环星号导出的 namespace 保留实时绑定`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * from './a.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        """
                        export * from './b.js';
                        export let value = 1;
                        export function setValue(next) {
                          value = next;
                        }
                        """.trimIndent(),
                    ),
                    "./b.js" to source("b", "export * from './a.js'; export const b = 2;"),
                ),
            ),
        )
        assertEquals(
            listOf("b", "setValue", "value"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
        val setValue = engine.interpreter.getProperty(namespace, PropertyKey.Str("setValue"))
        engine.interpreter.callFunction(setValue, JSUndefined, listOf(JSNumber(7.0)))
        assertEquals(7.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value)
    }

    @Test
    fun `循环导出中的同名歧义不进入 namespace`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * from './a.js'; export * from './b.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source("a", "export const x = 1;"),
                    "./b.js" to source("b", "export const x = 2;"),
                ),
            ),
        )
        assertEquals(
            0,
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().size,
        )
        assertEquals(null, namespace.getOwnProperty(PropertyKey.Str("x")))
    }

    @Test
    fun `重复星号导出同一模块不产生歧义`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * from './a.js'; export * from './a.js';",
            ),
            Provider(mapOf("./a.js" to source("a", "export const x = 1;"))),
        )
        assertEquals(
            listOf("x"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `循环依赖按深度优先逆序执行模块体`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "import './a.js'; export const log = globalThis.cycleLog;",
            ),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        "globalThis.cycleLog = (globalThis.cycleLog || '') + 'a'; import './b.js';",
                    ),
                    "./b.js" to source(
                        "b",
                        "globalThis.cycleLog = (globalThis.cycleLog || '') + 'b'; import './a.js';",
                    ),
                ),
            ),
        )
        assertEquals("ba", (engine.interpreter.getProperty(namespace, PropertyKey.Str("log")) as JSString).value)
    }

    @Test
    fun `显式导出优先于同名星号导出`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export { x } from './a.js'; export * from './b.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source("a", "export const x = 1;"),
                    "./b.js" to source("b", "export const x = 2;"),
                ),
            ),
        )
        assertEquals(
            listOf("x"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `显式导出即使位于星号导出之后仍优先`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * from './b.js'; export { x } from './a.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source("a", "export const x = 1;"),
                    "./b.js" to source("b", "export const x = 2;"),
                ),
            ),
        )
        assertEquals(
            listOf("x"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `显式导出的实时绑定覆盖同名星号导出`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export { x, setX } from './a.js'; export * from './b.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        """
                        export let x = 1;
                        export function setX(next) {
                          x = next;
                        }
                        """.trimIndent(),
                    ),
                    "./b.js" to source("b", "export const x = 2;"),
                ),
            ),
        )
        assertEquals(
            listOf("setX", "x"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
        val setX = engine.interpreter.getProperty(namespace, PropertyKey.Str("setX"))
        engine.interpreter.callFunction(setX, JSUndefined, listOf(JSNumber(5.0)))
        assertEquals(5.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `菱形依赖只求值一次并按深度优先顺序执行`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                """
                import './a.js';
                import './b.js';
                export const order = globalThis.order;
                export { sharedCount } from './shared.js';
                """.trimIndent(),
            ),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        "import './shared.js'; globalThis.order = (globalThis.order || '') + 'a,';",
                    ),
                    "./b.js" to source(
                        "b",
                        "import './shared.js'; globalThis.order = (globalThis.order || '') + 'b,';",
                    ),
                    "./shared.js" to source(
                        "shared",
                        """
                        globalThis.sharedCount = (globalThis.sharedCount || 0) + 1;
                        globalThis.order = (globalThis.order || '') + 'shared,';
                        export const sharedCount = globalThis.sharedCount;
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals("shared,a,b,", (engine.interpreter.getProperty(namespace, PropertyKey.Str("order")) as JSString).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("sharedCount")) as JSNumber).value)
    }

    @Test
    fun `export_star_as_default 暴露嵌套命名空间`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as default from './a.js';"),
            Provider(mapOf("./a.js" to source("a", "export const x = 1;"))),
        )
        assertEquals(
            listOf("default"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("default")),
        )
        assertEquals(1.0, (engine.interpreter.getProperty(nested, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `命名空间导出包含目标的 default 与普通导出`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as ns from './a.js';"),
            Provider(
                mapOf(
                    "./a.js" to source("a", "export default 7; export const x = 1;"),
                ),
            ),
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")),
        )
        assertEquals(
            listOf("default", "x"),
            nested.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(7.0, (engine.interpreter.getProperty(nested, PropertyKey.Str("default")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(nested, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `空模块的命名空间导出保持空对象语义`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as ns from './a.js';"),
            Provider(mapOf("./a.js" to source("a", "export {};"))),
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")),
        )
        assertEquals(
            0,
            nested.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().size,
        )
        assertEquals(null, nested.prototype)
        assertFalse(nested.isExtensible())
    }

    @Test
    fun `无导出的入口模块仍执行副作用并返回空 namespace`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "globalThis.sideEffect = 1;"),
        )
        assertEquals(
            0,
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().size,
        )
        assertEquals(null, namespace.prototype)
        assertFalse(namespace.isExtensible())
        assertEquals(1.0, (engine.run("globalThis.sideEffect") as JSNumber).value)
    }

    @Test
    fun `星号导出不包含目标的 default`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * from './a.js';"),
            Provider(
                mapOf(
                    "./a.js" to source("a", "export default 7; export const x = 1;"),
                ),
            ),
        )
        assertEquals(
            listOf("x"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(null, namespace.getOwnProperty(PropertyKey.Str("default")))
    }

    @Test
    fun `命名空间导出保持目标的实时绑定`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as ns from './a.js';"),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        """
                        export let x = 1;
                        export function setX(next) {
                          x = next;
                        }
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")),
        )
        val setX = engine.interpreter.getProperty(nested, PropertyKey.Str("setX"))
        engine.interpreter.callFunction(setX, JSUndefined, listOf(JSNumber(5.0)))
        assertEquals(5.0, (engine.interpreter.getProperty(nested, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `default 可以显式重导出`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export { default } from './a.js';"),
            Provider(mapOf("./a.js" to source("a", "export default 7;"))),
        )
        assertEquals(
            listOf("default"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(7.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("default")) as JSNumber).value)
    }

    @Test
    fun `嵌套命名空间保持 namespace exotic 描述符`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as ns from './a.js';"),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        "export const x = 1; export * as nested from './b.js';",
                    ),
                    "./b.js" to source("b", "export const y = 2;"),
                ),
            ),
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")),
        )
        assertEquals(null, nested.prototype)
        assertFalse(nested.isExtensible())
        assertEquals(
            listOf("nested", "x"),
            nested.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        val xDescriptor = assertNotNull(nested.getOwnProperty(PropertyKey.Str("x")))
        assertTrue(xDescriptor.writable)
        assertTrue(xDescriptor.enumerable)
        assertFalse(xDescriptor.configurable)
        val inner = assertIs<JSObject>(
            engine.interpreter.getProperty(nested, PropertyKey.Str("nested")),
        )
        assertEquals(2.0, (engine.interpreter.getProperty(inner, PropertyKey.Str("y")) as JSNumber).value)
    }

    @Test
    fun `同一模块的多个命名空间导出保持同一对象`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * as ns1 from './a.js'; export * as ns2 from './a.js';",
            ),
            Provider(mapOf("./a.js" to source("a", "export const x = 1;"))),
        )
        val first = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns1")),
        )
        val second = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns2")),
        )
        assertSame(first, second)
    }

    @Test
    fun `嵌套命名空间的 toStringTag 仍为 Module`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * as ns from './a.js';"),
            Provider(mapOf("./a.js" to source("a", "export const x = 1;"))),
        )
        val nested = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns")),
        )
        val tag = assertNotNull(
            nested.getOwnProperty(PropertyKey.Sym(engine.intrinsics.symbolToStringTag)),
        )
        assertEquals("Module", (tag.value as JSString).value)
    }

    @Test
    fun `不同 specifier 指向同一模块时命名空间身份一致`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source(
                "entry",
                "export * as ns1 from './a.js'; export * as ns2 from './b.js';",
            ),
            Provider(
                mapOf(
                    "./a.js" to source("shared", "export const x = 1;"),
                    "./b.js" to source("shared", "export const x = 1;"),
                ),
            ),
        )
        val first = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns1")),
        )
        val second = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("ns2")),
        )
        assertSame(first, second)
        assertEquals(1.0, (engine.interpreter.getProperty(first, PropertyKey.Str("x")) as JSNumber).value)
    }

    @Test
    fun `依赖求值失败后后续依赖不再执行`() = runTest {
        val engine = Engine()
        assertFailsWith<JSException> {
            engine.runModule(
                source(
                    "entry",
                    "import './a.js'; import './b.js';",
                ),
                Provider(
                    mapOf(
                        "./a.js" to source(
                            "a",
                            "globalThis.aExecuted = 1; throw new Error('a-failed');",
                        ),
                        "./b.js" to source("b", "globalThis.bExecuted = 1;"),
                    ),
                ),
            )
        }
        assertEquals(1.0, (engine.run("globalThis.aExecuted") as JSNumber).value)
        assertEquals("undefined", (engine.run("typeof globalThis.bExecuted") as JSString).value)
    }

    @Test
    fun `依赖求值失败后入口模块体不再执行`() = runTest {
        val engine = Engine()
        assertFailsWith<JSException> {
            engine.runModule(
                source(
                    "entry",
                    """
                    import './a.js';
                    globalThis.entryBodyExecuted = 1;
                    """.trimIndent(),
                ),
                Provider(
                    mapOf(
                        "./a.js" to source("a", "throw new Error('a-failed');"),
                    ),
                ),
            )
        }
        assertEquals("undefined", (engine.run("typeof globalThis.entryBodyExecuted") as JSString).value)
    }

    @Test
    fun `循环重命名导出保留别名实时绑定`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * from './a.js'; export * from './b.js';"),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        """
                        export { value as aValue } from './b.js';
                        export const a = 1;
                        export let value = 10;
                        """.trimIndent(),
                    ),
                    "./b.js" to source(
                        "b",
                        """
                        export { value as bValue } from './a.js';
                        export const b = 2;
                        export const value = 20;
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("a", "aValue", "b", "bValue"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(20.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("aValue")) as JSNumber).value)
        assertEquals(10.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("bValue")) as JSNumber).value)
        assertEquals(null, namespace.getOwnProperty(PropertyKey.Str("value")))
    }

    @Test
    fun `循环星号命名空间导出保持嵌套命名空间身份`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * from './a.js'; export * from './b.js';"),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        "export const a = 1; export * as bNamespace from './b.js';",
                    ),
                    "./b.js" to source(
                        "b",
                        "export const b = 2; export * as aNamespace from './a.js';",
                    ),
                ),
            ),
        )
        val bNamespace = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("bNamespace")),
        )
        val aNamespace = assertIs<JSObject>(
            engine.interpreter.getProperty(namespace, PropertyKey.Str("aNamespace")),
        )
        assertSame(
            bNamespace,
            engine.interpreter.getProperty(aNamespace, PropertyKey.Str("bNamespace")),
        )
        assertEquals(2.0, (engine.interpreter.getProperty(bNamespace, PropertyKey.Str("b")) as JSNumber).value)
    }

    @Test
    fun `循环 default 重导出保留重命名别名`() = runTest {
        val engine = Engine()
        val namespace = engine.runModule(
            source("entry", "export * from './a.js'; export * from './b.js';"),
            Provider(
                mapOf(
                    "./a.js" to source(
                        "a",
                        "export { default as aDefault } from './b.js'; export default 1;",
                    ),
                    "./b.js" to source(
                        "b",
                        "export { default as bDefault } from './a.js'; export default 2;",
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("aDefault", "bDefault"),
            namespace.ownPropertyKeys().filterIsInstance<PropertyKey.Str>().map { it.value },
        )
        assertEquals(2.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("aDefault")) as JSNumber).value)
        assertEquals(1.0, (engine.interpreter.getProperty(namespace, PropertyKey.Str("bDefault")) as JSNumber).value)
    }
}
