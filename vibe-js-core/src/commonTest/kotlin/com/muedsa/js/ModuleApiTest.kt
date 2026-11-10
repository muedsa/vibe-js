package com.muedsa.js

import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.EmptyModuleProvider
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.InMemoryModuleProvider
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 模块公共 API 与 Provider 契约；此时还没有链接/求值。 */
@OptIn(ExperimentalModuleApi::class)
class ModuleApiTest {

    @Test
    fun `InMemoryModuleProvider 按精确 id 返回源码且未知 id 返回 null`() = runTest {
        val provider = InMemoryModuleProvider(
            mapOf("./main.mjs" to "export const value = 1;"),
        )
        val source = provider.load(ModuleRequest("./main.mjs"))
        assertEquals("./main.mjs", source?.id)
        assertEquals("./main.mjs", source?.sourceName)
        assertEquals("export const value = 1;", source?.source)
        assertNull(source?.url)

        assertNull(provider.load(ModuleRequest("../main.mjs")))
        // 内存 Provider 只看精确 specifier；referrer 由宿主/规范化逻辑自行使用。
        assertEquals(
            "./main.mjs",
            provider.load(ModuleRequest("./main.mjs", referrer = "./other.mjs"))?.id,
        )
    }

    @Test
    fun `EmptyModuleProvider 始终返回 null`() = runTest {
        assertNull(EmptyModuleProvider.load(ModuleRequest("./missing.mjs")))
    }

    @Test
    fun `ModuleSource 与 ModuleRequest 的默认字段`() {
        val source = ModuleSource(id = "mod-1", source = "1;")
        assertEquals("mod-1", source.sourceName)
        assertNull(source.url)

        val request = ModuleRequest("./dep.mjs")
        assertNull(request.referrer)
    }

    @Test
    fun `ModuleLinkException 保留错误分类与定位字段`() {
        val error = ModuleLinkException(
            kind = ModuleLinkErrorKind.MISSING_EXPORT,
            specifier = "./dep.mjs",
            referrer = "./main.mjs",
            moduleId = "dep",
            exportName = "missing",
            message = "missing export",
        )
        assertEquals(ModuleLinkErrorKind.MISSING_EXPORT, error.kind)
        assertEquals("./dep.mjs", error.specifier)
        assertEquals("./main.mjs", error.referrer)
        assertEquals("dep", error.moduleId)
        assertEquals("missing", error.exportName)
        assertEquals("missing export", error.message)
    }

    @Test
    fun `模块图预算默认值与非法值校验`() {
        assertEquals(10_000, EngineLimits().maxModules)
        assertEquals(10_000_000, EngineLimits().maxTotalModuleSourceLength)

        assertFailsWith<IllegalArgumentException> { EngineLimits(maxModules = 0) }
        assertFailsWith<IllegalArgumentException> { EngineLimits(maxModules = -1) }
        assertFailsWith<IllegalArgumentException> {
            EngineLimits(maxTotalModuleSourceLength = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EngineLimits(maxTotalModuleSourceLength = -1)
        }
    }
}
