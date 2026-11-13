package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@OptIn(ExperimentalModuleApi::class)
class SourcePhaseImportTest {

    @Test
    fun `import source 绑定 source 对象且不解析源文本模块`() = runTest {
        val engine = Engine()
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? =
                if (request.specifier == "./source.js") {
                    // 故意提供非 JS 源文本；source phase 不应尝试解析它。
                    ModuleSource(
                        id = "source.js",
                        source = "this is not javascript",
                        sourceName = "source.js",
                        moduleSourceClassName = "Module Source",
                    )
                } else {
                    null
                }
        }
        val namespace = engine.runModule(
            ModuleSource(
                id = "entry.js",
                source = """
                    import source mod from "./source.js";
                    export const result = mod;
                """.trimIndent(),
                sourceName = "entry.js",
            ),
            provider,
        )
        val result = assertIs<JSObject>(
            engine.interpreter.getV(namespace, PropertyKey.Str("result"))
        )
        assertEquals("Module Source", result.moduleSourceClassName)
        val prototype = assertNotNull(result.prototype)
        assertSame(
            engine.intrinsics.abstractModuleSource.functionPrototypeObject,
            prototype.prototype,
        )
    }

    @Test
    fun `import source 对普通 JS source text 模块抛 SyntaxError`() = runTest {
        val engine = Engine()
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? =
                if (request.specifier == "./plain.js") {
                    ModuleSource(id = "plain.js", source = "export const x = 1;")
                } else {
                    null
                }
        }
        val error = assertFailsWith<JSException> {
            engine.runModule(
                ModuleSource(
                    id = "entry.js",
                    source = """import source mod from "./plain.js";""",
                    sourceName = "entry.js",
                ),
                provider,
            )
        }
        val value = assertIs<JSError>(error.value)
        assertEquals("SyntaxError", value.errorName)
    }
}
