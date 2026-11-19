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
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalModuleApi::class)
class FollowupRegressionTest {

    private suspend fun module(
        @Language("JavaScript") source: String,
        provider: ModuleProvider = InMemoryModuleProvider(emptyMap()),
        limits: EngineLimits = EngineLimits(),
    ): String {
        val engine = Engine(limits = limits)
        return try {
            val namespace = engine.runModule(ModuleSource("entry", source), provider)
            (engine.interpreter.getV(namespace, PropertyKey.Str("result")) as JSString).value
        } finally {
            engine.close()
        }
    }

    private suspend fun script(@Language("JavaScript") source: String): JSValue {
        val engine = Engine()
        return try {
            engine.run(source)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `导入选项表达式抛错时同步传播`() = runTest {
        val actual = module("""
            let phase = "none";
            function options() {
              throw new Error("options");
            }
            try {
              const promise = import("m", options());
              phase = await promise.then(() => "fulfilled", e => "rejected:" + e.message);
            } catch (e) {
              phase = "sync:" + e.message;
            }
            export const result = phase;
        """.trimIndent())
        assertEquals("sync:options", actual)
    }

    @Test
    fun `先求值选项表达式再转换说明符字符串`() = runTest {
        val actual = module("""
            const log = [];
            const specifier = {
              toString() {
                log.push("string");
                return "m";
              }
            };
            function options() {
              log.push("options");
              return {};
            }
            await import(specifier, options());
            export const result = log.join(":");
        """.trimIndent(), InMemoryModuleProvider(mapOf("m" to "export const x = 1;")))
        assertEquals("options:string", actual)
    }

    @Test
    fun `JSON 容器按自己的深度上限解析`() = runTest {
        val data = "[".repeat(20) + "1" + "]".repeat(20)
        val actual = module("""
            import data from "data" with { type: "json" };
            export const result = String(Array.isArray(data));
        """.trimIndent(), InMemoryModuleProvider(mapOf("data" to data)), EngineLimits(maxNestingDepth = 16, maxJsonDepth = 64))
        assertEquals("true", actual)
    }

    @Test
    fun `模块图累计预算按 Provider 原始源字符数核算`() = runTest {
        val entry = """
            import value from "data" with { type: "json" };
            export const result = String(value.release.major);
        """.trimIndent()
        val json = """
            {
              "release": {
                "major": 1,
                "minor": 2,
                "patch": 3
              }
            }
        """.trimIndent().replace("  ", "                ")
        val limit = entry.length + json.length - 1
        val error = assertFailsWith<ModuleLinkException> {
            module(entry, InMemoryModuleProvider(mapOf("data" to json)), EngineLimits(maxTotalModuleSourceLength = limit))
        }
        assertEquals(ModuleLinkErrorKind.TOTAL_SOURCE_TOO_LARGE, error.kind)
    }

    @Test
    fun `描述符先读取 enumerable 再检查 value`() = runTest {
        val actual = script("""
            const desc = {
              get enumerable() {
                this.value = 17;
                return true;
              }
            };
            const target = Object.defineProperty({}, "x", desc);
            String(target.x);
        """.trimIndent()) as JSString
        assertEquals("17", actual.value)
    }

    @Test
    fun `脚本工厂交给模块调用后仍属于定义脚本`() = runTest {
        val requests = mutableListOf<ModuleRequest>()
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource {
                requests.add(request)
                return ModuleSource(request.specifier, "export const color = 'blue';")
            }
        }
        val engine = Engine()
        try {
            engine.installModuleProvider(provider)
            engine.run("globalThis.makeReader = () => () => import('palette');", "factory.js")
            engine.runModule(ModuleSource("entry", "globalThis.reader = makeReader();"), provider)
            (engine.run("reader();") as JSPromise).awaitResult()
            assertEquals(null, requests.single().referrer)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `并行顶层等待恢复后新建闭包仍使用各自模块`() = runTest {
        val shared = """
            await 0;
            export const getUrl = () => import.meta.url;
        """.trimIndent()
        val actual = module("""
            import { getUrl as first } from "a";
            import { getUrl as second } from "b";
            export const result = first() + ":" + second();
        """.trimIndent(), InMemoryModuleProvider(mapOf("a" to shared, "b" to shared)))
        assertEquals("module:a:module:b", actual)
    }

    @Test
    fun `字节资产默认导出不依赖可替换的全局构造器`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest) =
                ModuleSource(request.specifier, "", bytes = byteArrayOf(42))
        }
        val actual = module("""
            globalThis.Uint8Array = function () {
              throw new Error("replacement");
            };
            const result = await import("bytes", { with: { type: "bytes" } })
              .then(ns => String(ns.default[0]), e => e.message);
            export { result };
        """.trimIndent(), provider)
        assertEquals("42", actual)
    }

    @Test
    fun `非十进制 Number 字面量不受带符号 Long 范围限制`() = runTest {
        val actual = script("""
            const highBit = 0x8000000000000000;
            String(highBit === 2 ** 63);
        """.trimIndent()) as JSString
        assertEquals("true", actual.value)
    }
}
