package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind
import com.muedsa.js.runtime.regex.JsRegexEngine
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSMap
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
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

    @Test
    fun `AST 节点超限在解析期抛出并携带限制名与位置`() = runTest {
        val engine = Engine(limits = EngineLimits(maxAstNodes = 10))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.parse("1+1+1+1+1")
            }
            assertEquals(ResourceLimitKind.AST_NODES, error.kind)
            assertEquals("maxAstNodes", error.limitName)
            assertEquals(10L, error.limitValue)
            assertTrue(error.actualValue > 10L)
            assertTrue(error.message!!.contains("maxAstNodes"))
            assertTrue(error.message!!.contains("line 1, column"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `AST 节点预算边界允许恰好等于上限`() = runTest {
        // "1" 由 NumberLiteral + ExpressionStatement + Program 三个节点组成。
        val exact = Engine(limits = EngineLimits(maxAstNodes = 3))
        try {
            exact.parse("1")
        } finally {
            exact.close()
        }
        val tooSmall = Engine(limits = EngineLimits(maxAstNodes = 2))
        try {
            val error = assertFailsWith<ResourceLimitException> { tooSmall.parse("1") }
            assertEquals(ResourceLimitKind.AST_NODES, error.kind)
            assertEquals(2L, error.limitValue)
            assertEquals(3L, error.actualValue)
        } finally {
            tooSmall.close()
        }
    }

    @Test
    fun `eval 解析同样受 AST 节点预算约束`() = runTest {
        val engine = Engine(limits = EngineLimits(maxAstNodes = 5))
        try {
            // 外层脚本 eval(evalSource) 恰好 5 个节点；eval 的 "1+1+1" 需要 6 个节点。
            engine.registry.defineGlobal("evalSource", JSString("1+1+1"))
            val error = assertFailsWith<ResourceLimitException> {
                engine.run("eval(evalSource)")
            }
            assertEquals(ResourceLimitKind.AST_NODES, error.kind)
            assertEquals(5L, error.limitValue)
            assertTrue(error.actualValue > 5L)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `Function 构造器解析同样受 AST 节点预算约束`() = runTest {
        val engine = Engine(limits = EngineLimits(maxAstNodes = 5))
        try {
            // 外层脚本 Function(body) 恰好 5 个节点；动态函数源码需要更多节点。
            engine.registry.defineGlobal("body", JSString("return 1"))
            val error = assertFailsWith<ResourceLimitException> {
                engine.run("Function(body)")
            }
            assertEquals(ResourceLimitKind.AST_NODES, error.kind)
            assertEquals(5L, error.limitValue)
            assertTrue(error.actualValue > 5L)

            // 超限不污染引擎，后续小脚本仍可解析执行。
            assertEquals(42.0, assertIs<JSNumber>(engine.run("42")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `模板表达式子解析器与主解析器共享 AST 节点预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxAstNodes = 8))
        try {
            // 6 个子表达式各产生 1 个节点，加上模板/语句/Program 节点后超过 8。
            val error = assertFailsWith<ResourceLimitException> {
                engine.parse("`\${1}\${1}\${1}\${1}\${1}\${1}`")
            }
            assertEquals(ResourceLimitKind.AST_NODES, error.kind)
            assertEquals(8L, error.limitValue)
            assertTrue(error.actualValue > 8L)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `对象分配超限抛出携带实际分配数的宿主异常`() = runTest {
        val engine = Engine(limits = EngineLimits(maxObjectCount = 100))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.run("for (var i = 0; i < 500; i++) { ({ n: i }); }")
            }
            assertEquals(ResourceLimitKind.OBJECT_COUNT, error.kind)
            assertEquals("maxObjectCount", error.limitName)
            assertEquals(100L, error.limitValue)
            assertEquals(101L, error.actualValue)
            assertTrue(error.message!!.contains("maxObjectCount"))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `对象分配预算只统计单次 run 且引擎初始化对象不计入`() = runTest {
        val engine = Engine(limits = EngineLimits(maxObjectCount = 1))
        try {
            // 引擎初始化创建的内建对象发生在预算窗口外，不计入；原始值也不分配对象。
            assertEquals(1.0, assertIs<JSNumber>(engine.run("1")).value)
            // 恰好一个对象允许通过。
            engine.run("({})")
            val error = assertFailsWith<ResourceLimitException> { engine.run("({}, {})") }
            assertEquals(ResourceLimitKind.OBJECT_COUNT, error.kind)
            assertEquals(2L, error.actualValue)
            // 每次 run 重置计数，失败后仍可继续执行。
            engine.run("({})")
        } finally {
            engine.close()
        }
    }

    @Test
    fun `对象分配超限不能被 JS catch 吞掉`() = runTest {
        val engine = Engine(limits = EngineLimits(maxObjectCount = 50))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.run(
                    "var caught = false; try { for (;;) { ({}) } } catch (e) { caught = true; }"
                )
            }
            assertEquals(ResourceLimitKind.OBJECT_COUNT, error.kind)
            assertEquals(false, assertIs<JSBoolean>(engine.run("caught")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `数组函数与内建实例同样计入对象分配预算`() = runTest {
        for (source in listOf(
            "for (;;) { []; }",
            "for (;;) { (function () {}); }",
            "for (;;) { new Date(); }",
            "for (;;) { JSON.parse('{}'); }",
            "for (;;) { Object.create(null); }",
            "for (;;) { new Proxy({}, {}); }",
            "for (;;) { JSON.rawJSON(1); }",
        )) {
            val engine = Engine(limits = EngineLimits(maxObjectCount = 80))
            try {
                val error = assertFailsWith<ResourceLimitException> { engine.run(source) }
                assertEquals(ResourceLimitKind.OBJECT_COUNT, error.kind)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `对象分配预算按引擎隔离而不是全局共享`() = runTest {
        val limited = Engine(limits = EngineLimits(maxObjectCount = 20))
        val normal = Engine(limits = EngineLimits(maxObjectCount = 20))
        try {
            assertFailsWith<ResourceLimitException> {
                limited.run("for (;;) { ({}) }")
            }
            // 另一个引擎的单次 run 计数不受影响。
            normal.run("for (var i = 0; i < 10; i++) { ({ i: i }); }")
            normal.run("({})")
        } finally {
            limited.close()
            normal.close()
        }
    }

    @Test
    fun `默认对象预算允许常规对象与数组分配`() = runTest {
        val engine = Engine()
        try {
            assertEquals(49995000.0, assertIs<JSNumber>(engine.run(
                "var sum = 0; for (var i = 0; i < 10000; i++) { var o = { i: i }; sum += o.i; } sum;"
            )).value)
            assertEquals(1000.0, assertIs<JSNumber>(engine.run(
                "var n = 0; for (var i = 0; i < 1000; i++) { n += [i].length; } n;"
            )).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `Array 长循环内建在步数预算耗尽时抛出宿主异常`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 500))
        try {
            engine.registry.defineGlobal(
                "big",
                JSArray(
                    MutableList(100_000) { JSNumber(it.toDouble()) },
                    engine.intrinsics.arrayPrototype,
                ),
            )
            val sources = listOf(
                "big.forEach(function () {});",
                "big.map(function (x) { return x; });",
                "big.filter(function () { return false; });",
                "big.reduce(function (a, b) { return a + b; }, 0);",
                "big.join(',');",
            )
            for (source in sources) {
                val error = assertFailsWith<ExecutionLimitException> { engine.run(source) }
                assertEquals("maxSteps", error.limitName)
                assertEquals(500L, error.limitValue)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `Array sort 的插入排序比较循环消耗步数预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 500))
        try {
            engine.registry.defineGlobal(
                "descending",
                JSArray(
                    MutableList(5_000) { JSNumber((5_000 - it).toDouble()) },
                    engine.intrinsics.arrayPrototype,
                ),
            )
            val error = assertFailsWith<ExecutionLimitException> {
                engine.run("descending.sort();")
            }
            assertEquals("maxSteps", error.limitName)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `String split 与 replaceAll 长循环消耗步数预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 50))
        try {
            engine.registry.defineGlobal("text", JSString("a".repeat(200_000)))
            for (source in listOf("text.split('');", "text.replaceAll('a', 'b');")) {
                val error = assertFailsWith<ExecutionLimitException> { engine.run(source) }
                assertEquals("maxSteps", error.limitName)
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `JSON stringify 与 parse 长循环消耗步数预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 500))
        try {
            engine.registry.defineGlobal(
                "bigArray",
                JSArray(
                    MutableList(20_000) { JSNumber(0.0) },
                    engine.intrinsics.arrayPrototype,
                ),
            )
            assertFailsWith<ExecutionLimitException> { engine.run("JSON.stringify(bigArray);") }

            val jsonText = buildString {
                append('[')
                repeat(20_000) {
                    if (it > 0) append(',')
                    append('0')
                }
                append(']')
            }
            engine.registry.defineGlobal("jsonText", JSString(jsonText))
            assertFailsWith<ExecutionLimitException> { engine.run("JSON.parse(jsonText);") }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `Map 与 Set 长迭代消耗步数预算`() = runTest {
        val engine = Engine(limits = EngineLimits(maxSteps = 500))
        try {
            val map = JSMap(engine.intrinsics.mapPrototype)
            repeat(20_000) { map.setEntry(JSNumber(it.toDouble()), JSNumber(it.toDouble())) }
            engine.registry.defineGlobal("bigMap", map)
            assertFailsWith<ExecutionLimitException> {
                engine.run("bigMap.forEach(function () {});")
            }

            val set = com.muedsa.js.runtime.value.JSSet(engine.intrinsics.setPrototype)
            repeat(20_000) { set.addValue(JSNumber(it.toDouble())) }
            engine.registry.defineGlobal("bigSet", set)
            assertFailsWith<ExecutionLimitException> {
                engine.run("bigSet.forEach(function () {});")
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `事件循环单轮微任务预算超限抛出资源异常`() = runTest {
        val engine = Engine(limits = EngineLimits(maxMicrotasksPerRun = 10))
        try {
            var executed = 0
            lateinit var task: suspend () -> Unit
            task = {
                executed++
                engine.eventLoop.enqueueMicrotask(task)
            }
            engine.eventLoop.enqueueMicrotask(task)
            val error = assertFailsWith<ResourceLimitException> {
                engine.eventLoop.drainMicrotasks()
            }
            assertEquals(ResourceLimitKind.MICROTASKS_PER_RUN, error.kind)
            assertEquals("maxMicrotasksPerRun", error.limitName)
            assertEquals(10L, error.limitValue)
            assertEquals(11L, error.actualValue)
            assertEquals(10, executed)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `纯 Promise 自驱动微任务链受单轮微任务预算终止`() = runTest {
        val engine = Engine(limits = EngineLimits(maxMicrotasksPerRun = 100))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.run(
                    "function again() { Promise.resolve().then(again); } again();"
                )
            }
            assertEquals(ResourceLimitKind.MICROTASKS_PER_RUN, error.kind)
            assertEquals(100L, error.limitValue)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `async 生成器自驱动泵受单轮微任务预算终止`() = runTest {
        val engine = Engine(limits = EngineLimits(maxMicrotasksPerRun = 300))
        try {
            val error = assertFailsWith<ResourceLimitException> {
                engine.run(
                    """
                    var caught = false;
                    try {
                        (async function () {
                            const generator = (async function* () {
                                while (true) { yield 1; }
                            })();
                            for await (const value of generator) { }
                        })();
                    } catch (e) { caught = true; }
                    """.trimIndent()
                )
            }
            assertEquals(ResourceLimitKind.MICROTASKS_PER_RUN, error.kind)
            assertEquals("maxMicrotasksPerRun", error.limitName)
            assertEquals(300L, error.limitValue)
            // 微任务阶段耗尽配额，不能进入 JS catch；后续 run 使用新预算。
            assertEquals(false, assertIs<JSBoolean>(engine.run("caught")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `默认微任务预算允许正常 async await 链`() = runTest {
        val engine = Engine()
        try {
            engine.run(
                """
                var result = '';
                (async function () {
                    for (var i = 0; i < 1000; i++) { await Promise.resolve(); }
                    result = 'done';
                })();
                """.trimIndent()
            )
            assertEquals("done", assertIs<JSString>(engine.run("result")).value)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `新增配额拒绝零与负数并保留第二批默认值`() = runTest {
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxAstNodes = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxAstNodes = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxObjectCount = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxObjectCount = -1) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxMicrotasksPerRun = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxMicrotasksPerRun = -1) }

        val limits = EngineLimits()
        assertEquals(5_000_000, limits.maxAstNodes)
        assertEquals(2_000_000, limits.maxObjectCount)
        assertEquals(1_000_000, limits.maxMicrotasksPerRun)
        // 既有默认值保持不变。
        assertEquals(null, limits.maxSteps)
        assertEquals(16_384, limits.maxCallDepth)
        assertEquals(10_000_000, limits.maxSourceLength)
        assertEquals(256, limits.maxNestingDepth)
        assertEquals(1_000, limits.maxJsonDepth)
        assertEquals(10_000_000L, limits.maxRegexSteps)
        assertEquals(100_000, limits.maxPendingTasks)
    }

    @Test
    fun `默认 AST 与对象预算允许大型常规脚本`() = runTest {
        val engine = Engine()
        try {
            val source = buildString {
                append("var total = 0;")
                for (i in 1..2000) append("total += $i;")
                append("total;")
            }
            assertEquals(2001000.0, assertIs<JSNumber>(engine.run(source)).value)
        } finally {
            engine.close()
        }
    }
}
