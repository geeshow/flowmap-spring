package com.flowmap.callgraph

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PsiSourceParser] maps Kotlin AND Java source text to `<fqcn>#<method>` node ids with
 * line ranges, visibility, and endpoint metadata — the join used by PR impact analysis.
 */
class PsiSourceParserTest {

    private val parser = PsiSourceParser()

    @AfterTest fun tearDown() = parser.close()

    @Test fun `java methods get fqcn ids, visibility and endpoints`() {
        val src = """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/users")
            public class UserController {
              @GetMapping("/{id}")
              public String get(long id) { return "u"; }
              private void helper() {}
            }
        """.trimIndent()
        val fns = parser.functions("UserController.java", src).associateBy { it.nodeId }

        val get = fns.getValue("com.x.UserController#get")
        assertEquals("public", get.visibility)
        assertTrue(get.isPublic)
        assertTrue(get.isEndpoint)
        assertEquals("GET", get.httpMethod)
        assertEquals("/users/{id}", get.endpoint)

        val helper = fns.getValue("com.x.UserController#helper")
        assertEquals("private", helper.visibility)
        assertTrue(!helper.isPublic)
        assertTrue(!helper.isEndpoint)
    }

    @Test fun `java method line range covers its body`() {
        val src = """
            package com.x;
            public class A {
              public int one() {
                return 1;
              }
            }
        """.trimIndent()
        val one = parser.functions("A.java", src).single { it.nodeId == "com.x.A#one" }
        // declared on line 3, body closes on line 5 (1-based)
        assertEquals(3, one.startLine)
        assertTrue(one.endLine >= 5)
    }

    @Test fun `kotlin methods still parse with visibility`() {
        val src = """
            package com.x
            class S {
                fun pub() {}
                private fun priv() {}
            }
        """.trimIndent()
        val fns = parser.functions("S.kt", src).associateBy { it.nodeId }
        assertEquals("public", fns.getValue("com.x.S#pub").visibility)
        assertEquals("private", fns.getValue("com.x.S#priv").visibility)
    }
}
