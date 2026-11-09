package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * URL / URLSearchParams 的宿主子集。
 *
 * 支持 http/https/ws/wss/file 的绝对 URL、相对 base 解析、常用组件访问器与
 * `URLSearchParams` 的 get/set/append/delete/has/getAll/sort/forEach/迭代器。
 * 不实现 IDNA、IPv6 规范化、URL 编码的完整 WHATWG 状态机与 transfer 等扩展；
 * 路径按 `./`/`../` 做基础规范化，百分号编码采用 UTF-8。
 */
internal fun installUrl(registry: BuiltinRegistry) {
    val intrinsics = registry.intrinsics
    val searchParamsPrototype = JSObject(prototype = intrinsics.objectPrototype)
    val urlPrototype = JSObject(prototype = intrinsics.objectPrototype)

    installUrlSearchParams(registry, intrinsics, searchParamsPrototype)
    installUrlConstructor(registry, intrinsics, urlPrototype, searchParamsPrototype)
}

private class UrlParseException(message: String) : Exception(message)

private class UrlState(
    var scheme: String,
    var username: String = "",
    var password: String = "",
    var host: String = "",
    var port: Int? = null,
    var path: String = "/",
    var query: String? = null,
    var fragment: String? = null,
    var hasAuthority: Boolean = true,
) {
    var searchParamsObject: JSObject? = null
    var searchParamsState: SearchParamsState? = null

    fun href(): String = buildString {
        append(scheme)
        append(':')
        if (hasAuthority) {
            append("//")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                append(percentEncode(username))
                if (password.isNotEmpty()) {
                    append(':')
                    append(percentEncode(password))
                }
                append('@')
            }
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
        }
        append(path)
        if (query != null) {
            append('?')
            append(query)
        }
        if (fragment != null) {
            append('#')
            append(fragment)
        }
    }

    fun origin(): String = when (scheme) {
        "http", "https", "ws", "wss" -> buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != null) {
                append(':')
                append(port)
            }
        }

        else -> "null"
    }

    fun hostWithPort(): String = if (port == null) host else "$host:$port"

    fun updateQuery(newQuery: String?) {
        query = newQuery
        val params = searchParamsState ?: return
        params.entries.clear()
        params.entries.addAll(parseQuery(newQuery ?: ""))
    }

    fun replaceWith(other: UrlState) {
        scheme = other.scheme
        username = other.username
        password = other.password
        host = other.host
        port = other.port
        path = other.path
        query = other.query
        fragment = other.fragment
        hasAuthority = other.hasAuthority
        val params = searchParamsState ?: return
        params.entries.clear()
        params.entries.addAll(parseQuery(query ?: ""))
    }
}

private class UrlObject(
    prototype: JSObject,
    var state: UrlState,
) : JSObject(prototype) {
    init {
        internalState = state
    }

    override fun toPrimitiveString(): String = state.href()
}

private class SearchParamsState(
    val entries: MutableList<Pair<String, String>>,
    var url: UrlState? = null,
) {
    fun syncToUrl() {
        url?.query = serializeQuery(entries).ifEmpty { null }
    }
}

private class SearchParamsObject(
    prototype: JSObject,
    var state: SearchParamsState,
) : JSObject(prototype) {
    init {
        internalState = state
    }

    override fun toPrimitiveString(): String = serializeQuery(state.entries)
}

private fun installUrlConstructor(
    registry: BuiltinRegistry,
    intrinsics: Intrinsics,
    urlPrototype: JSObject,
    searchParamsPrototype: JSObject,
) {
    val constructor = BuiltinHelpers.constructorWithNewTarget(
        intrinsics = intrinsics,
        name = "URL",
        length = 1,
        prototypeObject = urlPrototype,
    ) { interpreter, _, args, newTarget ->
        if (newTarget is JSUndefined) {
            interpreter.typeError("URL constructor requires 'new'")
        }
        val inputValue = args.getOrNull(0)
            ?: interpreter.typeError("URL constructor requires an input")
        val input = interpreter.toStringValue(inputValue)
        val baseValue = args.getOrNull(1)
        val base = if (baseValue == null || baseValue is JSUndefined) {
            null
        } else {
            val baseObject = baseValue as? JSObject
            val existing = baseObject?.internalState as? UrlState
            existing ?: try {
                parseUrl(interpreter.toStringValue(baseValue), null)
            } catch (_: UrlParseException) {
                interpreter.typeError("Invalid base URL")
            }
        }
        val state = try {
            parseUrl(input, base)
        } catch (_: UrlParseException) {
            interpreter.typeError("Invalid URL: $input")
        }
        val instancePrototype = interpreter.prototypeFromNewTarget(newTarget, urlPrototype)
        UrlObject(instancePrototype, state)
    }

    urlPrototype.defineHostAccessor(
        intrinsics,
        "href",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).href()) },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val input = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            val parsed = try {
                parseUrl(input, state)
            } catch (_: UrlParseException) {
                interpreter.typeError("Invalid URL: $input")
            }
            state.replaceWith(parsed)
            JSUndefined
        },
    )
    urlPrototype.defineHostGetter(intrinsics, "origin") { interpreter, thisValue, _ ->
        JSString(requireUrl(interpreter, thisValue).origin())
    }
    urlPrototype.defineHostAccessor(
        intrinsics,
        "protocol",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).scheme + ":") },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val value = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            val scheme = value.removeSuffix(":").lowercase()
            if (!isValidScheme(scheme)) interpreter.typeError("Invalid URL protocol")
            state.scheme = scheme
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "host",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).hostWithPort()) },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val authority = try {
                parseAuthority(args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: "")
            } catch (_: UrlParseException) {
                interpreter.typeError("Invalid URL host")
            }
            state.host = authority.host
            state.port = authority.port?.takeUnless { it == defaultPort(state.scheme) }
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "hostname",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).host) },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val value = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            state.host = value.lowercase()
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "port",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).port?.toString() ?: "") },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val raw = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            state.port = parsePort(raw, state.scheme)
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "pathname",
        get = { interpreter, thisValue, _ -> JSString(requireUrl(interpreter, thisValue).path) },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val value = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            state.path = normalizePath(if (value.startsWith("/")) value else "/$value")
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "search",
        get = { interpreter, thisValue, _ ->
            val query = requireUrl(interpreter, thisValue).query
            JSString(if (query.isNullOrEmpty()) "" else "?$query")
        },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val value = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            state.updateQuery(value.removePrefix("?").ifEmpty { null })
            JSUndefined
        },
    )
    urlPrototype.defineHostAccessor(
        intrinsics,
        "hash",
        get = { interpreter, thisValue, _ ->
            val fragment = requireUrl(interpreter, thisValue).fragment
            JSString(if (fragment == null) "" else "#$fragment")
        },
        set = { interpreter, thisValue, args ->
            val state = requireUrl(interpreter, thisValue)
            val value = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: ""
            state.fragment = value.removePrefix("#").ifEmpty { null }
            JSUndefined
        },
    )
    urlPrototype.defineHostGetter(intrinsics, "username") { interpreter, thisValue, _ ->
        JSString(requireUrl(interpreter, thisValue).username)
    }
    urlPrototype.defineHostGetter(intrinsics, "password") { interpreter, thisValue, _ ->
        JSString(requireUrl(interpreter, thisValue).password)
    }
    urlPrototype.defineHostGetter(intrinsics, "searchParams") { interpreter, thisValue, _ ->
        val state = requireUrl(interpreter, thisValue)
        state.searchParamsObject?.let { return@defineHostGetter it }
        val paramsState = SearchParamsState(parseQuery(state.query ?: "").toMutableList(), state)
        state.searchParamsState = paramsState
        SearchParamsObject(searchParamsPrototype, paramsState).also {
            state.searchParamsObject = it
        }
    }
    urlPrototype.defineHostMethod(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
        JSString(requireUrl(interpreter, thisValue).href())
    }
    urlPrototype.defineHostMethod(intrinsics, "toJSON", 0) { interpreter, thisValue, _ ->
        JSString(requireUrl(interpreter, thisValue).href())
    }
    urlPrototype.defineHostToStringTag(intrinsics, "URL")

    registry.defineGlobal(
        name = "URL",
        value = constructor,
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

private fun installUrlSearchParams(
    registry: BuiltinRegistry,
    intrinsics: Intrinsics,
    prototype: JSObject,
) {
    val constructor = BuiltinHelpers.constructorWithNewTarget(
        intrinsics = intrinsics,
        name = "URLSearchParams",
        length = 0,
        prototypeObject = prototype,
    ) { interpreter, _, args, newTarget ->
        if (newTarget is JSUndefined) {
            interpreter.typeError("URLSearchParams constructor requires 'new'")
        }
        val init = args.getOrNull(0)
        val entries = when {
            init == null || init is JSUndefined -> mutableListOf()
            init is JSObject && init.internalState is SearchParamsState ->
                (init.internalState as SearchParamsState).entries.toMutableList()

            else -> parseQuery(interpreter.toStringValue(init).removePrefix("?")).toMutableList()
        }
        val instancePrototype = interpreter.prototypeFromNewTarget(newTarget, prototype)
        SearchParamsObject(instancePrototype, SearchParamsState(entries, null))
    }

    prototype.defineHostGetter(intrinsics, "size") { interpreter, thisValue, _ ->
        JSNumber(requireSearchParams(interpreter, thisValue).entries.size.toDouble())
    }
    prototype.defineHostMethod(intrinsics, "append", 2) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        state.entries.add(
            interpreter.toStringValue(args.getOrElse(0) { JSUndefined }) to
                interpreter.toStringValue(args.getOrElse(1) { JSUndefined }),
        )
        state.syncToUrl()
        JSUndefined
    }
    prototype.defineHostMethod(intrinsics, "delete", 1) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val name = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
        val value = args.getOrNull(1)
        if (value == null || value is JSUndefined) {
            state.entries.removeAll { it.first == name }
        } else {
            val expected = interpreter.toStringValue(value)
            state.entries.removeAll { it.first == name && it.second == expected }
        }
        state.syncToUrl()
        JSUndefined
    }
    prototype.defineHostMethod(intrinsics, "get", 1) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val name = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
        state.entries.firstOrNull { it.first == name }?.let { JSString(it.second) } ?: JSNull
    }
    prototype.defineHostMethod(intrinsics, "getAll", 1) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val name = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
        JSArray(
            initialElements = state.entries.filter { it.first == name }.map { JSString(it.second) },
            prototype = intrinsics.arrayPrototype,
        )
    }
    prototype.defineHostMethod(intrinsics, "has", 1) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val name = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
        val value = args.getOrNull(1)
        val present = if (value == null || value is JSUndefined) {
            state.entries.any { it.first == name }
        } else {
            val expected = interpreter.toStringValue(value)
            state.entries.any { it.first == name && it.second == expected }
        }
        JSBoolean.getJsBoolean(present)
    }
    prototype.defineHostMethod(intrinsics, "set", 2) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val name = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
        val value = interpreter.toStringValue(args.getOrElse(1) { JSUndefined })
        val firstIndex = state.entries.indexOfFirst { it.first == name }
        if (firstIndex < 0) {
            state.entries.add(name to value)
        } else {
            state.entries[firstIndex] = name to value
            var index = state.entries.lastIndex
            while (index > firstIndex) {
                if (state.entries[index].first == name) state.entries.removeAt(index)
                index--
            }
        }
        state.syncToUrl()
        JSUndefined
    }
    prototype.defineHostMethod(intrinsics, "sort", 0) { interpreter, thisValue, _ ->
        val state = requireSearchParams(interpreter, thisValue)
        val sorted = state.entries.sortedBy { it.first }
        state.entries.clear()
        state.entries.addAll(sorted)
        state.syncToUrl()
        JSUndefined
    }
    prototype.defineHostMethod(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
        JSString(serializeQuery(requireSearchParams(interpreter, thisValue).entries))
    }
    prototype.defineHostMethod(intrinsics, "forEach", 1) { interpreter, thisValue, args ->
        val state = requireSearchParams(interpreter, thisValue)
        val callback = args.getOrNull(0) ?: interpreter.typeError("forEach requires a callback")
        if (!interpreter.isCallable(callback)) interpreter.typeError("forEach requires a callback")
        val thisArg = args.getOrElse(1) { JSUndefined }
        for ((name, value) in state.entries.toList()) {
            interpreter.callFunction(
                callback,
                thisArg,
                listOf(JSString(value), JSString(name), thisValue),
            )
        }
        JSUndefined
    }
    prototype.defineHostMethod(intrinsics, "keys", 0) { interpreter, thisValue, _ ->
        createIterator(
            intrinsics,
            requireSearchParams(interpreter, thisValue).entries.map { JSString(it.first) },
        )
    }
    prototype.defineHostMethod(intrinsics, "values", 0) { interpreter, thisValue, _ ->
        createIterator(
            intrinsics,
            requireSearchParams(interpreter, thisValue).entries.map { JSString(it.second) },
        )
    }
    prototype.defineHostMethod(intrinsics, "entries", 0) { interpreter, thisValue, _ ->
        createIterator(
            intrinsics,
            requireSearchParams(interpreter, thisValue).entries.map {
                JSArray(
                    initialElements = listOf(JSString(it.first), JSString(it.second)),
                    prototype = intrinsics.arrayPrototype,
                )
            },
        )
    }
    prototype.defineHostToStringTag(intrinsics, "URLSearchParams")

    registry.defineGlobal(
        name = "URLSearchParams",
        value = constructor,
        writable = true,
        enumerable = false,
        configurable = true,
    )
}

private fun requireUrl(interpreter: Interpreter, value: JSValue): UrlState {
    val state = (value as? JSObject)?.internalState
    return state as? UrlState ?: interpreter.typeError("Receiver is not a URL")
}

private fun requireSearchParams(interpreter: Interpreter, value: JSValue): SearchParamsState {
    val state = (value as? JSObject)?.internalState
    return state as? SearchParamsState ?: interpreter.typeError("Receiver is not URLSearchParams")
}

private fun createIterator(intrinsics: Intrinsics, values: List<JSValue>): JSObject {
    var index = 0
    val iterator = JSObject(prototype = intrinsics.objectPrototype)
    iterator.defineHostMethod(intrinsics, "next", 0) { _, _, _ ->
        val result = JSObject(prototype = intrinsics.objectPrototype)
        if (index < values.size) {
            result.defineDataProperty(
                PropertyKey.Str("value"),
                values[index++],
                writable = true,
                enumerable = true,
                configurable = true,
            )
            result.defineDataProperty(
                PropertyKey.Str("done"),
                JSBoolean.FALSE,
                writable = true,
                enumerable = true,
                configurable = true,
            )
        } else {
            result.defineDataProperty(
                PropertyKey.Str("value"),
                JSUndefined,
                writable = true,
                enumerable = true,
                configurable = true,
            )
            result.defineDataProperty(
                PropertyKey.Str("done"),
                JSBoolean.TRUE,
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
        result
    }
    iterator.defineDataProperty(
        PropertyKey.Sym(intrinsics.symbolIterator),
        BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { _, _, _ -> iterator },
        writable = true,
        enumerable = false,
        configurable = true,
    )
    return iterator
}

private data class Authority(
    val username: String,
    val password: String,
    val host: String,
    val port: Int?,
)

private fun parseUrl(input: String, base: UrlState?): UrlState {
    val text = input.trim { it <= ' ' }
    if (text.isEmpty()) {
        return base?.copyState() ?: throw UrlParseException("empty URL")
    }
    val schemeEnd = schemeEnd(text)
    if (schemeEnd > 0) {
        val scheme = text.substring(0, schemeEnd).lowercase()
        val rest = text.substring(schemeEnd + 1)
        if (!isValidScheme(scheme)) throw UrlParseException("invalid scheme")
        return if (rest.startsWith("//")) {
            parseAuthorityUrl(scheme, rest.substring(2))
        } else {
            parseOpaqueUrl(scheme, rest)
        }
    }
    val baseState = base ?: throw UrlParseException("relative URL without base")
    return resolveRelative(baseState, text)
}

private fun schemeEnd(text: String): Int {
    if (text.isEmpty() || !isAsciiLetter(text[0])) return -1
    var index = 1
    while (index < text.length) {
        val character = text[index]
        if (character == ':') return index
        if (!(isAsciiLetter(character) || character.isDigit() || character == '+' || character == '-' || character == '.')) {
            return -1
        }
        index++
    }
    return -1
}

private fun isAsciiLetter(character: Char): Boolean =
    character in 'a'..'z' || character in 'A'..'Z'

private fun isValidScheme(scheme: String): Boolean {
    if (scheme.isEmpty() || !isAsciiLetter(scheme[0])) return false
    return scheme.all { isAsciiLetter(it) || it.isDigit() || it == '+' || it == '-' || it == '.' }
}

private fun parseAuthorityUrl(scheme: String, raw: String): UrlState {
    val (authorityText, tail) = splitAuthorityTail(raw)
    val authority = parseAuthority(authorityText)
    val (path, query, fragment) = splitPathQueryFragment(tail)
    if (authority.host.isEmpty() && scheme != "file") {
        throw UrlParseException("missing host")
    }
    return UrlState(
        scheme = scheme,
        username = authority.username,
        password = authority.password,
        host = authority.host,
        port = authority.port?.takeUnless { it == defaultPort(scheme) },
        path = normalizePath(path.ifEmpty { "/" }),
        query = query,
        fragment = fragment,
        hasAuthority = true,
    )
}

private fun parseOpaqueUrl(scheme: String, raw: String): UrlState {
    val (path, query, fragment) = splitPathQueryFragment(raw)
    return UrlState(
        scheme = scheme,
        host = "",
        path = path,
        query = query,
        fragment = fragment,
        hasAuthority = false,
    )
}

private fun resolveRelative(base: UrlState, input: String): UrlState {
    if (!base.hasAuthority) throw UrlParseException("cannot resolve against opaque URL")
    if (input.startsWith("//")) {
        return parseAuthorityUrl(base.scheme, input.substring(2))
    }
    if (input.startsWith("/")) {
        val (path, query, fragment) = splitPathQueryFragment(input)
        return base.copyState(path = normalizePath(path), query = query, fragment = fragment)
    }
    if (input.startsWith("?")) {
        val (_, query, fragment) = splitPathQueryFragment(input)
        return base.copyState(query = query, fragment = fragment)
    }
    if (input.startsWith("#")) {
        return base.copyState(fragment = input.substring(1).ifEmpty { null })
    }
    val (relPath, query, fragment) = splitPathQueryFragment(input)
    val directory = base.path.substringBeforeLast('/', "")
    val combined = if (directory.isEmpty()) "/$relPath" else "$directory/$relPath"
    return base.copyState(path = normalizePath(combined), query = query, fragment = fragment)
}

private fun UrlState.copyState(
    path: String = this.path,
    query: String? = this.query,
    fragment: String? = this.fragment,
): UrlState = UrlState(
    scheme = scheme,
    username = username,
    password = password,
    host = host,
    port = port,
    path = path,
    query = query,
    fragment = fragment,
    hasAuthority = hasAuthority,
)

private fun splitAuthorityTail(raw: String): Pair<String, String> {
    val index = raw.indexOfFirst { it == '/' || it == '?' || it == '#' }
    return if (index < 0) raw to "" else raw.substring(0, index) to raw.substring(index)
}

private fun parseAuthority(raw: String): Authority {
    var rest = raw
    var username = ""
    var password = ""
    val at = rest.lastIndexOf('@')
    if (at >= 0) {
        val userInfo = rest.substring(0, at)
        rest = rest.substring(at + 1)
        val colon = userInfo.indexOf(':')
        username = percentDecode(if (colon < 0) userInfo else userInfo.substring(0, colon))
        password = if (colon < 0) "" else percentDecode(userInfo.substring(colon + 1))
    }
    var host: String
    var port: Int? = null
    if (rest.startsWith("[")) {
        val close = rest.indexOf(']')
        if (close < 0) throw UrlParseException("invalid IPv6 host")
        host = rest.substring(0, close + 1).lowercase()
        val suffix = rest.substring(close + 1)
        if (suffix.startsWith(":")) {
            port = parsePort(suffix.substring(1), "")
        } else if (suffix.isNotEmpty()) {
            throw UrlParseException("invalid host")
        }
    } else {
        val colon = rest.lastIndexOf(':')
        if (colon >= 0 && rest.substring(colon + 1).all { it.isDigit() }) {
            host = rest.substring(0, colon).lowercase()
            port = parsePort(rest.substring(colon + 1), "")
        } else {
            host = rest.lowercase()
        }
    }
    return Authority(username, password, host, port)
}

private fun parsePort(raw: String, scheme: String): Int? {
    if (raw.isEmpty()) return null
    if (!raw.all { it.isDigit() }) throw UrlParseException("invalid port")
    val port = raw.toIntOrNull() ?: throw UrlParseException("invalid port")
    if (port !in 0..65535) throw UrlParseException("invalid port")
    if (scheme.isNotEmpty() && port == defaultPort(scheme)) return null
    return port
}

private fun defaultPort(scheme: String): Int? = when (scheme) {
    "http", "ws" -> 80
    "https", "wss" -> 443
    "ftp" -> 21
    else -> null
}

private fun splitPathQueryFragment(raw: String): Triple<String, String?, String?> {
    val hashIndex = raw.indexOf('#')
    val beforeHash = if (hashIndex < 0) raw else raw.substring(0, hashIndex)
    val fragment = if (hashIndex < 0) null else raw.substring(hashIndex + 1).ifEmpty { null }
    val queryIndex = beforeHash.indexOf('?')
    val path = if (queryIndex < 0) beforeHash else beforeHash.substring(0, queryIndex)
    val query = if (queryIndex < 0) null else beforeHash.substring(queryIndex + 1).ifEmpty { null }
    return Triple(path, query, fragment)
}

private fun normalizePath(path: String): String {
    if (path.isEmpty()) return "/"
    val absolute = path.startsWith("/")
    val output = ArrayList<String>()
    for (segment in path.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (output.isNotEmpty()) output.removeAt(output.lastIndex)
            else -> output.add(segment)
        }
    }
    val result = buildString {
        if (absolute) append('/')
        append(output.joinToString("/"))
    }
    return when {
        result.isEmpty() -> "/"
        path.endsWith("/") && !result.endsWith("/") -> "$result/"
        else -> result
    }
}

private fun parseQuery(query: String): List<Pair<String, String>> {
    if (query.isEmpty()) return emptyList()
    val result = ArrayList<Pair<String, String>>()
    for (part in query.split('&')) {
        if (part.isEmpty()) continue
        val equals = part.indexOf('=')
        val name = if (equals < 0) part else part.substring(0, equals)
        val value = if (equals < 0) "" else part.substring(equals + 1)
        result.add(decodeFormComponent(name) to decodeFormComponent(value))
    }
    return result
}

private fun serializeQuery(entries: List<Pair<String, String>>): String =
    entries.joinToString("&") { (name, value) ->
        "${encodeFormComponent(name)}=${encodeFormComponent(value)}"
    }

private fun encodeFormComponent(value: String): String {
    val bytes = encodeUtf8Bytes(value)
    val builder = StringBuilder(bytes.size)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        when {
            code == 0x20 -> builder.append('+')
            code in 'a'.code..'z'.code ||
                code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code ||
                code == '*'.code || code == '-'.code || code == '.'.code || code == '_'.code ->
                builder.append(code.toChar())

            else -> {
                builder.append('%')
                builder.append(HEX[(code shr 4) and 0x0F])
                builder.append(HEX[code and 0x0F])
            }
        }
    }
    return builder.toString()
}

private fun decodeFormComponent(value: String): String {
    if (value.isEmpty()) return ""
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '+') {
            bytes.add(' '.code.toByte())
            index++
        } else if (character == '%' && index + 2 < value.length) {
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
            } else {
                bytes.add(character.code.toByte())
                index++
            }
        } else {
            val encoded = encodeUtf8Bytes(character.toString())
            for (byte in encoded) bytes.add(byte)
            index++
        }
    }
    return bytes.toByteArray().decodeToString()
}

private fun percentEncode(value: String): String = encodeFormComponent(value).replace("+", "%20")

/** userinfo/path 的百分号解码：不把 `+` 当空格。 */
private fun percentDecode(value: String): String {
    if (value.indexOf('%') < 0) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length) {
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        val encoded = encodeUtf8Bytes(character.toString())
        for (byte in encoded) bytes.add(byte)
        index++
    }
    return bytes.toByteArray().decodeToString()
}

private fun hexValue(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> -1
}

private const val HEX = "0123456789ABCDEF"
