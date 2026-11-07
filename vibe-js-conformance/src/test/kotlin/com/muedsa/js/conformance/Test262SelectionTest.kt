package com.muedsa.js.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Test262 选择与跳过判定的单元测试。
 *
 * 这些规则决定"哪些用例进入基线、哪些被跳过"，一旦误排会直接掩盖真实缺口，
 * 因此用纯函数测试覆盖，不依赖两万条语料。
 */
class Test262SelectionTest {

    private fun metadata(
        es5Id: String? = null,
        es6Id: String? = null,
        esId: String? = null,
        features: Set<String> = emptySet(),
        flags: Set<String> = emptySet(),
        negativePhase: String? = null,
        includes: List<String> = emptyList(),
    ) = Metadata(
        es5Id = es5Id,
        es6Id = es6Id,
        esId = esId,
        includes = includes,
        flags = flags,
        features = features,
        negativePhase = negativePhase,
        negativeType = null,
    )

    private fun reasonOf(decision: SelectionDecision): String =
        assertIs<SelectionDecision.Excluded>(decision).reason

    @Test
    fun `ES5 子集只选中带 es5id 的用例`() {
        val selected = Test262Selection.decide(
            "test/language/x.js", metadata(es5Id = "15.1"), "es5", includeEsid = false, filter = null,
        )
        assertIs<SelectionDecision.Selected>(selected)

        val excluded = Test262Selection.decide(
            "test/language/x.js", metadata(es6Id = "x"), "es5", includeEsid = false, filter = null,
        )
        assertEquals(Test262Selection.EXCLUDED_MISSING_ES5_ID, reasonOf(excluded))
    }

    @Test
    fun `没有 metadata 的用例在两种子集里都被排除`() {
        assertEquals(
            Test262Selection.EXCLUDED_NO_METADATA,
            reasonOf(Test262Selection.decide("a.js", null, "es5", false, null)),
        )
        assertEquals(
            Test262Selection.EXCLUDED_NO_METADATA,
            reasonOf(Test262Selection.decide("a.js", null, "es2015", false, null)),
        )
    }

    @Test
    fun `ES2015 子集要求全部特性已支持`() {
        val supported = Test262Selection.decide(
            "test/language/x.js",
            metadata(features = setOf("arrow-function", "let")),
            "es2015",
            includeEsid = false,
            filter = null,
        )
        assertIs<SelectionDecision.Selected>(supported)

        val unsupported = Test262Selection.decide(
            "test/language/x.js",
            metadata(features = setOf("arrow-function", "Proxy")),
            "es2015",
            includeEsid = false,
            filter = null,
        )
        assertEquals(Test262Selection.EXCLUDED_UNSUPPORTED_FEATURES, reasonOf(unsupported))
    }

    @Test
    fun `ES2015 子集按 es6id 与 includeEsid 决定无特性用例是否纳入`() {
        assertIs<SelectionDecision.Selected>(
            Test262Selection.decide(
                "test/language/x.js", metadata(es6Id = "6.1"), "es2015", includeEsid = false, filter = null,
            )
        )

        val withoutFlag = Test262Selection.decide(
            "test/language/x.js", metadata(esId = "sec-x"), "es2015", includeEsid = false, filter = null,
        )
        assertEquals(Test262Selection.EXCLUDED_NO_FEATURE_ANNOTATION, reasonOf(withoutFlag))

        val withFlag = Test262Selection.decide(
            "test/language/x.js", metadata(esId = "sec-x"), "es2015", includeEsid = true, filter = null,
        )
        assertIs<SelectionDecision.Selected>(withFlag)
    }

    @Test
    fun `ES2015 子集排除 ES5 用例与显式路径`() {
        assertEquals(
            Test262Selection.EXCLUDED_ES5_CASE,
            reasonOf(
                Test262Selection.decide(
                    "test/language/x.js", metadata(es5Id = "15.1"), "es2015", false, null,
                )
            ),
        )
        assertEquals(
            Test262Selection.EXCLUDED_PATH,
            reasonOf(
                Test262Selection.decide(
                    "test/built-ins/ArrayBuffer/prototype/slice/x.js",
                    metadata(features = setOf("let")),
                    "es2015",
                    false,
                    null,
                )
            ),
        )
    }

    @Test
    fun `filter 不匹配的用例记为筛选排除`() {
        val decision = Test262Selection.decide(
            "test/language/statements/for-of/x.js", metadata(es5Id = "15.1"), "es5", false, filter = "Map",
        )
        assertEquals(Test262Selection.EXCLUDED_FILTER, reasonOf(decision))
    }

    @Test
    fun `带 generator 或 module 标志的用例被跳过`() {
        val reason = Test262Selection.skipReason(
            "var x = 1;", metadata(flags = setOf("generator")), "es5",
        )
        assertEquals("unsupported flag", reason)
    }

    @Test
    fun `未支持特性按特性名跳过`() {
        val reason = Test262Selection.skipReason(
            "var x = 1;", metadata(features = setOf("Intl")), "es2015",
        )
        assertEquals("unsupported feature: Intl", reason)
    }

    @Test
    fun `BigInt 特性已纳入 ES2015 子集`() {
        // L3.3 实现 BigInt 运行时后：特性既不被筛选排除，也不按特性名/文本跳过
        val metadata = metadata(features = setOf("BigInt"))
        assertIs<SelectionDecision.Selected>(
            Test262Selection.decide("test/language/x.js", metadata, "es2015", false, null)
        )
        assertNull(Test262Selection.skipReason("var n = 1n;", metadata, "es2015"))
    }

    @Test
    fun `Reflect 特性用例已纳入 ES2015 子集`() {
        // 核心库提供完整 Reflect：特性既不被筛选排除，也不按特性名跳过
        val metadata = metadata(features = setOf("Reflect", "Reflect.construct"))
        assertIs<SelectionDecision.Selected>(
            Test262Selection.decide("test/language/x.js", metadata, "es2015", false, null)
        )
        assertNull(Test262Selection.skipReason("var x = 1;", metadata, "es2015"))
    }

    @Test
    fun `只使用 evalScript 的用例放行其余宿主钩子跳过`() {
        val allowed = Test262Selection.skipReason(
            "\$262.evalScript('var a = 1;');", metadata(), "es2015",
        )
        assertNull(allowed)

        val skipped = Test262Selection.skipReason(
            "\$262.createRealm();", metadata(), "es2015",
        )
        assertEquals("unsupported harness: \$262", skipped)
    }

    @Test
    fun `文本筛选按子集生效且能识别生成器`() {
        // ES5 子集不做 Generator/Proxy 文本过滤，保持 ES5 口径
        assertNull(
            Test262Selection.skipReason("function* g() {}", metadata(), "es5")
        )
        assertEquals(
            "unsupported Proxy/Generator",
            Test262Selection.skipReason("function* g() {}", metadata(), "es2015"),
        )
        // BigInt 已支持：字面量不再触发文本排除
        assertNull(
            Test262Selection.skipReason("var n = 1n;", metadata(), "es2015")
        )
        // 方法形式的生成器也要识别
        assertEquals(
            "unsupported Proxy/Generator",
            Test262Selection.skipReason("var o = { *gen() {} };", metadata(), "es2015"),
        )
    }

    @Test
    fun `负数用例的阶段不在支持范围内时跳过`() {
        val reason = Test262Selection.skipReason(
            "var x = 1;", metadata(negativePhase = "early"), "es5",
        )
        assertEquals("unsupported negative phase: early", reason)
    }

    @Test
    fun `async 负数用例跳过`() {
        val reason = Test262Selection.skipReason(
            "var x = 1;", metadata(flags = setOf("async"), negativePhase = "runtime"), "es5",
        )
        assertEquals("unsupported async negative", reason)
    }

    @Test
    fun `文本匹配跳过会标记 textMatchOnly，元数据排除不标记`() {
        // 源码文本命中：features 没有标注，只能靠文本判断，标记为待复核
        val textMatched = Test262Selection.skipDecision("function* g() {}", metadata(), "es2015")
        assertEquals("unsupported Proxy/Generator", textMatched?.reason)
        assertEquals(true, textMatched?.textMatchOnly)

        // features 明确标注：属于元数据排除，不需要文本复核
        val featureMatched = Test262Selection.skipDecision(
            "", metadata(features = setOf("Proxy")), "es2015",
        )
        assertEquals("unsupported feature: Proxy", featureMatched?.reason)
        assertEquals(false, featureMatched?.textMatchOnly)

        // flag 排除同样不需要文本复核
        val flagMatched = Test262Selection.skipDecision(
            "", metadata(flags = setOf("module")), "es2015",
        )
        assertEquals("unsupported flag", flagMatched?.reason)
        assertEquals(false, flagMatched?.textMatchOnly)

        assertNull(Test262Selection.skipDecision("var x = 1;", metadata(), "es2015"))
    }
}
