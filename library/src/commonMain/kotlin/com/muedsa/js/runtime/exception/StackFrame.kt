package com.muedsa.js.runtime.exception

data class StackFrame(
    val functionName: String,
    val line: Int = 0,
    val column: Int = 0,
) {
    override fun toString(): String {
        return if (line > 0 && column > 0) {
            "    at $functionName [$line: $column]"
        } else {
            "    at $functionName"
        }
    }
}