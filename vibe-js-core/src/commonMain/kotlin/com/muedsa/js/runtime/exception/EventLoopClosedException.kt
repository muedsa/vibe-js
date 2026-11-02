package com.muedsa.js.runtime.exception

import kotlinx.coroutines.CancellationException

/** 事件循环关闭属于宿主生命周期控制，不能转换为 JS catch 或 Promise 拒绝。 */
class EventLoopClosedException : CancellationException("Event loop is closed")
