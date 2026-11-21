package com.muedsa.js

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.test.runTest
import org.intellij.lang.annotations.Language
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalModuleApi::class)
class SelfAuditRegressionTest {

    private suspend fun script(@Language("JavaScript") source: String): JSValue {
        val engine = Engine()
        return try {
            engine.run(source)
        } finally {
            engine.close()
        }
    }

    private suspend fun module(
        @Language("JavaScript") source: String,
        provider: ModuleProvider,
    ): JSValue {
        val engine = Engine()
        return try {
            val namespace = engine.runModule(ModuleSource("entry", source), provider)
            engine.interpreter.getV(namespace, PropertyKey.Str("result"))
        } finally {
            engine.close()
        }
    }

    private fun text(value: JSValue): String = when (value) {
        is JSString -> value.value
        else -> value.toPrimitiveString()
    }

    @Test
    fun `语句赋值条件和标签递归都遵守解析嵌套限制`() = runTest {
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 8))
        try {
            assertFailsWith<ResourceLimitException> {
                engine.parse("if (1) ".repeat(20) + "x;")
            }
            assertFailsWith<ResourceLimitException> {
                engine.parse("x = ".repeat(20) + "1;")
            }
            assertFailsWith<ResourceLimitException> {
                engine.parse("a ? b : ".repeat(20) + "c;")
            }
            val labels = (0 until 20).joinToString("") { "label$it:" }
            assertFailsWith<ResourceLimitException> {
                engine.parse(labels + "x;")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `括号包裹的 use strict 不是指令序言`() = runTest {
        val result = script(
            """
            ("use strict");
            (function () {
              return this === globalThis;
            })();
            """.trimIndent(),
        )
        assertEquals(true, (result as JSBoolean).value)
    }

    @Test
    fun `模板表达式支持后置自增和关键字属性后的除法`() = runTest {
        val result = script(
            $$"""
            let i = 5;
            const o = { return: 8 };
            String(`${i++ / 2}`) + ":" + String(`${o.return / 2}`);
            """.trimIndent(),
        )
        assertEquals("2.5:4", text(result))
    }

    @Test
    fun `标识符 Unicode 转义允许任意数量的前导零`() = runTest {
        val result = script(
            """
            const \u{0000041} = 1;
            A;
            """.trimIndent(),
        )
        assertEquals(1.0, (result as JSNumber).value)
    }

    @Test
    fun `非 unicode 字符类的倒序范围是早期错误`() = runTest {
        val engine = Engine()
        try {
            assertFailsWith<LexerException> {
                engine.parse("try { /[b-a]/; } catch (e) {}")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `sloppy 下 await 标识符后的数字除法链`() = runTest {
        val result = script(
            """
            var await = 8;
            await / 2 / 2;
            """.trimIndent(),
        )
        assertEquals(2.0, (result as JSNumber).value)
    }

    @Test
    fun `暂停生成器不计入事件循环 asyncTasks`() = runTest {
        val engine = Engine()
        try {
            engine.run(
                """
                function* g() {
                  yield 1;
                }
                globalThis.iterator = g();
                globalThis.iterator.next();
                """.trimIndent(),
            )
            assertEquals(0, engine.eventLoop.pendingTasks.asyncTasks)
            engine.eventLoop.runUntilIdle()
        } finally {
            engine.close()
        }
    }

    @Test
    fun `动态导入缺失导出映射为 SyntaxError`() = runTest {
        val provider = InMemoryModuleProvider(
            mapOf(
                "./bad.mjs" to """
                    import { missing } from "./dep.mjs";
                    export const value = 1;
                """.trimIndent(),
                "./dep.mjs" to "export const present = 1;",
            ),
        )
        val result = module(
            """
            export const result = await import("./bad.mjs").then(
              () => "ok",
              (error) => error.name
            );
            """.trimIndent(),
            provider,
        )
        assertEquals("SyntaxError", text(result))
    }

    @Test
    fun `失败的模块图加载不会污染第二次导入`() = runTest {
        val base = InMemoryModuleProvider(
            mapOf(
                "./parent.mjs" to """
                    import { value } from "./dep.mjs";
                    export const answer = value;
                """.trimIndent(),
                "./dep.mjs" to "export const value = 42;",
            ),
        )
        var firstDependencyLoad = true
        val provider = object : ModuleProvider {
            override suspend fun load(request: ModuleRequest): ModuleSource? {
                if (request.specifier == "./dep.mjs" && firstDependencyLoad) {
                    firstDependencyLoad = false
                    return null
                }
                return base.load(request)
            }
        }
        val result = module(
            """
            let first;
            let second;
            try {
              await import("./parent.mjs");
              first = "ok";
            } catch (error) {
              first = "err";
            }
            try {
              const parent = await import("./parent.mjs");
              second = parent.answer;
            } catch (error) {
              second = "err:" + error.message;
            }
            export const result = first + ":" + second;
            """.trimIndent(),
            provider,
        )
        assertEquals("err:42", text(result))
    }

    @Test
    fun `生成器体错误栈保留同步调用方帧`() = runTest {
        val result = script(
            """
            function* boom() {
              throw new Error("from-generator");
            }
            function caller() {
              try {
                boom().next();
              } catch (error) {
                globalThis.result = error.stack;
              }
            }
            caller();
            globalThis.result;
            """.trimIndent(),
        )
        val stack = text(result)
        assertContains(stack, "at boom")
        assertContains(stack, "at caller")
    }

    @Test
    fun `大数组排序保持稳定且结果有序`() = runTest {
        val result = script(
            """
            const values = [];
            for (let index = 0; index < 5000; index++) {
              values.push({ key: (index * 7919) % 1000, index });
            }
            values.sort((left, right) => left.key - right.key);
            let stable = true;
            for (let index = 1; index < values.length; index++) {
              if (values[index - 1].key > values[index].key) stable = false;
            }
            [values[0].key, values[values.length - 1].key, stable].join(":");
            """.trimIndent(),
        )
        assertEquals("0:999:true", text(result))
    }

    @Test
    fun `JSON stringify 深嵌套返回可捕获的 RangeError`() = runTest {
        val result = script(
            """
            let value = 0;
            for (let index = 0; index < 1500; index++) {
              value = [value];
            }
            let name = "none";
            try {
              JSON.stringify(value);
            } catch (error) {
              name = error.name;
            }
            name;
            """.trimIndent(),
        )
        assertEquals("RangeError", text(result))
    }

    @Test
    fun `Array flat 深嵌套不会耗尽宿主调用栈`() = runTest {
        val result = script(
            """
            let value = 0;
            for (let index = 0; index < 20000; index++) {
              value = [value];
            }
            let output = "none";
            try {
              const flat = value.flat(Infinity);
              output = flat.length + ":" + flat[0];
            } catch (error) {
              output = error.name;
            }
            output;
            """.trimIndent(),
        )
        assertEquals("1:0", text(result))
    }

    @Test
    fun `padStart 超长目标长度返回 RangeError 而不是宿主错误`() = runTest {
        val result = script(
            """
            let name = "none";
            try {
              "a".padStart(2147483647);
            } catch (error) {
              name = error.name;
            }
            name;
            """.trimIndent(),
        )
        assertEquals("RangeError", text(result))
    }

    @Test
    fun `循环数组 join 输出空元素而不是递归溢出`() = runTest {
        val result = script(
            """
            const values = [1];
            values.push(values);
            values.join();
            """.trimIndent(),
        )
        assertEquals("1,", text(result))
    }
}
