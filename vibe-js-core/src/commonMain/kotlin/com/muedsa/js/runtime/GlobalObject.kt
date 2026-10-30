package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSObject

/**
 * 全局对象。
 *
 * 作为全局变量（var/function/隐式全局）的属性容器，
 * 同时也是 globalThis / window / self 指向的对象（别名由宿主模块注册）。
 */
class GlobalObject : JSObject(prototype = null)
