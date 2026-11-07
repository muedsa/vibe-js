package com.muedsa.js

import com.muedsa.js.runtime.Float32Math
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4：Kotlin/JS 目标 Float32 / Math.fround 精度回归。
 *
 * Kotlin/JS 的 `Double.toFloat()` 是恒等转换，不会执行 binary32 舍入，因此
 * `Math.fround` 曾在 JS 目标上退化为恒等。这里同时锁定统一的位级实现
 * [Float32Math]，以及 `Math.fround`、Float32Array、DataView 三条 JS 可见路径，
 * 保证 JVM / JS / Wasm / Native 行为一致。
 */
class Float32PrecisionTest {

    @Test
    fun `Float32Math 位模式转换覆盖典型值 次正规数 溢出与特殊值`() {
        // 典型值：0.1、1.337、2.9 的 binary32 位模式（与 V8 的 Math.fround 一致）
        assertEquals(0x3DCCCCCD, Float32Math.toBits(0.1))
        assertEquals(0x3FAB22D1, Float32Math.toBits(1.337))
        assertEquals(0x4039999A, Float32Math.toBits(2.9))

        // roundTiesToEven：1 + 2^-24 恰好在 1 与 1 + 2^-23 之间，取偶数尾数 1.0；
        // 1 + 3*2^-24 在 1 + 2^-23 与 1 + 2^-22 之间，取偶数尾数 1 + 2^-22
        assertEquals(0x3F800000, Float32Math.toBits(1.0 + 1.0 / 16777216.0))
        assertEquals(0x3F800002, Float32Math.toBits(1.0 + 3.0 / 16777216.0))

        // 最大有限值保留；2^128 与恰好位于溢出阈值上的数都进位为 Infinity
        assertEquals(0x7F7FFFFF, Float32Math.toBits(3.4028234663852886e38))
        assertEquals(0x7F800000, Float32Math.toBits(3.402823669209385e38))
        assertEquals(0x7F800000, Float32Math.toBits(3.4028235677973366e38))

        // 次正规数：最小单位 2^-149、一半取偶数归零、1.5/2.5/3.5 个单位的银行家舍入
        assertEquals(0x00000001, Float32Math.toBits(1.401298464324817e-45))
        assertEquals(0x00000001, Float32Math.toBits(1e-45))
        assertEquals(0x00000000, Float32Math.toBits(1e-46))
        assertEquals(0x00000000, Float32Math.toBits(7.006492321624085e-46))
        assertEquals(0x00000002, Float32Math.toBits(2.1019476964872256e-45))
        assertEquals(0x00000002, Float32Math.toBits(3.5032461608120427e-45))
        assertEquals(0x00000004, Float32Math.toBits(4.90454462513686e-45))

        // 特殊值：±0、±Infinity、NaN（NaN 载荷不参与比较，只要求是规范静默 NaN）
        assertEquals(0x00000000, Float32Math.toBits(0.0))
        assertEquals(Int.MIN_VALUE, Float32Math.toBits(-0.0))
        assertEquals(0x7F800000, Float32Math.toBits(Double.POSITIVE_INFINITY))
        assertEquals(0xFF800000.toInt(), Float32Math.toBits(Double.NEGATIVE_INFINITY))
        assertEquals(0x7FC00000, Float32Math.toBits(Double.NaN))
        // NaN 的符号位在 toBits/fromBits 往返中保留，载荷统一为规范静默 NaN
        val negativeNaN = Double.fromBits(Long.MIN_VALUE or 0x7FF8000000000000L)
        assertEquals(0xFFC00000.toInt(), Float32Math.toBits(negativeNaN))
    }

    @Test
    fun `Float32Math 位模式反向扩展精确且可往返`() {
        // binary32 的每个有限值都能被 double 精确表示，反向扩展不做舍入
        assertEquals(0.10000000149011612, Float32Math.fromBits(0x3DCCCCCD))
        assertEquals(3.4028234663852886e38, Float32Math.fromBits(0x7F7FFFFF))
        assertEquals(1.401298464324817e-45, Float32Math.fromBits(0x00000001))
        assertEquals(1.1754943508222875e-38, Float32Math.fromBits(0x00800000))
        assertEquals(Double.POSITIVE_INFINITY, Float32Math.fromBits(0x7F800000))
        assertEquals(Double.NEGATIVE_INFINITY, Float32Math.fromBits(0xFF800000.toInt()))
        assertTrue(Float32Math.fromBits(0x7FC00000).isNaN())

        // NaN 的符号位必须保留，否则同类型 TypedArray 的逐元素拷贝会破坏位模式
        val negativeNaN = Float32Math.fromBits(0xFFC00000.toInt())
        assertTrue(negativeNaN.isNaN())
        assertTrue(negativeNaN.toRawBits() < 0L)
        assertEquals(0xFFC00000.toInt(), Float32Math.toBits(negativeNaN))

        // -0 的符号位必须保留（用 1 / -0 === -Infinity 观察）
        assertEquals(Double.NEGATIVE_INFINITY, 1.0 / Float32Math.fromBits(Int.MIN_VALUE))

        // 所有有限的 binary32 位模式往返后保持不变（不含 NaN）
        val finiteBits = intArrayOf(
            0x00000000, 0x00000001, 0x00000002, 0x007FFFFF,
            0x00800000, 0x3DCCCCCD, 0x3F800000, 0x3FAB22D1,
            0x7F7FFFFF, Int.MIN_VALUE, Int.MIN_VALUE + 1, 0xFF7FFFFF.toInt(),
        )
        for (bits in finiteBits) {
            val message = "位模式 0x${bits.toUInt().toString(16)}"
            assertEquals(bits, Float32Math.toBits(Float32Math.fromBits(bits)), message)
        }
    }

    @Test
    fun `Math fround 按 roundTiesToEven 舍入并处理溢出 次正规与特殊值`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            [
                Math.fround(0.1) === 0.10000000149011612,
                Math.fround(1.337) === 1.3370000123977661,
                Math.fround(2.9) === 2.9000000953674316,
                Math.fround(-0.1) === -0.10000000149011612,
                Math.fround(Math.pow(2, 128)) === Infinity,
                Math.fround(3.4028234663852886e38) === 3.4028234663852886e38,
                Math.fround(3.4028235677973366e38) === Infinity,
                Math.fround(1.401298464324817e-45) === 1.401298464324817e-45,
                Math.fround(1e-45) === 1.401298464324817e-45,
                Math.fround(1e-46) === 0,
                Math.fround(Math.pow(2, -150)) === 0,
                Math.fround(1 + Math.pow(2, -24)) === 1,
                Math.fround(1 + 3 * Math.pow(2, -24)) === 1 + Math.pow(2, -22),
                Math.fround(NaN) !== Math.fround(NaN),
                Math.fround(Infinity) === Infinity,
                Math.fround(-Infinity) === -Infinity,
                1 / Math.fround(-0) === -Infinity
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,true,true",
            result,
        )
    }

    @Test
    fun `Float32Array 写入按 binary32 舍入并保留特殊值`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Float32Array(7);
            array[0] = 0.1;
            array[1] = 2.9;
            array[2] = 1e-45;
            array[3] = 1e-46;
            array[4] = Math.pow(2, 128);
            array[5] = -0;
            array[6] = 0 / 0;
            var view = new DataView(array.buffer);
            var fromIterable = new Float32Array([0.1, 1.337]);
            [
                array[0] === 0.10000000149011612,
                array[1] === 2.9000000953674316,
                view.getUint32(0, true) === 0x3DCCCCCD,
                array[2] === 1.401298464324817e-45,
                array[3] === 0,
                array[4] === Infinity,
                1 / array[5] === -Infinity,
                view.getUint32(20, true) === 0x80000000,
                array[6] !== array[6],
                fromIterable[0] === 0.10000000149011612,
                fromIterable[1] === 1.3370000123977661
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,true,true,true,true,true,true", result)
    }

    @Test
    fun `DataView setFloat32 按 binary32 舍入并写入正确位模式`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(4);
            var view = new DataView(buffer);
            view.setFloat32(0, 0.1, true);
            var little = view.getUint32(0, true);
            var roundTrip = view.getFloat32(0, true);
            view.setFloat32(0, 0.1, false);
            var big = view.getUint32(0, false);
            view.setFloat32(0, Math.pow(2, 128), true);
            var overflow = view.getFloat32(0, true);
            view.setFloat32(0, -0, true);
            var negativeZero = view.getFloat32(0, true);
            view.setFloat32(0, 1e-45, true);
            var subnormal = view.getFloat32(0, true);
            view.setFloat32(0, 0 / 0, true);
            var nan = view.getFloat32(0, true);
            [
                little === 0x3DCCCCCD,
                roundTrip === 0.10000000149011612,
                big === 0x3DCCCCCD,
                overflow === Infinity,
                1 / negativeZero === -Infinity,
                subnormal === 1.401298464324817e-45,
                nan !== nan
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,true,true", result)
    }
}
