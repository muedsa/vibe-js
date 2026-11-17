package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSNumber
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
class ReviewRegressionTest {

    private suspend fun script(@Language("JavaScript") source: String): JSValue {
        val engine = Engine()
        return try {
            engine.run(source)
        } finally {
            engine.close()
        }
    }

    private suspend fun module(@Language("JavaScript") source: String, provider: ModuleProvider): JSValue {
        val engine = Engine()
        return try {
            val namespace = engine.runModule(ModuleSource("entry", source), provider)
            engine.interpreter.getV(namespace, PropertyKey.Str("result"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `幂运算链和连续new遵守解析嵌套限制`() = runTest {
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 32))
        try {
            assertFailsWith<ResourceLimitException> { engine.parse("1 ** ".repeat(512) + "1") }
            assertFailsWith<ResourceLimitException> { engine.parse("new ".repeat(512) + "Object") }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `默认限制阻止超长幂运算链导致宿主栈溢出`() = runTest {
        val engine = Engine()
        try {
            assertFailsWith<ResourceLimitException> { engine.parse("1 ** ".repeat(20_000) + "1") }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `批量属性描述符必须先全部转换再修改目标`() = runTest {
        val result = script("""
            const target = {};
            try {
              Object.defineProperties(target, { a: { value: 1 }, b: { get: 123 } });
            } catch (e) {}
            String(Object.hasOwn(target, "a"));
        """.trimIndent())
        assertEquals("false", (result as JSString).value)
    }

    @Test
    fun `动态导入选项错误应拒绝 Promise`() = runTest {
        val result = module("""
            let outcome;
            try {
              const promise = import("m", { with: { type: 123 } });
              outcome = "promise";
              await promise.catch(() => {});
            } catch (e) {
              outcome = "sync";
            }
            export const result = outcome;
        """.trimIndent(), InMemoryModuleProvider(mapOf("m" to "export const x = 1;")))
        assertEquals("promise", (result as JSString).value)
    }

    @Test
    fun `运行模块不会自动授予脚本 Provider`() = runTest {
        val engine = Engine()
        try {
            engine.runModule(
                ModuleSource("entry", "export const x = 1;"),
                InMemoryModuleProvider(mapOf("secret" to "export const x = 42;")),
            )
            assertFailsWith<JSException> {
                (engine.run("import('secret');") as JSPromise).awaitResult()
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `导出的 import meta resolve 以定义模块为参照`() = runTest {
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? =
                if (request.specifier == "lib") {
                    ModuleSource("lib", "export const resolve = import.meta.resolve;")
                } else {
                    null
                }

            override suspend fun resolve(request: ModuleRequest): String =
                "${request.referrer}/${request.specifier}"
        }
        val result = module("""
            import { resolve } from "lib";
            export const result = resolve("x");
        """.trimIndent(), provider)
        assertEquals("lib/x", (result as JSString).value)
    }

    @Test
    fun `跨模块调用创建的闭包保留定义模块身份`() = runTest {
        val result = module("""
            import { make } from "lib";
            export const result = make()();
        """.trimIndent(), InMemoryModuleProvider(mapOf("lib" to """
            export function make() {
              return () => import.meta.url;
            }
        """.trimIndent())))
        assertEquals("module:lib", (result as JSString).value)
    }

    @Test
    fun `JSON 模块不应调用被用户替换的 JSON parse`() = runTest {
        val result = module("""
            JSON.parse = () => ({ x: 99 });
            const data = await import("data", { with: { type: "json" } });
            export const result = data.default.x;
        """.trimIndent(), InMemoryModuleProvider(mapOf("data" to """{"x":1}""")))
        assertEquals(1.0, (result as JSNumber).value)
    }

    @Test
    fun `JSON 模块字面量保留 proto 数据属性`() = runTest {
        val result = module("""
            const data = await import("data", { with: { type: "json" } });
            export const result = String(Object.hasOwn(data.default, "__proto__")) +
              ":" + String(Object.getPrototypeOf(data.default) === Object.prototype);
        """.trimIndent(), InMemoryModuleProvider(mapOf("data" to """{"__proto__":{"x":1}}""")))
        assertEquals("true:true", (result as JSString).value)
    }

    @Test
    fun `手动冻结与密封空数组按描述符识别`() = runTest {
        val result = script("""
            const frozen = [];
            Object.preventExtensions(frozen);
            Object.defineProperty(frozen, "length", { writable: false });
            const sealed = [];
            Object.preventExtensions(sealed);
            String(Object.isFrozen(frozen)) + ":" + String(Object.isSealed(sealed));
        """.trimIndent())
        assertEquals("true:true", (result as JSString).value)
    }

    @Test
    fun `Reflect construct 先校验 newTarget 再读取参数列表`() = runTest {
        val result = script("""
            let touched = false;
            try {
              Reflect.construct(function () {}, {
                get length() {
                  touched = true;
                  return 0;
                }
              }, () => {});
            } catch (e) {}
            String(touched);
        """.trimIndent())
        assertEquals("false", (result as JSString).value)
    }
}
