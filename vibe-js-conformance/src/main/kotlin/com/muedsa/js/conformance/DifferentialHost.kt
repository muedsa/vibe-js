package com.muedsa.js.conformance

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinModule
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSUndefined

/**
 * 差分运行器专用的 detach 宿主钩子。
 *
 * ECMAScript 没有向 JS 暴露 detach 能力，Test262 通过宿主注入的
 * `$262.detachArrayBuffer` 观察分离语义；差分运行器沿用同一思路，
 * 向两侧注入 `__detach(buffer)`：
 * - Vibe 侧直接调用 [JSArrayBuffer.detach]；
 * - Node 侧由运行器生成的脚本用 `structuredClone(buffer, { transfer: [buffer] })`
 *   实现（transfer 会分离原缓冲区）。
 *
 * 该钩子只安装在差分运行器的引擎中，不进入核心内建。
 */
internal object DifferentialDetachModule : BuiltinModule {

    override fun install(registry: BuiltinRegistry) {
        val detach = BuiltinHelpers.method(registry.intrinsics, "__detach", 1) { interpreter, _, args ->
            val buffer = args.getOrNull(0) as? JSArrayBuffer
                ?: interpreter.typeError("__detach requires an ArrayBuffer argument")
            buffer.detach()
            JSUndefined
        }
        registry.defineGlobal("__detach", detach)
    }
}
