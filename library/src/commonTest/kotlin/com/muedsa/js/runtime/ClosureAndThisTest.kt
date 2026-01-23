package com.muedsa.js.runtime

import com.muedsa.js.createRuntime
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import kotlin.test.Test
import kotlin.test.assertEquals

class ClosureAndThisTest {

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
    fun `test closure with var in loop`() {
        // 测试经典的闭包陷阱：使用 var 声明的循环变量在闭包中共享
        // 在 JS 中，var 没有块级作用域，所以 i 是函数级/全局的。
        // 当循环结束时，i 是 3。所有闭包都引用同一个 i。
        val i = eval("""
            var funcs = [];
            for (var i = 0; i < 3; i++) {
                funcs.push(function() { return i; });
            }
            var r0 = funcs[0]();
            var r1 = funcs[1]();
            var r2 = funcs[2]();
        """.trimIndent())

        assertEquals(3.0, (i.getValue("r0") as JSNumber).value)
        assertEquals(3.0, (i.getValue("r1") as JSNumber).value)
        assertEquals(3.0, (i.getValue("r2") as JSNumber).value)
    }

    @Test
    fun `test closure with let in loop`() {
        // 测试使用 let 声明的循环变量在闭包中捕获独立的值
        val i = eval("""
            var funcs = [];
            for (let i = 0; i < 3; i++) {
                funcs.push(function() { return i; });
            }
            var r0 = funcs[0]();
            var r1 = funcs[1]();
            var r2 = funcs[2]();
        """.trimIndent())

        assertEquals(0.0, (i.getValue("r0") as JSNumber).value)
        assertEquals(1.0, (i.getValue("r1") as JSNumber).value)
        assertEquals(2.0, (i.getValue("r2") as JSNumber).value)
    }

    @Test
    fun `test this in method call`() {
        // 测试对象方法调用时 this 指向对象本身
        val i = eval("""
            var obj = {
                val: 42,
                getVal: function() { return this.val; }
            };
            var res = obj.getVal();
        """.trimIndent())
        assertEquals(42.0, (i.getValue("res") as JSNumber).value)
    }

    @Test
    fun `test this in detached method call`() {
        // 测试将方法赋值给变量后调用，this 丢失（变为 undefined）
        // 在修复后的 Interpreter 中，未绑定的 this 会被显式定义为 undefined，而不是抛出 ReferenceError。
        val i = eval("""
            var obj = {
                getThis: function() { return this; }
            };
            var f = obj.getThis;
            var isUndef = f() === undefined;
        """.trimIndent())
        assertEquals(true, (i.getValue("isUndef") as com.muedsa.js.runtime.value.JSBoolean).value)
    }

    @Test
    fun `test prototype chain basic`() {
        // 测试基本的原型链查找
        // 验证对象确实继承了 Object.prototype 上的方法（如 toString）
        val i = eval("""
            var obj = { a: 1 };
            var s = obj.toString(); 
            // ObjectPrototype 中定义了 toString 返回 [object Object]
        """.trimIndent())
        assertEquals("[object Object]", (i.getValue("s") as JSString).value)
    }
}
