package com.muedsa.js.runtime.value

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.runtime.Environment

class JSFunction(
    val name: String,
    val params: List<String>,
    val body: BlockStatement,
    val closure: Environment,
) : JSObject(
    prototype = NativeFunctionPrototype,
) {
    override fun toPrimitiveBoolean() = true
    override fun toPrimitiveNumber() = Double.NaN
    override fun toPrimitiveString() =
        "function ${name}(${params.joinToString(", ")}) { ${if (body.statements.isEmpty()) "" else "..."} }"
}
