package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSValue

/**
 * 用于表示函数返回值的内部异常
 */
class ReturnValue(val value: JSValue) : Throwable()
