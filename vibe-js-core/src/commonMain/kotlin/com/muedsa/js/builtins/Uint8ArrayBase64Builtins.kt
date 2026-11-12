package com.muedsa.js.builtins

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Uint8Array 的 base64 / hex 扩展（ES2026 proposal，Test262 `uint8array-base64`）。
 *
 * 只安装到 `Uint8Array` 构造器与 `Uint8Array.prototype`；`fromBase64`/`fromHex`
 * 返回固定长度 Uint8Array，`setFromBase64`/`setFromHex` 返回 `{ read, written }`。
 */
internal object Uint8ArrayBase64Builtins {

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun install(
        intrinsics: Intrinsics,
        constructor: JSNativeFunction,
        prototype: JSObject,
    ) {
        constructor.defineDataProperty(
            key = PropertyKey.Str("fromBase64"),
            value = BuiltinHelpers.method(intrinsics, "fromBase64", 1) { interpreter, _, args ->
                val input = requireStringArgument(interpreter, args, "Uint8Array.fromBase64")
                val options = decodeOptions(interpreter, args.getOrNull(1))
                val decoded = decodeBase64(
                    interpreter = interpreter,
                    input = input,
                    alphabet = options.alphabet,
                    lastChunkHandling = options.lastChunkHandling,
                    maxBytes = Int.MAX_VALUE,
                    target = null,
                )
                createUint8Array(interpreter, decoded.bytes)
            },
        )
        constructor.defineDataProperty(
            key = PropertyKey.Str("fromHex"),
            value = BuiltinHelpers.method(intrinsics, "fromHex", 1) { interpreter, _, args ->
                val input = requireStringArgument(interpreter, args, "Uint8Array.fromHex")
                val decoded = decodeHex(interpreter, input, Int.MAX_VALUE)
                createUint8Array(interpreter, decoded.bytes)
            },
        )

        prototype.defineDataProperty(
            key = PropertyKey.Str("toBase64"),
            value = BuiltinHelpers.method(intrinsics, "toBase64", 0) { interpreter, thisValue, args ->
                val array = requireUint8(interpreter, thisValue, "Uint8Array.prototype.toBase64")
                val options = encodeOptions(interpreter, args.getOrNull(0))
                requireAttached(interpreter, array, "Uint8Array.prototype.toBase64")
                JSString(encodeBase64(bytesOf(interpreter, array), options.alphabet, options.omitPadding))
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("toHex"),
            value = BuiltinHelpers.method(intrinsics, "toHex", 0) { interpreter, thisValue, _ ->
                val array = requireUint8(interpreter, thisValue, "Uint8Array.prototype.toHex")
                requireAttached(interpreter, array, "Uint8Array.prototype.toHex")
                JSString(bytesOf(interpreter, array).joinToString("") { byte ->
                    byte.toString(16).padStart(2, '0')
                })
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("setFromBase64"),
            value = BuiltinHelpers.method(intrinsics, "setFromBase64", 1) { interpreter, thisValue, args ->
                val array = requireUint8(interpreter, thisValue, "Uint8Array.prototype.setFromBase64")
                if (array.buffer.isImmutable) {
                    interpreter.typeError("Cannot write to an immutable ArrayBuffer")
                }
                val input = requireStringArgument(interpreter, args, "Uint8Array.prototype.setFromBase64")
                val options = decodeOptions(interpreter, args.getOrNull(1))
                requireAttached(interpreter, array, "Uint8Array.prototype.setFromBase64")
                val decoded = decodeBase64(
                    interpreter = interpreter,
                    input = input,
                    alphabet = options.alphabet,
                    lastChunkHandling = options.lastChunkHandling,
                    maxBytes = array.length,
                    target = array,
                )
                decodeResultObject(interpreter, decoded.read, decoded.written)
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("setFromHex"),
            value = BuiltinHelpers.method(intrinsics, "setFromHex", 1) { interpreter, thisValue, args ->
                val array = requireUint8(interpreter, thisValue, "Uint8Array.prototype.setFromHex")
                if (array.buffer.isImmutable) {
                    interpreter.typeError("Cannot write to an immutable ArrayBuffer")
                }
                val input = requireStringArgument(interpreter, args, "Uint8Array.prototype.setFromHex")
                requireAttached(interpreter, array, "Uint8Array.prototype.setFromHex")
                val decoded = decodeHex(interpreter, input, array.length, array)
                decodeResultObject(interpreter, decoded.read, decoded.written)
            },
        )
    }

    private data class DecodeOptions(val alphabet: String, val lastChunkHandling: String)
    private data class EncodeOptions(val alphabet: String, val omitPadding: Boolean)
    private data class DecodeResult(
        val read: Int,
        val written: Int,
        val bytes: List<Int>,
    )

    private fun requireStringArgument(
        interpreter: Interpreter,
        args: List<JSValue>,
        name: String,
    ): String {
        val value = args.getOrElse(0) { JSUndefined }
        if (value !is JSString) {
            interpreter.typeError("$name requires a string argument")
        }
        return value.value
    }

    private suspend fun decodeOptions(interpreter: Interpreter, value: JSValue?): DecodeOptions {
        if (value == null || value is JSUndefined || value is com.muedsa.js.runtime.value.JSNull) {
            return DecodeOptions(BASE64_ALPHABET, "loose")
        }
        val options = interpreter.toObject(value)
        val alphabetValue = interpreter.getV(options, PropertyKey.Str("alphabet"))
        val alphabet = when (alphabetValue) {
            is JSUndefined -> BASE64_ALPHABET
            is JSString -> when (alphabetValue.value) {
                "base64" -> BASE64_ALPHABET
                "base64url" -> BASE64URL_ALPHABET
                else -> interpreter.typeError("Invalid alphabet option")
            }
            else -> interpreter.typeError("alphabet option must be a string")
        }
        val handlingValue = interpreter.getV(options, PropertyKey.Str("lastChunkHandling"))
        val handling = when (handlingValue) {
            is JSUndefined -> "loose"
            is JSString -> when (handlingValue.value) {
                "loose", "strict", "stop-before-partial" -> handlingValue.value
                else -> interpreter.typeError("Invalid lastChunkHandling option")
            }
            else -> interpreter.typeError("lastChunkHandling option must be a string")
        }
        return DecodeOptions(alphabet, handling)
    }

    private suspend fun encodeOptions(interpreter: Interpreter, value: JSValue?): EncodeOptions {
        if (value == null || value is JSUndefined || value is com.muedsa.js.runtime.value.JSNull) {
            return EncodeOptions(BASE64_ALPHABET, false)
        }
        val options = interpreter.toObject(value)
        val alphabetValue = interpreter.getV(options, PropertyKey.Str("alphabet"))
        val alphabet = when (alphabetValue) {
            is JSUndefined -> BASE64_ALPHABET
            is JSString -> when (alphabetValue.value) {
                "base64" -> BASE64_ALPHABET
                "base64url" -> BASE64URL_ALPHABET
                else -> interpreter.typeError("Invalid alphabet option")
            }
            else -> interpreter.typeError("alphabet option must be a string")
        }
        val omitPadding = interpreter.toBoolean(
            interpreter.getV(options, PropertyKey.Str("omitPadding")),
        )
        return EncodeOptions(alphabet, omitPadding)
    }

    private fun requireUint8(interpreter: Interpreter, value: JSValue, name: String): JSTypedArray {
        val array = value as? JSTypedArray
            ?: interpreter.typeError("$name requires that 'this' be a Uint8Array")
        if (array.type != JSTypedArrayType.UINT8) {
            interpreter.typeError("$name requires that 'this' be a Uint8Array")
        }
        return array
    }

    private fun requireAttached(interpreter: Interpreter, array: JSTypedArray, name: String) {
        if (array.isDetached) {
            interpreter.typeError("$name called on a Uint8Array with a detached buffer")
        }
    }

    private suspend fun createUint8Array(interpreter: Interpreter, bytes: List<Int>): JSTypedArray {
        val buffer = JSArrayBuffer(
            prototype = interpreter.intrinsics.arrayBufferPrototype,
            byteLength = bytes.size,
        )
        val array = JSTypedArray(
            prototype = interpreter.intrinsics.typedArrayPrototypes.getValue(JSTypedArrayType.UINT8),
            type = JSTypedArrayType.UINT8,
            buffer = buffer,
            byteOffset = 0,
            length = bytes.size,
        )
        array.attachAllocationBudget(interpreter.context.objectAllocations)
        for (index in bytes.indices) {
            interpreter.setTypedArrayElement(array, index, JSNumber(bytes[index].toDouble()))
        }
        return array
    }

    private suspend fun bytesOf(interpreter: Interpreter, array: JSTypedArray): List<Int> {
        val bytes = ArrayList<Int>(array.length)
        for (index in 0 until array.length) {
            interpreter.checkExecution()
            val value = array.getElement(index)
            bytes.add(if (value is JSNumber) value.value.toInt() and 0xFF else 0)
        }
        return bytes
    }

    private fun decodeResultObject(interpreter: Interpreter, read: Int, written: Int): JSObject {
        val result = JSObject(interpreter.intrinsics.objectPrototype)
        result.defineDataProperty(
            key = PropertyKey.Str("read"),
            value = JSNumber(read.toDouble()),
            writable = true,
            enumerable = true,
            configurable = true,
        )
        result.defineDataProperty(
            key = PropertyKey.Str("written"),
            value = JSNumber(written.toDouble()),
            writable = true,
            enumerable = true,
            configurable = true,
        )
        result.attachAllocationBudget(interpreter.context.objectAllocations)
        return result
    }

    private suspend fun decodeBase64(
        interpreter: Interpreter,
        input: String,
        alphabet: String,
        lastChunkHandling: String,
        maxBytes: Int,
        target: JSTypedArray?,
    ): DecodeResult {
        val collected = if (target == null) ArrayList<Int>() else null
        var read = 0
        var written = 0

        if (maxBytes == 0) {
            return DecodeResult(0, 0, emptyList())
        }

        suspend fun emit(bytes: List<Int>) {
            if (target == null) {
                collected!!.addAll(bytes)
            } else {
                for (offset in bytes.indices) {
                    interpreter.setTypedArrayElement(
                        target,
                        written + offset,
                        JSNumber(bytes[offset].toDouble()),
                    )
                }
            }
        }

        var index = 0
        while (true) {
            interpreter.checkExecution()
            while (index < input.length && isAsciiWhitespace(input[index])) index++
            if (index >= input.length) {
                read = index
                break
            }
            val chunkStart = index
            val values = IntArray(4) { -1 }
            var count = 0
            var sawPadding = false
            while (count < 4 && index < input.length) {
                val char = input[index++]
                if (isAsciiWhitespace(char)) continue
                if (char == '=') {
                    values[count++] = -2
                    sawPadding = true
                } else {
                    val value = alphabet.indexOf(char)
                    if (value < 0) {
                        interpreter.syntaxError("Invalid base64 character")
                    }
                    values[count++] = value
                }
            }
            if (count == 0) {
                read = index
                break
            }
            if (count < 4) {
                val dataCount = values.take(count).count { it != -2 }
                if (lastChunkHandling == "stop-before-partial") {
                    if (dataCount == 0 || (sawPadding && dataCount < 2)) {
                        interpreter.syntaxError("Invalid base64 string")
                    }
                    read = chunkStart
                    break
                }
                if (dataCount < 2 || sawPadding || lastChunkHandling == "strict") {
                    interpreter.syntaxError("Invalid base64 string")
                }
                val output = decodeValues(values, count - 1)
                if (written + output.size > maxBytes) {
                    read = chunkStart
                    break
                }
                emit(output)
                written += output.size
                read = index
                break
            }

            val padding = values.count { it == -2 }
            if (values[0] == -2 || values[1] == -2 || (values[2] == -2 && values[3] != -2)) {
                interpreter.syntaxError("Invalid base64 padding")
            }
            if (padding > 2) {
                interpreter.syntaxError("Invalid base64 padding")
            }
            val dataCount = 4 - padding
            if (dataCount == 1) {
                interpreter.syntaxError("Invalid base64 padding")
            }
            if (padding > 0 && lastChunkHandling == "strict") {
                val lowBitsMask = if (dataCount == 2) 0x0F else 0x03
                if (values[dataCount - 1] and lowBitsMask != 0) {
                    interpreter.syntaxError("Invalid base64 padding bits")
                }
            }
            val output = decodeValues(values, dataCount - 1)
            if (written + output.size > maxBytes) {
                read = chunkStart
                break
            }
            if (padding > 0) {
                var next = index
                while (next < input.length && isAsciiWhitespace(input[next])) next++
                if (next < input.length) {
                    interpreter.syntaxError("Trailing characters after base64 padding")
                }
            }
            emit(output)
            written += output.size
            read = index
            if (written == maxBytes) break
        }
        return DecodeResult(read, written, collected.orEmpty())
    }

    private fun decodeValues(values: IntArray, outputCount: Int): List<Int> {
        if (outputCount <= 0) return emptyList()
        val first = values[0]
        val second = values[1]
        val result = ArrayList<Int>(outputCount)
        result.add(((first shl 2) or (second shr 4)) and 0xFF)
        if (outputCount >= 2) {
            result.add((((second and 0x0F) shl 4) or (values[2] shr 2)) and 0xFF)
        }
        if (outputCount >= 3) {
            result.add((((values[2] and 0x03) shl 6) or values[3]) and 0xFF)
        }
        return result
    }

    private suspend fun decodeHex(
        interpreter: Interpreter,
        input: String,
        maxBytes: Int,
        target: JSTypedArray? = null,
    ): DecodeResult {
        if (input.length % 2 != 0) {
            interpreter.syntaxError("Invalid hex string length")
        }
        val bytes = ArrayList<Int>()
        var index = 0
        while (index < input.length) {
            interpreter.checkExecution()
            val first = hexValue(input[index])
            val second = hexValue(input[index + 1])
            if (first < 0 || second < 0) {
                interpreter.syntaxError("Invalid hex character")
            }
            if (bytes.size >= maxBytes) break
            val byte = (first shl 4) or second
            bytes.add(byte)
            if (target != null) {
                interpreter.setTypedArrayElement(
                    target,
                    bytes.size - 1,
                    JSNumber(byte.toDouble()),
                )
            }
            index += 2
        }
        return DecodeResult(index, bytes.size, bytes)
    }

    private fun encodeBase64(bytes: List<Int>, alphabet: String, omitPadding: Boolean): String {
        val result = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++]
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++] else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++] else 0
            result.append(alphabet[first shr 2])
            result.append(alphabet[((first and 0x03) shl 4) or (second shr 4)])
            if (hasSecond) {
                result.append(alphabet[((second and 0x0F) shl 2) or (third shr 6)])
            } else if (!omitPadding) {
                result.append('=')
            }
            if (hasThird) {
                result.append(alphabet[third and 0x3F])
            } else if (!omitPadding) {
                result.append('=')
            }
        }
        return result.toString()
    }

    private fun isAsciiWhitespace(char: Char): Boolean =
        char == ' ' || char == '\t' || char == '\n' || char == '\u000C' || char == '\r'

    private fun hexValue(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
