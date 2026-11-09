package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSTypedArrayType
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * TextEncoder / TextDecoder 的 UTF-8 子集。
 *
 * 只实现 UTF-8；TextDecoder 支持 fatal、ignoreBOM 与 stream，其他编码标签按
 * WebIDL 抛 RangeError。编码/解码均使用纯 Kotlin 实现，不依赖平台字符串编码 API
 * 的异常策略，保证 JVM / JS / Wasm 行为一致。
 */
internal fun installTextEncoding(registry: BuiltinRegistry) {
    val intrinsics = registry.intrinsics
    installTextEncoder(registry, intrinsics)
    installTextDecoder(registry, intrinsics)
}

private class TextEncoderState

private class TextDecoderState(
    val fatal: Boolean,
    val ignoreBOM: Boolean,
) {
    val decoder = Utf8Decoder(fatal = fatal, ignoreBOM = ignoreBOM)
}

private fun installTextEncoder(registry: BuiltinRegistry, intrinsics: Intrinsics) {
    val prototype = JSObject(prototype = intrinsics.objectPrototype)
    val constructor = BuiltinHelpers.constructorWithNewTarget(
        intrinsics = intrinsics,
        name = "TextEncoder",
        length = 0,
        prototypeObject = prototype,
    ) { interpreter, _, _, newTarget ->
        if (newTarget is JSUndefined) {
            interpreter.typeError("TextEncoder constructor requires 'new'")
        }
        val instancePrototype = interpreter.prototypeFromNewTarget(newTarget, prototype)
        JSObject(prototype = instancePrototype).also { it.internalState = TextEncoderState() }
    }

    prototype.defineHostGetter(intrinsics, "encoding") { interpreter, thisValue, _ ->
        requireTextEncoder(interpreter, thisValue)
        JSString("utf-8")
    }
    prototype.defineHostMethod(intrinsics, "encode", 1) { interpreter, thisValue, args ->
        requireTextEncoder(interpreter, thisValue)
        val source = if (args.getOrNull(0) == null || args[0] is JSUndefined) {
            ""
        } else {
            interpreter.toStringValue(args[0])
        }
        createUint8Array(interpreter, intrinsics, encodeUtf8Bytes(source))
    }
    prototype.defineHostMethod(intrinsics, "encodeInto", 2) { interpreter, thisValue, args ->
        requireTextEncoder(interpreter, thisValue)
        val source = if (args.getOrNull(0) == null || args[0] is JSUndefined) {
            ""
        } else {
            interpreter.toStringValue(args[0])
        }
        val destination = args.getOrNull(1) ?: JSUndefined
        if (destination !is JSTypedArray || destination.type != JSTypedArrayType.UINT8) {
            interpreter.typeError("TextEncoder.encodeInto requires a Uint8Array destination")
        }
        if (destination.buffer.isDetached) {
            interpreter.typeError("Cannot encode into a detached Uint8Array")
        }
        var readCodeUnits = 0
        var writtenBytes = 0
        for (unit in encodeUtf8Units(source)) {
            if (writtenBytes + unit.bytes.size > destination.length) break
            for (byte in unit.bytes) {
                interpreter.setTypedArrayElement(
                    destination,
                    writtenBytes,
                    JSNumber((byte.toInt() and 0xFF).toDouble()),
                )
                writtenBytes++
            }
            readCodeUnits += unit.codeUnits
        }
        JSObject(prototype = intrinsics.objectPrototype).also { result ->
            result.defineDataProperty(
                PropertyKey.Str("read"),
                JSNumber(readCodeUnits.toDouble()),
                writable = true,
                enumerable = true,
                configurable = true,
            )
            result.defineDataProperty(
                PropertyKey.Str("written"),
                JSNumber(writtenBytes.toDouble()),
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
    }
    prototype.defineHostToStringTag(intrinsics, "TextEncoder")

    registry.defineGlobal(
        name = "TextEncoder",
        value = constructor,
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

private fun installTextDecoder(registry: BuiltinRegistry, intrinsics: Intrinsics) {
    val prototype = JSObject(prototype = intrinsics.objectPrototype)
    val constructor = BuiltinHelpers.constructorWithNewTarget(
        intrinsics = intrinsics,
        name = "TextDecoder",
        length = 0,
        prototypeObject = prototype,
    ) { interpreter, _, args, newTarget ->
        if (newTarget is JSUndefined) {
            interpreter.typeError("TextDecoder constructor requires 'new'")
        }
        val labelValue = args.getOrNull(0)
        val label = if (labelValue == null || labelValue is JSUndefined) {
            "utf-8"
        } else {
            interpreter.toStringValue(labelValue)
        }
        val normalized = normalizeEncodingLabel(label)
            ?: interpreter.rangeError("The encoding label provided ('$label') is invalid")
        if (normalized != "utf-8") {
            interpreter.rangeError("TextDecoder only supports utf-8; got '$label'")
        }
        val options = args.getOrNull(1)
        val fatal = options is JSObject &&
            interpreter.toBoolean(interpreter.getV(options, PropertyKey.Str("fatal")))
        val ignoreBOM = options is JSObject &&
            interpreter.toBoolean(interpreter.getV(options, PropertyKey.Str("ignoreBOM")))
        val instancePrototype = interpreter.prototypeFromNewTarget(newTarget, prototype)
        JSObject(prototype = instancePrototype).also {
            it.internalState = TextDecoderState(fatal = fatal, ignoreBOM = ignoreBOM)
        }
    }

    prototype.defineHostGetter(intrinsics, "encoding") { interpreter, thisValue, _ ->
        requireTextDecoder(interpreter, thisValue)
        JSString("utf-8")
    }
    prototype.defineHostGetter(intrinsics, "fatal") { interpreter, thisValue, _ ->
        JSBoolean.getJsBoolean(requireTextDecoder(interpreter, thisValue).fatal)
    }
    prototype.defineHostGetter(intrinsics, "ignoreBOM") { interpreter, thisValue, _ ->
        JSBoolean.getJsBoolean(requireTextDecoder(interpreter, thisValue).ignoreBOM)
    }
    prototype.defineHostMethod(intrinsics, "decode", 0) { interpreter, thisValue, args ->
        val state = requireTextDecoder(interpreter, thisValue)
        val input = args.getOrNull(0)
        val bytes = if (input == null || input is JSUndefined) {
            ByteArray(0)
        } else {
            readArrayBufferLikeBytes(interpreter, intrinsics, input)
                ?: interpreter.typeError("TextDecoder.decode expects an ArrayBuffer or ArrayBufferView")
        }
        val options = args.getOrNull(1)
        val stream = options is JSObject &&
            interpreter.toBoolean(interpreter.getV(options, PropertyKey.Str("stream")))
        try {
            JSString(state.decoder.decode(bytes, stream))
        } catch (_: InvalidUtf8Exception) {
            interpreter.typeError("The encoded data was not valid for encoding utf-8")
        }
    }
    prototype.defineHostToStringTag(intrinsics, "TextDecoder")

    registry.defineGlobal(
        name = "TextDecoder",
        value = constructor,
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

private fun requireTextEncoder(interpreter: Interpreter, value: JSValue) {
    val objectValue = value as? JSObject
    if (objectValue?.internalState !is TextEncoderState) {
        interpreter.typeError("Receiver is not a TextEncoder")
    }
}

private fun requireTextDecoder(interpreter: Interpreter, value: JSValue): TextDecoderState {
    val objectValue = value as? JSObject
    return objectValue?.internalState as? TextDecoderState
        ?: interpreter.typeError("Receiver is not a TextDecoder")
}

/** WHATWG Encoding 标签归一化；只接受 UTF-8 的常见别名。 */
private fun normalizeEncodingLabel(label: String): String? {
    val normalized = label.trim { it == '\t' || it == '\n' || it == '\u000C' || it == '\r' || it == ' ' }
        .lowercase()
    return when (normalized) {
        "utf-8", "utf8", "unicode-1-1-utf-8", "unicode11utf8", "unicode20utf8", "x-unicode20utf8" -> "utf-8"
        else -> null
    }
}

private data class EncodedUnit(
    val codeUnits: Int,
    val bytes: ByteArray,
)

/** 按 UTF-16 码元切分编码；孤立代理项按 U+FFFD 编码。 */
private fun encodeUtf8Units(source: String): List<EncodedUnit> {
    val result = ArrayList<EncodedUnit>(source.length)
    var index = 0
    while (index < source.length) {
        val first = source[index]
        val codePoint: Int
        val codeUnits: Int
        if (first.isHighSurrogate() && index + 1 < source.length && source[index + 1].isLowSurrogate()) {
            codePoint = 0x10000 + ((first.code - 0xD800) shl 10) + (source[index + 1].code - 0xDC00)
            codeUnits = 2
        } else if (first.isSurrogate()) {
            codePoint = 0xFFFD
            codeUnits = 1
        } else {
            codePoint = first.code
            codeUnits = 1
        }
        result.add(EncodedUnit(codeUnits, encodeCodePoint(codePoint)))
        index += codeUnits
    }
    return result
}

/** 编码为 UTF-8 字节；供 TextEncoder 与 URL 百分号编码共用。 */
internal fun encodeUtf8Bytes(source: String): ByteArray {
    val units = encodeUtf8Units(source)
    var size = 0
    for (unit in units) size += unit.bytes.size
    val result = ByteArray(size)
    var offset = 0
    for (unit in units) {
        unit.bytes.copyInto(result, offset)
        offset += unit.bytes.size
    }
    return result
}

private fun encodeCodePoint(codePoint: Int): ByteArray = when {    codePoint <= 0x7F -> byteArrayOf(codePoint.toByte())
    codePoint <= 0x7FF -> byteArrayOf(
        (0xC0 or (codePoint shr 6)).toByte(),
        (0x80 or (codePoint and 0x3F)).toByte(),
    )

    codePoint <= 0xFFFF -> byteArrayOf(
        (0xE0 or (codePoint shr 12)).toByte(),
        (0x80 or ((codePoint shr 6) and 0x3F)).toByte(),
        (0x80 or (codePoint and 0x3F)).toByte(),
    )

    else -> byteArrayOf(
        (0xF0 or (codePoint shr 18)).toByte(),
        (0x80 or ((codePoint shr 12) and 0x3F)).toByte(),
        (0x80 or ((codePoint shr 6) and 0x3F)).toByte(),
        (0x80 or (codePoint and 0x3F)).toByte(),
    )
}

private class InvalidUtf8Exception : Exception()

/**
 * UTF-8 解码器。
 *
 * 非 fatal 模式按 WHATWG 的宽松策略用 U+FFFD 替换非法序列；stream 模式保留
 * 末尾不完整序列供下一次 decode 继续。非法序列的消费粒度为 1 字节，足够覆盖
 * TextDecoder 的常见流式用法。
 */
private class Utf8Decoder(
    private val fatal: Boolean,
    private val ignoreBOM: Boolean,
) {
    private var pending = ByteArray(0)
    private var bomChecked = false

    fun decode(input: ByteArray, stream: Boolean): String {
        val data = if (pending.isEmpty()) input else pending + input
        pending = ByteArray(0)
        val builder = StringBuilder(data.size)
        var index = 0

        if (!bomChecked) {
            if (ignoreBOM) {
                bomChecked = true
            } else if (stream && data.size < 3 && isBomPrefix(data)) {
                pending = data
                return ""
            } else {
                if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
                    index = 3
                }
                bomChecked = true
            }
        }

        while (index < data.size) {
            val first = data[index].toInt() and 0xFF
            when {
                first <= 0x7F -> {
                    builder.append(first.toChar())
                    index++
                }

                first in 0xC2..0xDF -> {
                    if (index + 1 >= data.size) {
                        if (stream) {
                            pending = data.copyOfRange(index, data.size)
                            break
                        }
                        appendReplacement(builder)
                        index = data.size
                        break
                    }
                    val second = data[index + 1].toInt() and 0xFF
                    if (second !in 0x80..0xBF) {
                        invalid(builder)
                        index++
                        continue
                    }
                    val codePoint = ((first and 0x1F) shl 6) or (second and 0x3F)
                    builder.append(codePoint.toChar())
                    index += 2
                }

                first in 0xE0..0xEF -> {
                    if (index + 2 >= data.size) {
                        if (stream) {
                            pending = data.copyOfRange(index, data.size)
                            break
                        }
                        appendReplacement(builder)
                        index = data.size
                        break
                    }
                    val second = data[index + 1].toInt() and 0xFF
                    val third = data[index + 2].toInt() and 0xFF
                    val validSecond = when (first) {
                        0xE0 -> second in 0xA0..0xBF
                        0xED -> second in 0x80..0x9F
                        else -> second in 0x80..0xBF
                    }
                    if (!validSecond || third !in 0x80..0xBF) {
                        invalid(builder)
                        index++
                        continue
                    }
                    val codePoint = ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or
                        (third and 0x3F)
                    builder.append(codePoint.toChar())
                    index += 3
                }

                first in 0xF0..0xF4 -> {
                    if (index + 3 >= data.size) {
                        if (stream) {
                            pending = data.copyOfRange(index, data.size)
                            break
                        }
                        appendReplacement(builder)
                        index = data.size
                        break
                    }
                    val second = data[index + 1].toInt() and 0xFF
                    val third = data[index + 2].toInt() and 0xFF
                    val fourth = data[index + 3].toInt() and 0xFF
                    val validSecond = when (first) {
                        0xF0 -> second in 0x90..0xBF
                        0xF4 -> second in 0x80..0x8F
                        else -> second in 0x80..0xBF
                    }
                    if (!validSecond || third !in 0x80..0xBF || fourth !in 0x80..0xBF) {
                        invalid(builder)
                        index++
                        continue
                    }
                    val codePoint = ((first and 0x07) shl 18) or
                        ((second and 0x3F) shl 12) or
                        ((third and 0x3F) shl 6) or
                        (fourth and 0x3F)
                    appendCodePoint(builder, codePoint)
                    index += 4
                }

                else -> {
                    invalid(builder)
                    index++
                }
            }
        }

        if (stream && pending.isEmpty() && index < data.size) {
            pending = data.copyOfRange(index, data.size)
        } else if (!stream && index < data.size) {
            appendReplacement(builder)
        }
        return builder.toString()
    }

    private fun invalid(builder: StringBuilder) {
        if (fatal) throw InvalidUtf8Exception()
        builder.append('\uFFFD')
    }

    private fun appendReplacement(builder: StringBuilder) {
        invalid(builder)
    }

    private fun isBomPrefix(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        if (data.size > bom.size) return false
        for (index in data.indices) {
            if (data[index] != bom[index]) return false
        }
        return true
    }

    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            builder.append(codePoint.toChar())
        } else {
            val offset = codePoint - 0x10000
            builder.append((0xD800 + (offset shr 10)).toChar())
            builder.append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}
