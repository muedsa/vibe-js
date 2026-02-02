package com.muedsa.js.utils

import com.muedsa.js.runtime.value.*
import kotlinx.serialization.json.*

object JsonConverter {

    // 将 kotlinx.serialization 的 JsonElement 转换为你的 JSValue
    fun toJSValue(element: JsonElement): JSValue {
        return when (element) {
            is JsonNull -> JSNull
            is JsonPrimitive -> {
                if (element.isString) {
                    JSString(element.content)
                } else {
                    val content = element.content
                    content.toDoubleOrNull()?.let { JSNumber(it) }
                        ?: content.toBooleanStrictOrNull()?.let { JSBoolean.getJsBoolean(it) }
                        ?: JSString(content)
                }
            }
            is JsonArray -> JSArray(element.map { toJSValue(it) }.toMutableList())
            is JsonObject -> JSObject(element.mapValues { toJSValue(it.value) }.toMutableMap())
        }
    }

    // 将你的 JSValue 转换为 kotlinx.serialization 的 JsonElement
    fun toJsonElement(value: JSValue): JsonElement {
        return when (value) {
            is JSNull -> JsonNull
            is JSUndefined -> JsonNull // JSON 标准中没有 undefined，通常转为 null 或在对象中忽略
            is JSBoolean -> JsonPrimitive(value.value)
            is JSNumber -> JsonPrimitive(value.value)
            is JSString -> JsonPrimitive(value.value)
            is JSArray -> JsonArray(value.map { toJsonElement(it) })
            is JSObject -> JsonObject(value.getOwnProperties().mapValues { toJsonElement(it.value) })
            is JSFunction, is JSNativeFunction -> JsonNull // 函数无法序列化
        }
    }
}
