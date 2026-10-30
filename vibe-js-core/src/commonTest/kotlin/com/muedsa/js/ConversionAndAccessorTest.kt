package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 类型转换、Boolean/String/Number 构造器与访问器属性边界。
 */
class ConversionAndAccessorTest {

    @Test
    fun `Boolean 转换与装箱对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(false, runtime.evalBoolean("Boolean(0)"))
        assertEquals(false, runtime.evalBoolean("Boolean('')"))
        assertEquals(false, runtime.evalBoolean("Boolean(NaN)"))
        assertEquals(true, runtime.evalBoolean("Boolean('0')"))
        assertEquals(true, runtime.evalBoolean("Boolean([])"))
        assertEquals(true, runtime.evalBoolean("Boolean({})"))
        assertEquals("object", runtime.evalString("typeof new Boolean(false)"))
        assertEquals(
            "truthy:false",
            runtime.evalString(
                "var boxed = new Boolean(false); (boxed ? 'truthy' : 'falsy') + ':' + boxed.valueOf()"
            )
        )
    }

    @Test
    fun `全局 isNaN 与 isFinite 会做类型转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("isNaN('abc')"))
        assertEquals(false, runtime.evalBoolean("isNaN('12')"))
        assertEquals(true, runtime.evalBoolean("isNaN(undefined)"))
        assertEquals(true, runtime.evalBoolean("isFinite('12')"))
        assertEquals(false, runtime.evalBoolean("isFinite('12px')"))
        assertEquals(false, runtime.evalBoolean("isFinite(Infinity)"))
    }

    @Test
    fun `String 与 Number 函数调用的转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals("123", runtime.evalString("String(123)"))
        assertEquals("null", runtime.evalString("String(null)"))
        assertEquals("undefined", runtime.evalString("String(undefined)"))
        assertEquals("1,2", runtime.evalString("String([1, 2])"))
        assertEquals("", runtime.evalString("String()"))
        assertEquals(42.0, runtime.evalNumber("Number('42')"))
        assertEquals(0.0, runtime.evalNumber("Number()"))
    }

    @Test
    fun `valueOf 与 toString 的转换优先级`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("var o = { valueOf: function () { return 2; } }; +o"))
        assertEquals(3.0, runtime.evalNumber("var o = { valueOf: function () { return 2; } }; 1 + o"))
        assertEquals(
            "p",
            runtime.evalString(
                "var o = { valueOf: function () { return {}; }, toString: function () { return 'p'; } }; '' + o"
            )
        )
        assertEquals(
            "q",
            runtime.evalString("var o = { toString: function () { return 'q'; } }; '' + o")
        )
        // String 提示优先调用 toString，Object.prototype.toString 返回默认标签
        assertEquals(
            "[object Object]",
            runtime.evalString("var o = { valueOf: function () { return 2; } }; String(o)")
        )
    }

    @Test
    fun `getter 的 this 与继承数据属性赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber("var o = { y: 2, get x() { return this.y; } }; o.x")
        )
        assertEquals(
            "true:true:1",
            runtime.evalString(
                """
                var proto = { x: 1 };
                var o = Object.create(proto);
                o.x = 2;
                o.hasOwnProperty('x') + ':' + (o.x === 2) + ':' + proto.x;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `严格模式下无 setter 的访问器赋值抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("'use strict'; var o = { get x() { return 1; } }; o.x = 2;")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
        assertEquals(
            1.0,
            runtime.evalNumber("var o = { get x() { return 1; } }; o.x = 2; o.x")
        )
    }

    @Test
    fun `new 表达式支持展开参数与嵌套数组字符串化`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber("function F(a, b) { this.sum = a + b; } new F(...[1, 2]).sum")
        )
        assertEquals("1,2,3", runtime.evalString("'' + [1, [2, 3]]"))
        assertEquals(",", runtime.evalString("String([null, undefined])"))
    }

    @Test
    fun `数组 keys 迭代器包含空洞索引`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0,1,2", runtime.evalString("[...new Array(3).keys()].join(',')"))
        assertEquals(0.0, runtime.evalNumber("Object.keys(new Array(3)).length"))
    }

    @Test
    fun `in 运算符会查找原型链`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("'x' in Object.create({ x: 1 })"))
        assertEquals(false, runtime.evalBoolean("'x' in {}"))
        assertEquals(true, runtime.evalBoolean("'toString' in {}"))
    }
}
