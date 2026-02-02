package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlFlowTest {

    private fun eval(code: String): Interpreter {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        val program = parser.parse()
        val interpreter = createRuntime()
        interpreter.interpret(program)
        return interpreter
    }

    @Test
    fun `test if else`() {
        // 测试基础的 if-else 分支逻辑
        val i = eval("""
            var a = 1;
            var res;
            if (a > 0) {
                res = "positive";
            } else {
                res = "non-positive";
            }
        """.trimIndent())
        assertEquals("positive", (i.getValue("res") as JSString).value)
    }

    @Test
    fun `test while loop`() {
        // 测试 while 循环累加
        val i = eval("""
            var i = 0;
            var sum = 0;
            while (i < 5) {
                sum += i;
                i++;
            }
        """.trimIndent())
        // 0+1+2+3+4 = 10
        assertEquals(10.0, (i.getValue("sum") as JSNumber).value)
    }

    @Test
    fun `test for loop`() {
        // 测试 for 循环累加 (包含初始化、条件和更新表达式)
        val i = eval("""
            var sum = 0;
            for (var k = 1; k <= 5; k++) {
                sum += k;
            }
        """.trimIndent())
        // 1+2+3+4+5 = 15
        assertEquals(15.0, (i.getValue("sum") as JSNumber).value)
    }

    @Test
    fun `test break continue`() {
        // 测试 break 和 continue 在单层循环中的行为
        val i = eval("""
            var sum = 0;
            for (var i = 0; i < 10; i++) {
                if (i == 2) continue; // 跳过 2
                if (i == 5) break;    // 在 5 处停止
                sum += i;
            }
        """.trimIndent())
        // i=0 -> sum=0
        // i=1 -> sum=1
        // i=2 -> continue
        // i=3 -> sum=1+3=4
        // i=4 -> sum=4+4=8
        // i=5 -> break
        assertEquals(8.0, (i.getValue("sum") as JSNumber).value)
    }

    @Test
    fun `test switch`() {
        // 测试 switch-case 语句的基本匹配和 break
        val i = eval("""
            var x = 2;
            var res;
            switch(x) {
                case 1: res = "one"; break;
                case 2: res = "two"; break;
                default: res = "other";
            }
        """.trimIndent())
        assertEquals("two", (i.getValue("res") as JSString).value)
    }

    @Test
    fun `test switch fallthrough`() {
        // 测试 switch 语句的贯穿 (Fallthrough) 行为（无 break 时继续执行下一个 case）
         val i = eval("""
            var x = 1;
            var count = 0;
            switch(x) {
                case 1: count++;
                case 2: count++; break;
                default: count = 0;
            }
        """.trimIndent())
        assertEquals(2.0, (i.getValue("count") as JSNumber).value)
    }

    @Test
    fun `test nested loop break`() {
        // 测试嵌套循环中的 break 仅跳出内层循环
        val i = eval("""
            var count = 0;
            for (var i = 0; i < 3; i++) {
                for (var j = 0; j < 3; j++) {
                    count++;
                    if (j == 1) break;
                }
            }
        """.trimIndent())
        // i=0: j=0(count=1), j=1(count=2, break)
        // i=1: j=0(count=3), j=1(count=4, break)
        // i=2: j=0(count=5), j=1(count=6, break)
        assertEquals(6.0, (i.getValue("count") as JSNumber).value)
    }
}
