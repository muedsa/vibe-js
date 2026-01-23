package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.*

class JSArrayTest {

    @Test
    fun `JSArray should convert to primitive boolean correctly`() {
        assertEquals(true, JSArray().toPrimitiveBoolean())
        assertEquals(true, JSArray(mutableListOf(JSNumber(1.0))).toPrimitiveBoolean())
    }

    @Test
    fun `JSArray should convert to primitive number correctly`() {
        assertEquals(0.0, JSArray().toPrimitiveNumber())
        assertEquals(123.0, JSArray(mutableListOf(JSNumber(123.0))).toPrimitiveNumber())
        // Recursive check: [[123]] -> 123
        assertEquals(123.0, JSArray(mutableListOf(JSArray(mutableListOf(JSNumber(123.0))))).toPrimitiveNumber())
        assertTrue(JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0))).toPrimitiveNumber().isNaN())
    }

    @Test
    fun `JSArray should convert to primitive string correctly`() {
        assertEquals("", JSArray().toPrimitiveString())
        assertEquals("1", JSArray(mutableListOf(JSNumber(1.0))).toPrimitiveString())
        assertEquals("1,2", JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0))).toPrimitiveString())
        // Nested arrays: [1, [2, 3]] -> "1,2,3"
        assertEquals(
            "1,2,3",
            JSArray(
                mutableListOf(
                    JSNumber(1.0),
                    JSArray(mutableListOf(JSNumber(2.0), JSNumber(3.0)))
                )
            ).toPrimitiveString()
        )
    }

    @Test
    fun `JSArray should handle getting and setting properties including length`() {
        val arr = JSArray()
        assertEquals(JSNumber(0.0), arr.getProperty("length"))

        arr.setProperty("0", JSNumber(10.0))
        assertEquals(1, arr.size)
        assertEquals(JSNumber(10.0), arr.getProperty("0"))
        assertEquals(JSNumber(1.0), arr.getProperty("length"))

        arr.setProperty("1", JSNumber(20.0))
        assertEquals(2, arr.size)
        assertEquals(JSNumber(20.0), arr.getProperty("1"))
        assertEquals(JSNumber(2.0), arr.getProperty("length"))

        assertEquals(JSUndefined, arr.getProperty("2"))

        // Test modifying existing
        arr.setProperty("0", JSNumber(11.0))
        assertEquals(JSNumber(11.0), arr[0])
        assertEquals(2, arr.size)
    }

    @Test
    fun `Array constructor should create new arrays correctly`() {
        val runtime = createRuntime()

        // Array()
        var result = ArrayConstructor.function(runtime, JSUndefined, emptyList())
        assertIs<JSArray>(result)
        assertEquals(0, result.size)

        // Array(1, 2, 3)
        result = ArrayConstructor.function(runtime, JSUndefined, listOf(JSNumber(1.0), JSNumber(2.0)))
        assertIs<JSArray>(result)
        assertEquals(2, result.size)
        assertEquals(JSNumber(1.0), result[0])
        assertEquals(JSNumber(2.0), result[1])
    }

    @Test
    fun `Array_from should create array from other arrays`() {
        val runtime = createRuntime()
        val from = ArrayConstructor.getProperty("from") as JSNativeFunction

        // From Array
        val src = JSArray(mutableListOf(JSNumber(1.0)))
        val result = from.function(runtime, JSUndefined, listOf(src))
        assertIs<JSArray>(result)
        assertEquals(1, result.size)
        assertEquals(JSNumber(1.0), result[0])

        // From no args
        val empty = from.function(runtime, JSUndefined, emptyList())
        assertIs<JSArray>(empty)
        assertEquals(0, empty.size)
    }

    @Test
    fun `Array_isArray should correctly identify arrays`() {
        val runtime = createRuntime()
        val isArray = ArrayConstructor.getProperty("isArray") as JSNativeFunction

        assertEquals(JSBoolean.True, isArray.function(runtime, JSUndefined, listOf(JSArray())))
        assertEquals(JSBoolean.False, isArray.function(runtime, JSUndefined, listOf(JSObject())))
        assertEquals(JSBoolean.False, isArray.function(runtime, JSUndefined, listOf(JSNull)))
    }

    @Test
    fun `Array_of should create array from arguments`() {
        val runtime = createRuntime()
        val of = ArrayConstructor.getProperty("of") as JSNativeFunction

        val result = of.function(runtime, JSUndefined, listOf(JSNumber(1.0), JSNumber(2.0)))
        assertIs<JSArray>(result)
        assertEquals(2, result.size)
        assertEquals(JSNumber(1.0), result[0])
        assertEquals(JSNumber(2.0), result[1])
    }

    @Test
    fun `Array_prototype_at should return element at given index`() {
        val runtime = createRuntime()
        val at = ArrayPrototype.getProperty("at") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b"), JSString("c")))

        assertEquals(JSString("a"), at.function(runtime, arr, listOf(JSNumber(0.0))))
        assertEquals(JSString("c"), at.function(runtime, arr, listOf(JSNumber(-1.0))))
        assertEquals(JSUndefined, at.function(runtime, arr, listOf(JSNumber(3.0))))
        assertEquals(JSUndefined, at.function(runtime, arr, listOf(JSNumber(-4.0))))
        // Large negative index
        assertEquals(JSUndefined, at.function(runtime, arr, listOf(JSNumber(-100.0))))
    }

    @Test
    fun `Array_prototype_push should add elements and return new length`() {
        val runtime = createRuntime()
        val push = ArrayPrototype.getProperty("push") as JSNativeFunction
        val arr = JSArray()

        val newLength = push.function(runtime, arr, listOf(JSString("a"), JSString("b")))
        assertEquals(JSNumber(2.0), newLength)
        assertEquals(2, arr.size)
        assertEquals(JSString("a"), arr[0])
        assertEquals(JSString("b"), arr[1])
    }

    @Test
    fun `Array_prototype_pop should remove and return the last element`() {
        val runtime = createRuntime()
        val pop = ArrayPrototype.getProperty("pop") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b")))

        assertEquals(JSString("b"), pop.function(runtime, arr, emptyList()))
        assertEquals(1, arr.size)

        assertEquals(JSString("a"), pop.function(runtime, arr, emptyList()))
        assertEquals(0, arr.size)

        assertEquals(JSUndefined, pop.function(runtime, arr, emptyList()))
    }

    @Test
    fun `Array_prototype_forEach should iterate over all elements`() {
        val runtime = createRuntime()
        val forEach = ArrayPrototype.getProperty("forEach") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0)))

        val resultList = mutableListOf<JSValue>()
        // Mock a callback function
        val callback = JSNativeFunction("callback") { _, _, args ->
            resultList.add(args[0])
            JSUndefined
        }

        forEach.function(runtime, arr, listOf(callback))

        assertEquals(2, resultList.size)
        assertEquals(JSNumber(1.0), resultList[0])
        assertEquals(JSNumber(2.0), resultList[1])

        assertFailsWith<JSException> {
            forEach.function(runtime, arr, listOf(JSUndefined))
        }
    }

    @Test
    fun `Array_prototype_toString should return comma separated string`() {
        val runtime = createRuntime()
        val toString = ArrayPrototype.getProperty("toString") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0)))

        val result = toString.function(runtime, arr, emptyList())
        assertEquals(JSString("1,2"), result)
    }

    @Test
    fun `Array_prototype_concat should merge arrays and values`() {
        val runtime = createRuntime()
        val concat = ArrayPrototype.getProperty("concat") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0)))

        val result =
            concat.function(runtime, arr, listOf(JSNumber(2.0), JSArray(mutableListOf(JSNumber(3.0))))) as JSArray
        assertEquals(3, result.size)
        assertEquals(JSNumber(1.0), result[0])
        assertEquals(JSNumber(2.0), result[1])
        assertEquals(JSNumber(3.0), result[2])
    }

    @Test
    fun `Array_prototype_every should test all elements`() {
        val runtime = createRuntime()
        val every = ArrayPrototype.getProperty("every") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0), JSNumber(3.0)))

        val allPositive = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 0)
        }
        assertEquals(JSBoolean.True, every.function(runtime, arr, listOf(allPositive)))

        val allGreaterThanOne = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 1)
        }
        assertEquals(JSBoolean.False, every.function(runtime, arr, listOf(allGreaterThanOne)))
    }

    @Test
    fun `Array_prototype_filter should filter elements`() {
        val runtime = createRuntime()
        val filter = ArrayPrototype.getProperty("filter") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0), JSNumber(3.0)))

        val greaterThanOne = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 1)
        }
        val result = filter.function(runtime, arr, listOf(greaterThanOne)) as JSArray
        assertEquals(2, result.size)
        assertEquals(JSNumber(2.0), result[0])
        assertEquals(JSNumber(3.0), result[1])
    }

    @Test
    fun `Array_prototype_find should find element`() {
        val runtime = createRuntime()
        val find = ArrayPrototype.getProperty("find") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(10.0), JSNumber(20.0), JSNumber(30.0)))

        val callback = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 15)
        }
        val result = find.function(runtime, arr, listOf(callback))
        assertEquals(JSNumber(20.0), result)

        val notFound = JSNativeFunction("callback") { _, _, _ -> JSBoolean.False }
        assertEquals(JSUndefined, find.function(runtime, arr, listOf(notFound)))
    }

    @Test
    fun `Array_prototype_findIndex should find element index`() {
        val runtime = createRuntime()
        val findIndex = ArrayPrototype.getProperty("findIndex") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(10.0), JSNumber(20.0)))

        val callback = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 15)
        }
        val result = findIndex.function(runtime, arr, listOf(callback))
        assertEquals(JSNumber(1.0), result)

        val notFound = JSNativeFunction("callback") { _, _, _ -> JSBoolean.False }
        assertEquals(JSNumber(-1.0), findIndex.function(runtime, arr, listOf(notFound)))
    }

    @Test
    fun `Array_prototype_includes should check existence`() {
        val runtime = createRuntime()
        val includes = ArrayPrototype.getProperty("includes") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b")))

        assertEquals(JSBoolean.True, includes.function(runtime, arr, listOf(JSString("b"))))
        assertEquals(JSBoolean.False, includes.function(runtime, arr, listOf(JSString("c"))))
        assertEquals(
            JSBoolean.False,
            includes.function(runtime, arr, listOf(JSString("a"), JSNumber(1.0)))
        ) // fromIndex 1
    }

    @Test
    fun `Array_prototype_indexOf should return index`() {
        val runtime = createRuntime()
        val indexOf = ArrayPrototype.getProperty("indexOf") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b"), JSString("a")))

        assertEquals(JSNumber(0.0), indexOf.function(runtime, arr, listOf(JSString("a"))))
        assertEquals(JSNumber(1.0), indexOf.function(runtime, arr, listOf(JSString("b"))))
        assertEquals(JSNumber(2.0), indexOf.function(runtime, arr, listOf(JSString("a"), JSNumber(1.0))))
        assertEquals(JSNumber(-1.0), indexOf.function(runtime, arr, listOf(JSString("c"))))
    }

    @Test
    fun `Array_prototype_join should join elements`() {
        val runtime = createRuntime()
        val join = ArrayPrototype.getProperty("join") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b")))

        assertEquals(JSString("a,b"), join.function(runtime, arr, emptyList()))
        assertEquals(JSString("a-b"), join.function(runtime, arr, listOf(JSString("-"))))
    }

    @Test
    fun `Array_prototype_map should transform elements`() {
        val runtime = createRuntime()
        val map = ArrayPrototype.getProperty("map") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0)))

        val callback = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSNumber(num.value * 2)
        }
        val result = map.function(runtime, arr, listOf(callback)) as JSArray
        assertEquals(2, result.size)
        assertEquals(JSNumber(2.0), result[0])
        assertEquals(JSNumber(4.0), result[1])
    }

    @Test
    fun `Array_prototype_reverse should reverse array`() {
        val runtime = createRuntime()
        val reverse = ArrayPrototype.getProperty("reverse") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0)))

        val result = reverse.function(runtime, arr, emptyList()) as JSArray
        assertEquals(arr, result) // Returns same array
        assertEquals(JSNumber(2.0), arr[0])
        assertEquals(JSNumber(1.0), arr[1])
    }

    @Test
    fun `Array_prototype_shift should remove first element`() {
        val runtime = createRuntime()
        val shift = ArrayPrototype.getProperty("shift") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b")))

        assertEquals(JSString("a"), shift.function(runtime, arr, emptyList()))
        assertEquals(1, arr.size)
        assertEquals(JSString("b"), arr[0])

        assertEquals(JSString("b"), shift.function(runtime, arr, emptyList()))
        assertEquals(0, arr.size)
        assertEquals(JSUndefined, shift.function(runtime, arr, emptyList()))
    }

    @Test
    fun `Array_prototype_slice should return subarray`() {
        val runtime = createRuntime()
        val slice = ArrayPrototype.getProperty("slice") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0), JSNumber(3.0), JSNumber(4.0)))

        var result = slice.function(runtime, arr, listOf(JSNumber(1.0), JSNumber(3.0))) as JSArray
        assertEquals(2, result.size)
        assertEquals(JSNumber(2.0), result[0])
        assertEquals(JSNumber(3.0), result[1])

        // Negative indices
        result = slice.function(runtime, arr, listOf(JSNumber(-2.0))) as JSArray
        assertEquals(2, result.size)
        assertEquals(JSNumber(3.0), result[0])
        assertEquals(JSNumber(4.0), result[1])
    }

    @Test
    fun `Array_prototype_some should test if any element passes`() {
        val runtime = createRuntime()
        val some = ArrayPrototype.getProperty("some") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0)))

        val greaterThanOne = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 1)
        }
        assertEquals(JSBoolean.True, some.function(runtime, arr, listOf(greaterThanOne)))

        val greaterThanFive = JSNativeFunction("callback") { _, _, args ->
            val num = args[0] as JSNumber
            JSBoolean.getJsBoolean(num.value > 5)
        }
        assertEquals(JSBoolean.False, some.function(runtime, arr, listOf(greaterThanFive)))
    }

    @Test
    fun `Array_prototype_sort should sort elements`() {
        val runtime = createRuntime()
        val sort = ArrayPrototype.getProperty("sort") as JSNativeFunction

        // Default string sort
        var arr = JSArray(mutableListOf(JSNumber(10.0), JSNumber(2.0), JSNumber(1.0)))
        sort.function(runtime, arr, emptyList())
        assertEquals(JSNumber(1.0), arr[0])
        assertEquals(JSNumber(10.0), arr[1]) // "10" < "2"
        assertEquals(JSNumber(2.0), arr[2])

        // Custom sort
        arr = JSArray(mutableListOf(JSNumber(10.0), JSNumber(2.0), JSNumber(1.0)))
        val compareFn = JSNativeFunction("compare") { _, _, args ->
            val a = args[0] as JSNumber
            val b = args[1] as JSNumber
            JSNumber(a.value - b.value)
        }
        sort.function(runtime, arr, listOf(compareFn))
        assertEquals(JSNumber(1.0), arr[0])
        assertEquals(JSNumber(2.0), arr[1])
        assertEquals(JSNumber(10.0), arr[2])
    }

    @Test
    fun `Array_prototype_splice should remove and add elements`() {
        val runtime = createRuntime()
        val splice = ArrayPrototype.getProperty("splice") as JSNativeFunction
        val arr = JSArray(
            mutableListOf(
                JSString("a"),
                JSString("b"),
                JSString("c"),
                JSString("d")
            )
        )

        // Remove 2 elements from index 1, add "x", "y"
        // expected: ["a", "x", "y", "d"], returns ["b", "c"]
        val result =
            splice.function(runtime, arr, listOf(JSNumber(1.0), JSNumber(2.0), JSString("x"), JSString("y"))) as JSArray

        assertEquals(2, result.size)
        assertEquals(JSString("b"), result[0])
        assertEquals(JSString("c"), result[1])

        assertEquals(4, arr.size)
        assertEquals(JSString("a"), arr[0])
        assertEquals(JSString("x"), arr[1])
        assertEquals(JSString("y"), arr[2])
        assertEquals(JSString("d"), arr[3])
    }

    @Test
    fun `Array_prototype_unshift should add elements to start`() {
        val runtime = createRuntime()
        val unshift = ArrayPrototype.getProperty("unshift") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("b")))

        val newLength = unshift.function(runtime, arr, listOf(JSString("a")))
        assertEquals(JSNumber(2.0), newLength)
        assertEquals(JSString("a"), arr[0])
        assertEquals(JSString("b"), arr[1])
    }

    @Test
    fun `Array_prototype_reduce should reduce array`() {
        val runtime = createRuntime()
        val reduce = ArrayPrototype.getProperty("reduce") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSNumber(1.0), JSNumber(2.0), JSNumber(3.0)))

        // Sum
        val sumFn = JSNativeFunction("sum") { _, _, args ->
            val acc = args[0] as JSNumber
            val cur = args[1] as JSNumber
            JSNumber(acc.value + cur.value)
        }

        // With initial value
        var result = reduce.function(runtime, arr, listOf(sumFn, JSNumber(10.0)))
        assertEquals(JSNumber(16.0), result)

        // Without initial value
        result = reduce.function(runtime, arr, listOf(sumFn))
        assertEquals(JSNumber(6.0), result)
    }

    @Test
    fun `Array_prototype_reduceRight should reduce array from right`() {
        val runtime = createRuntime()
        val reduceRight = ArrayPrototype.getProperty("reduceRight") as JSNativeFunction
        val arr = JSArray(mutableListOf(JSString("a"), JSString("b"), JSString("c")))

        // Concat
        val concatFn = JSNativeFunction("concat") { _, _, args ->
            val acc = args[0] as JSString
            val cur = args[1] as JSString
            JSString(acc.value + cur.value)
        }

        // With initial value
        var result = reduceRight.function(runtime, arr, listOf(concatFn, JSString("d")))
        assertEquals(JSString("dcba"), result)

        // Without initial value
        result = reduceRight.function(runtime, arr, listOf(concatFn))
        assertEquals(JSString("cba"), result)
    }
}
