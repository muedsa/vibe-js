package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** ModuleProvider 的最小契约：入口/依赖 referrer、null、异常与源码元数据。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleProviderContractTest {

    @Test
    fun `入口与依赖请求的 referrer 契约`() = runTest {
        val requests = mutableListOf<ModuleRequest>()
        val provider = ModuleProvider { request ->
            requests += request
            when (request.specifier) {
                "entry" -> ModuleSource(
                    id = "entry",
                    source = "import { value } from \"./dep.js\"; export { value };",
                    sourceName = "entry.mjs",
                    url = "https://example.test/entry.mjs",
                )

                "./dep.js" -> ModuleSource(
                    id = "dep",
                    source = "export const value = 1;",
                    sourceName = "dep.mjs",
                )

                else -> null
            }
        }
        val engine = Engine()
        val namespace = engine.runModule("entry", provider)

        assertEquals(
            listOf(
                ModuleRequest("entry", referrer = null),
                ModuleRequest("./dep.js", referrer = "entry"),
            ),
            requests,
        )
        assertEquals(
            1.0,
            (engine.interpreter.getProperty(namespace, PropertyKey.Str("value")) as JSNumber).value,
        )
    }

    @Test
    fun `Provider 返回 null 时进入 NOT_FOUND`() = runTest {
        val engine = Engine()
        val provider = ModuleProvider { null }
        val error = assertFailsWith<ModuleLinkException> {
            engine.runModule("missing", provider)
        }
        assertEquals(ModuleLinkErrorKind.NOT_FOUND, error.kind)
        assertEquals("missing", error.specifier)
        assertNull(error.referrer)
    }

    @Test
    fun `Provider 异常原样透传`() = runTest {
        val engine = Engine()
        val failure = IllegalStateException("provider offline")
        val provider = ModuleProvider { throw failure }
        val error = assertFailsWith<IllegalStateException> {
            engine.runModule("entry", provider)
        }
        assertSame(failure, error)
    }

    @Test
    fun `InMemoryModuleProvider 精确 id 与 sourceName`() = runTest {
        val provider = InMemoryModuleProvider(mapOf("./entry.js" to "export const value = 1;"))
        val source = provider.load(ModuleRequest("./entry.js"))
        assertEquals("./entry.js", source?.id)
        assertEquals("./entry.js", source?.sourceName)
        assertNull(source?.url)
        assertNull(provider.load(ModuleRequest("entry.js")))
    }

    @Test
    fun `Provider 返回的 sourceName 可用于错误定位`() = runTest {
        val engine = Engine(limits = EngineLimits())
        val provider = ModuleProvider { request ->
            ModuleSource(
                id = request.specifier,
                source = "throw new Error(\"boom\");",
                sourceName = "custom-name.mjs",
            )
        }
        val error = assertFailsWith<com.muedsa.js.runtime.exception.JSException> {
            engine.runModule("entry", provider)
        }
        assertEquals(true, error.stack.any { it.sourceInfo.name == "custom-name.mjs" })
    }
}
