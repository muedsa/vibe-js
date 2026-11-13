package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** JSON / text / bytes module 资产的加载、导出与错误语义。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleAssetTest {

    private class AssetProvider(
        private val sources: Map<String, ModuleSource>,
    ) : ModuleProvider {
        override suspend fun load(request: ModuleRequest): ModuleSource? =
            sources[request.specifier]
    }

    @Test
    fun `JSON module 导出解析后的 default 对象`() = runTest {
        val provider = AssetProvider(
            mapOf(
                "entry" to ModuleSource(
                    id = "entry",
                    source = """
                        import value from "./data.json" with { type: "json" };
                        if (value.a !== 1 || value.b[0] !== true || value.b[1] !== null) {
                          throw new Error("bad JSON value");
                        }
                        if (Object.getPrototypeOf(value) !== Object.prototype) {
                          throw new Error("bad JSON prototype");
                        }
                        export default value;
                    """.trimIndent(),
                ),
                "./data.json" to ModuleSource(
                    id = "./data.json",
                    source = """{"a":1,"b":[true,null]}""",
                ),
            ),
        )
        val engine = Engine()
        engine.runModule("entry", provider)
    }

    @Test
    fun `text module 导出原始字符串`() = runTest {
        val provider = AssetProvider(
            mapOf(
                "entry" to ModuleSource(
                    id = "entry",
                    source = """
                        import value from "./message.txt" with { type: "text" };
                        if (value !== "hello\nworld") {
                          throw new Error("bad text value");
                        }
                    """.trimIndent(),
                ),
                "./message.txt" to ModuleSource(
                    id = "./message.txt",
                    source = "hello\nworld",
                ),
            ),
        )
        Engine().runModule("entry", provider)
    }

    @Test
    fun `bytes module 导出 immutable Uint8Array`() = runTest {
        val provider = AssetProvider(
            mapOf(
                "entry" to ModuleSource(
                    id = "entry",
                    source = """
                        import value from "./payload.bin" with { type: "bytes" };
                        if (!(value instanceof Uint8Array)) {
                          throw new Error("bytes is not Uint8Array");
                        }
                        if (value.length !== 3 || value[0] !== 1 || value[2] !== 255) {
                          throw new Error("bad bytes value");
                        }
                        if (value.buffer.immutable !== true) {
                          throw new Error("bytes buffer is mutable");
                        }
                    """.trimIndent(),
                ),
                "./payload.bin" to ModuleSource(
                    id = "./payload.bin",
                    source = "",
                    bytes = byteArrayOf(1, 2, 255.toByte()),
                ),
            ),
        )
        Engine().runModule("entry", provider)
    }

    @Test
    fun `非法 JSON module 在 resolution 阶段失败`() = runTest {
        val provider = AssetProvider(
            mapOf(
                "entry" to ModuleSource(
                    id = "entry",
                    source = """
                        import value from "./invalid.json" with { type: "json" };
                        export default value;
                    """.trimIndent(),
                ),
                "./invalid.json" to ModuleSource(
                    id = "./invalid.json",
                    source = """{"a": }""",
                ),
            ),
        )
        val error = assertFailsWith<ModuleLinkException> {
            Engine().runModule("entry", provider)
        }
        assertEquals(ModuleLinkErrorKind.INVALID_MODULE, error.kind)
    }
}
