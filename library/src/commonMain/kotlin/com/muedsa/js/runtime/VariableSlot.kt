package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSValue

data class VariableSlot(
    var value: JSValue,
    val kind: VariableKind,
)
