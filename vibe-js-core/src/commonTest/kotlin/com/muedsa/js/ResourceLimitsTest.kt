package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind
import com.muedsa.js.runtime.regex.JsRegexEngine
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * R1 资源约束（源码长度、语法嵌套、JSON 深度、正则步数、事件循环队列）。
 *
 * 这些限制都是宿主控制异常：除 JSON 深度按现有错误模型暴露为 RangeError 外，
 * 不能被 JavaScript try/catch 吞掉。每个用例都验证限制名、上限值、实际值与消息，
 * 避免只断言"抛了某个异常"而掩盖限制项配置错误。
 */
class ResourceLimitsTest {

    @Test
    fun `源码长度超限在解析前抛出资源异常并携带限制名与实际值`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSourceLength = 8))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.parse("123456789")
            }
            assertEquals(ResourceLimitKind.SOURCE_LENGTH, error.kind)
            assertEquals("maxSourceLength", error.limitName)
            assertEquals(8L, error.limitValue)
            assertEquals(9L, error.actualValue)
            assertTrue(error.message!!.contains("maxSourceLength"))
            assertTrue(error.message!!.contains("actual=9"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `源码长度边界值允许恰好等于上限并从run入口同样生效`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSourceLength = 3))
        try {
            // "123" 恰好 3 个字符，是合法数字字面量。
            engine.parse("123")
            assertEquals(123.0, assertIs<JSNumber>(engine.run("123")).value)
            val error = assertFailsWith<ResourceLimitException> { engine.run("1234") }
            assertEquals(ResourceLimitKind.SOURCE_LENGTH, error.kind)
            assertEquals(4L, error.actualValue)
            // 超限不污染引擎，下一段合法源码继续执行。
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `括号嵌套超限在词法阶段抛出明确异常`() = runTest {
        val limit = 8
        val engine = Engine(limits = EngineLimits(maxNestingDepth = limit))
        try {
            val source = "(".repeat(limit + 1) + "1" + ")".repeat(limit + 1)
            val error = assertFailsWith<ResourceLimitException> { engine.parse(source) }
            assertEquals(ResourceLimitKind.NESTING_DEPTH, error.kind)
            assertEquals("maxNestingDepth", error.limitName)
            assertEquals(limit.toLong(), error.limitValue)
            assertEquals((limit + 1).toLong(), error.actualValue)
            assertTrue(error.message!!.contains("maxNestingDepth"))
            assertTrue(error.message!!.contains("line 1, column 9"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `恰好达到嵌套上限的括号源码仍可解析`() = runTest {
        val limit = 8
        val engine = Engine(limits = EngineLimits(maxNestingDepth = limit))
        try {
            val source = "(".repeat(limit) + "1" + ")".repeat(limit)
            engine.parse(source)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `块数组与对象字面量共享同一嵌套深度上限`() = runTest {
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 4))
        try {
            val sources = listOf(
                // 5 层语句块
                "{{{{{1;}}}}}",
                // 5 层数组
                "[[[[[1]]]]]",
                // 5 层对象
                "{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":1}}}}}",
            )
            for (source in sources) {
                val error = assertFailsWith<ResourceLimitException> { engine.parse(source) }
                assertEquals(ResourceLimitKind.NESTING_DEPTH, error.kind)
                assertEquals(4L, error.limitValue)
                assertEquals(5L, error.actualValue)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `模板表达式嵌套深度跨子解析器累计`() = runTest {
        // 构造 5 层 `${` 模板：每一层由外层 Lexer 进入嵌套，子 Lexer/Parser 继承深度。
        var template = "`1`"
        repeat(5) { template = "`\${$template}`" }
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 4))
        try {
            val error = assertFailsWith<ResourceLimitException> { engine.parse(template) }
            assertEquals(ResourceLimitKind.NESTING_DEPTH, error.kind)
            assertEquals(4L, error.limitValue)
            assertEquals(5L, error.actualValue)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `无括号的箭头函数与一元运算链由解析器递归深度兜底`() = runTest {
        val engine = Engine(limits = EngineLimits(maxNestingDepth = 8))
        try {
            // 两类源码都没有括号，Lexer 深度为 0，只能由 Parser 递归守卫拦截。
            val sources = listOf(
                "x" + "=>x".repeat(20),
                "!".repeat(20) + "1",
            )
            for (source in sources) {
                val error = assertFailsWith<ResourceLimitException> { engine.parse(source) }
                assertEquals(ResourceLimitKind.NESTING_DEPTH, error.kind)
                assertEquals(8L, error.limitValue)
                assertTrue(error.actualValue > 8L)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `JSON 深度超限按现有错误模型转换为可捕获的 RangeError`() = runTest {
        val engine = Engine(limits = EngineLimits(maxJsonDepth = 3))
        try {
            val result = assertIs<JSString>(engine.run(
                """
                var caught = '';
                try { JSON.parse('[[[[1]]]]'); }
                catch (e) { caught = e.name + '|' + e.message; }
                caught + '|' + JSON.parse('[[[1]]]')[0][0][0];
                """.trimIndent()
            )).value
            assertTrue(result.startsWith("RangeError|"), "实际结果：$result")
            assertTrue(result.contains("maxJsonDepth=3"), "实际结果：$result")
            assertTrue(result.contains("actual=4"), "实际结果：$result")
            assertTrue(result.endsWith("|1"), "实际结果：$result")

            // 不计 reviver 的调用方式也应保持同一错误类型。
            val direct = assertFailsWith<JSException> { engine.run("JSON.parse('[[[[1]]]]')") }
            val error = assertIs<JSError>(direct.value)
            assertEquals("RangeError", error.errorName)
            assertTrue(error.messageText.contains("maxJsonDepth"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `正则回退引擎回溯超过步数预算抛出宿主异常`() = runTest {
        val engine = JsRegexEngine("^(a+)+$", "d", maxSteps = 300)
        val error = assertFailsWith<ResourceLimitException> {
            engine.find("a".repeat(40) + "!")
        }
        assertEquals(ResourceLimitKind.REGEX_STEPS, error.kind)
        assertEquals("maxRegexSteps", error.limitName)
        assertEquals(300L, error.limitValue)
        assertTrue(error.actualValue > 300L)
        assertTrue(error.message!!.contains("maxRegexSteps"))
        assertTrue(error.message!!.contains("actual=${error.actualValue}"))
    }

    @Test
    fun `正则步数超限经引擎不可被 JS catch 且后续执行可恢复`() = runTest {
        val engine = Engine(limits = EngineLimits(maxRegexSteps = 500))
        try {
            val source =
                "var caught = false; try { /^(a+)+$/d.test('${"a".repeat(40)}!'); } " +
                    "catch (e) { caught = true; }"
            val error = assertFailsWith<ResourceLimitException> { engine.run(source) }
            assertEquals(ResourceLimitKind.REGEX_STEPS, error.kind)
            // 宿主中止不执行 JS catch；后续 run 使用新预算并保持原全局状态。
            assertEquals(false, assertIs<JSBoolean>(engine.run("caught")).value)
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `默认正则预算允许普通匹配与全局替换`() = runTest {
        val engine = Engine()
        try {
            assertEquals(false, assertIs<JSBoolean>(
                engine.run("/^(a+)+$/d.test('aaaa!')")
            ).value)
            assertEquals(true, assertIs<JSBoolean>(
                engine.run("/^(a+)+$/d.test('aaaa')")
            ).value)
            assertEquals("x-x-x", assertIs<JSString>(engine.run("'a-a-a'.replace(/a/g, 'x')")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `事件循环排队任务超限抛出携带实际值的资源异常`() = runTest {
        val engine = Engine(limits = EngineLimits(maxPendingTasks = 4))
        try {
            repeat(4) { engine.eventLoop.enqueueMicrotask { } }
            val error = assertFailsWith<ResourceLimitException> {
                engine.eventLoop.enqueueMicrotask { }
            }
            assertEquals(ResourceLimitKind.PENDING_TASKS, error.kind)
            assertEquals("maxPendingTasks", error.limitName)
            assertEquals(4L, error.limitValue)
            assertEquals(5L, error.actualValue)
            assertTrue(error.message!!.contains("maxPendingTasks"))
            assertTrue(error.message!!.contains("actual=5"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `JS 内排队任务超限不能被 catch 吞掉`() = runTest {
        val engine = Engine(limits = EngineLimits(maxPendingTasks = 4))
        try {
            engine.registry.defineGlobal(
                "queueMany",
                JSNativeFunction("queueMany") { _, _, _ ->
                    repeat(5) { engine.eventLoop.enqueueMicrotask { } }
                    JSUndefined
                },
            )
            val error = assertFailsWith<ResourceLimitException> {
                engine.run(
                    "var caught = false; try { queueMany(); } catch (e) { caught = true; }"
                )
            }
            assertEquals(ResourceLimitKind.PENDING_TASKS, error.kind)
            assertEquals(false, assertIs<JSBoolean>(engine.run("caught")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `新增配额拒绝零与负数并保留默认值`() = runTest {
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxSourceLength = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxSourceLength = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxNestingDepth = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxNestingDepth = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxJsonDepth = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxJsonDepth = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxRegexSteps = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxRegexSteps = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxPendingTasks = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxPendingTasks = -1) }

        val limits = EngineLimits()
        assertEquals(10_000_000, limits.maxSourceLength)
        assertEquals(256, limits.maxNestingDepth)
        assertEquals(1_000, limits.maxJsonDepth)
        assertEquals(10_000_000L, limits.maxRegexSteps)
        assertEquals(100_000, limits.maxPendingTasks)
        // 既有默认值保持不变。
        assertEquals(null, limits.maxSteps)
        assertEquals(16_384, limits.maxCallDepth)
    }

    @Test
    fun `默认配额不影响常规脚本微任务与较深但合理的嵌套`() = runTest {
        val engine = Engine()
        try {
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
            engine.run("var done = false; Promise.resolve().then(function () { done = true; });")
            assertEquals("true", assertIs<JSString>(engine.run("String(done)")).value)
            // 64 层括号远低于默认 256，仍应正常解析。
            engine.parse("(".repeat(64) + "1" + ")".repeat(64))
            // 默认 JSON 深度允许常见嵌套对象。
            assertEquals(1.0, assertIs<JSNumber>(engine.run(
                "JSON.parse('{\"a\":{\"b\":{\"c\":1}}}').a.b.c"
            )).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `eval 源码同样受源码长度限制`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSourceLength = 128))
        try {
            // 外层脚本很短；eval 运行时构造 200 个字符的源码并触发上限。
            val error = assertFailsWith<ResourceLimitException> {
                engine.run("var source = 'x'.repeat(200); eval(source);")
            }
            assertEquals(ResourceLimitKind.SOURCE_LENGTH, error.kind)
            assertEquals(128L, error.limitValue)
            assertEquals(200L, error.actualValue)
        } finally {
            engine.close()
        }
    }
}
