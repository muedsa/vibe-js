package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.*
import com.muedsa.js.utils.JsonConverter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.io.encoding.Base64

fun Interpreter.initBrowserEnv(): Interpreter {
    val globalEnv = getGlobalEnv()
    val winObj = JSObject(
        mutableMapOf(
            "console" to JSObject(
                mutableMapOf(
                    "log" to JSNativeFunction("console.log") { interpreter, _, args ->
                        println(args.joinToString(" ") {
                            interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                        })
                        JSUndefined
                    },
                )
            ),
            "atob" to JSNativeFunction("atob") { interpreter, _, args ->
                val decodedStr = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val encodedStr = try {
                    Base64.decode(decodedStr.encodeToByteArray()).decodeToString()
                } catch (_: Throwable) {
                    throw JSException(JSError("InvalidCharacterError", "The string to be decoded is not correctly encoded"))
                }
                JSString(encodedStr)
            },
            "btoa" to JSNativeFunction("btoa") { interpreter, _, args ->
                val encodedStr = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                JSString(Base64.encode(encodedStr.encodeToByteArray()))
            },
            "JSON" to JSObject(
                mutableMapOf(
                    "parse" to JSNativeFunction("JSON.parse") { interpreter, _, args ->
                        val jsonStr = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                        try {
                            val element = Json.parseToJsonElement(jsonStr)
                            JsonConverter.toJSValue(element)
                        } catch (_: Throwable) {
                            throw JSException(JSError("SyntaxError", "\"${jsonStr}\" is not valid JSON"))
                        }
                    },
                    "stringify" to JSNativeFunction("JSON.stringify") { _, _, args ->
                        val value = args.getOrNull(0) ?: return@JSNativeFunction JSUndefined
                        val element = JsonConverter.toJsonElement(value)
                        JSString(Json.encodeToString(JsonElement.serializer(), element))
                    },
                )
            ),
            "Infinity" to NumberConstructor.getProperty("POSITIVE_INFINITY"),
            "NaN" to NumberConstructor.getProperty("NaN"),
            "isNaN" to NumberConstructor.getProperty("isNaN"),
            "isFinite" to NumberConstructor.getProperty("isFinite"),
            "parseInt" to NumberConstructor.getProperty("parseInt"),
            "parseFloat" to NumberConstructor.getProperty("isFinite"),
        )
    )

    globalEnv.define(
        name = "window",
        value = winObj,
        kind = VariableKind.CONST,
    )

    winObj.getOwnProperties().forEach {
        if (!globalEnv.hasLocal(it.key)) {
            globalEnv.define(it.key, it.value, kind = VariableKind.CONST)
        }
    }

    return this
}
