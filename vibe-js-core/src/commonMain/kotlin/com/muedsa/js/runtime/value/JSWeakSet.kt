package com.muedsa.js.runtime.value

/** WeakSet：复用 Set 存储，但不支持迭代与 size。 */
class JSWeakSet(prototype: JSObject?) : JSSet(prototype)
