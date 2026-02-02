package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

data class JSArray(
    private val elements: MutableList<JSValue> = mutableListOf(),
) : JSObject(
    prototype = ArrayPrototype,
), MutableList<JSValue> by elements {

    override val isPrimitive: Boolean = false

    override fun toPrimitiveBoolean() = true

    override fun toPrimitiveNumber() = when {
        elements.isEmpty() -> 0.0
        elements.size == 1 -> elements[0].toPrimitiveNumber()
        else -> Double.NaN
    }

    override fun toPrimitiveString() = elements.joinToString(",") { it.toPrimitiveString() }

    override fun getProperty(key: String): JSValue {
        // 特殊处理一下length
        if (key == "length") return JSNumber(elements.size.toDouble())
        val index = key.toIntOrNull()
        if (index != null && index.toString() == key && index >= 0) {
            // arr[index]
            return if (index < elements.size) {
                elements[index]
            } else {
                JSUndefined
            }
        }
        return super.getProperty(key)
    }

    override fun setProperty(key: String, value: JSValue): JSValue {
        val index = key.toIntOrNull()
        return if (index != null && index.toString() == key && index >= 0) {
            if (index < elements.size) elements[index] = value else elements.add(index, value)
            value
        } else {
            super.setProperty(key, value)
        }
    }
}

val ArrayPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "length" to JSNumber(0.0),
            "at" to JSNativeFunction("Array.prototype.at") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.at")
                if (args.isEmpty()) {
                    return@JSNativeFunction JSUndefined
                }
                var index = interpreter.getPrimitiveNumber(args[0]).toInt()
                if (index < 0) {
                    index += arr.size
                }
                arr.elementAtOrElse(index) { JSUndefined }
            },
            "concat" to JSNativeFunction("Array.prototype.concat") { _, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.concat")
                val newArr = JSArray()
                newArr.addAll(arr)
                for (arg in args) {
                     if (arg is JSArray) {
                         newArr.addAll(arg)
                     } else {
                         newArr.add(arg)
                     }
                }
                newArr
            },
            "every" to JSNativeFunction("Array.prototype.every") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.every")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.every requires a function as the first argument"))
                }
                var result = true
                for (index in arr.indices) {
                    val el = arr[index]
                    val test = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.every" }
                    if (!test.toPrimitiveBoolean()) {
                        result = false
                        break
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
            "filter" to JSNativeFunction("Array.prototype.filter") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.filter")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.filter requires a function as the first argument"))
                }
                val result = JSArray()
                arr.forEachIndexed { index, el ->
                    val shouldKeep = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.filter" }
                    if (shouldKeep.toPrimitiveBoolean()) {
                        result.add(el)
                    }
                }
                result
            },
            "find" to JSNativeFunction("Array.prototype.find") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.find")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.find requires a function as the first argument"))
                }
                var found: JSValue = JSUndefined
                for (index in arr.indices) {
                    val el = arr[index]
                    val test = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.find" }
                    if (test.toPrimitiveBoolean()) {
                        found = el
                        break
                    }
                }
                found
            },
            "findIndex" to JSNativeFunction("Array.prototype.findIndex") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.findIndex")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.findIndex requires a function as the first argument"))
                }
                var foundIndex = -1
                for (index in arr.indices) {
                    val el = arr[index]
                    val test = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.findIndex" }
                    if (test.toPrimitiveBoolean()) {
                        foundIndex = index
                        break
                    }
                }
                JSNumber(foundIndex.toDouble())
            },
            "forEach" to JSNativeFunction("Array.prototype.forEach") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.forEach")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(
                        JSError(
                            "TypeError",
                            "Array.prototype.forEach requires a function as the first argument"
                        )
                    )
                }
                arr.forEachIndexed { index, el ->
                    interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.forEach" }
                }
                JSUndefined
            },
            "includes" to JSNativeFunction("Array.prototype.includes") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.includes")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                var fromIndex = if (args.size > 1) interpreter.getPrimitiveNumber(args[1]).toInt() else 0
                if (fromIndex < 0) fromIndex += arr.size
                if (fromIndex < 0) fromIndex = 0
                
                var found = false
                for (i in fromIndex until arr.size) {
                    if (arr[i] == searchElement) {
                        found = true
                        break
                    }
                }
                JSBoolean.getJsBoolean(found)
            },
            "indexOf" to JSNativeFunction("Array.prototype.indexOf") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.indexOf")
                val searchElement = args.getOrNull(0) ?: JSUndefined
                var fromIndex = if (args.size > 1) interpreter.getPrimitiveNumber(args[1]).toInt() else 0
                if (fromIndex < 0) fromIndex += arr.size
                if (fromIndex < 0) fromIndex = 0

                var index = -1
                for (i in fromIndex until arr.size) {
                    if (arr[i] == searchElement) {
                         index = i
                         break
                    }
                }
                JSNumber(index.toDouble())
            },
            "join" to JSNativeFunction("Array.prototype.join") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.join")
                val separator = if (args.isNotEmpty()) interpreter.getPrimitiveString(args[0]) else ","
                JSString(arr.joinToString(separator) { interpreter.getPrimitiveString(it) })
            },
            "map" to JSNativeFunction("Array.prototype.map") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.map")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.map requires a function as the first argument"))
                }
                val result = JSArray()
                arr.forEachIndexed { index, el ->
                    result.add(interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.map" })
                }
                result
            },
            "pop" to JSNativeFunction("Array.prototype.pop") { _, thisValue, _ ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.pop")
                if (arr.isEmpty()) JSUndefined else arr.removeAt(arr.size - 1)
            },
            "push" to JSNativeFunction("Array.prototype.push") { _, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.push")
                arr.addAll(args)
                JSNumber(arr.size.toDouble())
            },
            "reduce" to JSNativeFunction("Array.prototype.reduce") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.reduce")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.reduce requires a function as the first argument"))
                }
                
                if (arr.isEmpty() && args.size < 2) {
                     throw JSException(JSError("TypeError", "Reduce of empty array with no initial value"))
                }

                var accumulator: JSValue
                var startIndex: Int

                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = 0
                } else {
                    accumulator = arr[0]
                    startIndex = 1
                }

                for (i in startIndex until arr.size) {
                    accumulator = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(accumulator, arr[i], JSNumber(i.toDouble()), arr)
                    ) { "Array.prototype.reduce" }
                }
                accumulator
            },
            "reduceRight" to JSNativeFunction("Array.prototype.reduceRight") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.reduceRight")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.reduceRight requires a function as the first argument"))
                }
                
                if (arr.isEmpty() && args.size < 2) {
                     throw JSException(JSError("TypeError", "Reduce of empty array with no initial value"))
                }

                var accumulator: JSValue
                var startIndex: Int

                if (args.size >= 2) {
                    accumulator = args[1]
                    startIndex = arr.size - 1
                } else {
                    accumulator = arr[arr.size - 1]
                    startIndex = arr.size - 2
                }

                for (i in startIndex downTo 0) {
                    accumulator = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(accumulator, arr[i], JSNumber(i.toDouble()), arr)
                    ) { "Array.prototype.reduceRight" }
                }
                accumulator
            },
            "reverse" to JSNativeFunction("Array.prototype.reverse") { _, thisValue, _ ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.reverse")
                arr.reverse()
                arr
            },
            "shift" to JSNativeFunction("Array.prototype.shift") { _, thisValue, _ ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.shift")
                if (arr.isEmpty()) JSUndefined else arr.removeAt(0)
            },
            "slice" to JSNativeFunction("Array.prototype.slice") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.slice")
                val len = arr.size
                var start = if (args.isNotEmpty()) interpreter.getPrimitiveNumber(args[0]).toInt() else 0
                var end = if (args.size > 1) interpreter.getPrimitiveNumber(args[1]).toInt() else len
                
                if (start < 0) start += len
                if (start < 0) start = 0
                if (start > len) start = len
                
                if (end < 0) end += len
                if (end < 0) end = 0
                if (end > len) end = len
                
                if (end < start) end = start
                
                val newArr = JSArray()
                for (i in start until end) {
                    newArr.add(arr[i])
                }
                newArr
            },
            "some" to JSNativeFunction("Array.prototype.some") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.some")
                val callback = args.getOrNull(0)
                if (callback !is JSFunction && callback !is JSNativeFunction) {
                    throw JSException(JSError("TypeError", "Array.prototype.some requires a function as the first argument"))
                }
                var result = false
                for (index in arr.indices) {
                    val el = arr[index]
                    val test = interpreter.evaluateFunction(
                        callback,
                        JSUndefined,
                        listOf(el, JSNumber(index.toDouble()), arr)
                    ) { "Array.prototype.some" }
                    if (test.toPrimitiveBoolean()) {
                        result = true
                        break
                    }
                }
                JSBoolean.getJsBoolean(result)
            },
            "sort" to JSNativeFunction("Array.prototype.sort") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.sort")
                val compareFn = args.getOrNull(0)
                
                if (compareFn != null && compareFn !is JSFunction && compareFn !is JSNativeFunction && compareFn != JSUndefined) {
                     throw JSException(JSError("TypeError", "The comparison function must be either a function or undefined"))
                }

                if (compareFn is JSFunction || compareFn is JSNativeFunction) {
                    arr.sortWith { a, b ->
                        val res = interpreter.evaluateFunction(
                            compareFn,
                            JSUndefined,
                            listOf(a, b)
                        ) { "Array.prototype.sort" }
                        val num = res.toPrimitiveNumber()
                        if (num < 0) -1 else if (num > 0) 1 else 0
                    }
                } else {
                    arr.sortWith { a, b ->
                        val sa = interpreter.getPrimitiveString(a)
                        val sb = interpreter.getPrimitiveString(b)
                        sa.compareTo(sb)
                    }
                }
                arr
            },
            "splice" to JSNativeFunction("Array.prototype.splice") { interpreter, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.splice")
                val startArg = if (args.isNotEmpty()) interpreter.getPrimitiveNumber(args[0]).toInt() else 0
                val deleteCountArg = if (args.size > 1) interpreter.getPrimitiveNumber(args[1]).toInt() else (arr.size - startArg)
                
                var start = startArg
                if (start < 0) start += arr.size
                if (start < 0) start = 0
                if (start > arr.size) start = arr.size
                
                var deleteCount = deleteCountArg
                if (deleteCount < 0) deleteCount = 0
                if (start + deleteCount > arr.size) deleteCount = arr.size - start
                
                val deletedElements = JSArray()
                for (i in 0 until deleteCount) {
                    deletedElements.add(arr.removeAt(start))
                }
                
                val itemsToAdd = if (args.size > 2) args.subList(2, args.size) else emptyList()
                arr.addAll(start, itemsToAdd)
                
                deletedElements
            },
            "toString" to JSNativeFunction("Array.prototype.toString") { interpreter, thisValue, _ ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.toString")
                val str = arr.joinToString(",") { interpreter.getPrimitiveString(it) }
                JSString(str)
            },
            "unshift" to JSNativeFunction("Array.prototype.unshift") { _, thisValue, args ->
                val arr = convertJSValueToJSArray(thisValue, "Array.prototype.unshift")
                arr.addAll(0, args)
                JSNumber(arr.size.toDouble())
            },
            "[Symbol.toStringTag]" to JSString("Array")
        )
    },
)

val ArrayConstructor = JSNativeFunction(
    name = "Array",
    lazyProperties = lazy {
        mutableMapOf(
            "length" to JSNumber(0.0),
            "from" to JSNativeFunction("from") { _, _, args ->
                if (args.isEmpty()) {
                    JSArray()
                } else {
                    val firstArg = args[0]
                    if (firstArg is JSArray) {
                        JSArray(firstArg)
                    } else {
                        JSArray()
                    }
                }
            },
            "isArray" to JSNativeFunction("isArray") { _, _, args ->
                JSBoolean.getJsBoolean(args.getOrElse(0) { JSUndefined } is JSArray)
            },
            "of" to JSNativeFunction("of") { _, _, args ->
                JSArray(args.toMutableList())
            },
        )
    },
) { _, _, args ->
    JSArray(args.toMutableList())
}

fun convertJSValueToJSArray(value: JSValue, calleeName: String): JSArray {
    return value as? JSArray
        ?: throw JSException(JSError("TypeError", "$calleeName requires that 'this' be an Array"))
}
