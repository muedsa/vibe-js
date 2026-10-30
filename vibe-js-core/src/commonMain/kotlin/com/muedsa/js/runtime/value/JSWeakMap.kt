package com.muedsa.js.runtime.value

/** WeakMap：复用 Map 存储，但不支持迭代与 size。 */
class JSWeakMap(prototype: JSObject?) : JSMap(prototype)
