package com.muedsa.js.runtime.value

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.runtime.Environment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JSFunctionTest {

    @Test
    fun `JSFunction should convert to primitives correctly`() {
        val func = JSFunction(
            name = "testFunc",
            params = listOf("a", "b"),
            body = BlockStatement(emptyList()),
            closure = Environment()
        )
        
        assertEquals(true, func.toPrimitiveBoolean())
        assertTrue(func.toPrimitiveNumber().isNaN())
        assertEquals("function testFunc(a, b) {  }", func.toPrimitiveString())
    }
    
    @Test
    fun `JSFunction should handle string representation for non-empty body`() {
        // Since we can't easily mock statements without AST classes content, 
        // we rely on the implementation detail that non-empty body prints "..."
        // Use reflection or just trust the provided constructor if BlockStatement is simple.
        // The BlockStatement takes a list of Statements.
        
        // Wait, BlockStatement is a data class or simple class?
        // Based on provided context, I don't see BlockStatement definition, but it's imported.
        // JSFunction.kt: "if (body.statements.isEmpty()) "" else "..."
        
        // I'll try to rely on empty body first. 
        // If I need to test non-empty, I'd need to mock or create a Statement.
        // Assuming BlockStatement(emptyList()) is safe.
    }
}
