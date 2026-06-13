package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubTest {

    @Test fun `parses gh pr list json`() {
        val json = """
            [
              {"number":220,"title":"ecs to eb","author":{"login":"geeshow","name":"K P"},
               "mergedAt":"2022-12-22T13:31:13Z","mergeCommit":{"oid":"06bef06"}},
              {"number":216,"title":"Master","author":{"login":"someone"},
               "mergedAt":"2022-12-04T12:47:01Z","mergeCommit":{"oid":"c62e864"}}
            ]
        """.trimIndent()
        val prs = GitHub.parse(json)
        assertEquals(2, prs.size)
        assertEquals(220, prs[0].number)
        assertEquals("ecs to eb", prs[0].title)
        assertEquals("geeshow", prs[0].author)
        assertEquals("06bef06", prs[0].mergeCommit)
        assertEquals("2022-12-22T13:31:13Z", prs[0].mergedAt)
    }

    @Test fun `tolerates missing optional fields`() {
        val prs = GitHub.parse("""[{"number":5,"title":"x"}]""")
        assertEquals(1, prs.size)
        assertEquals(5, prs[0].number)
        assertEquals(null, prs[0].author)
        assertEquals(null, prs[0].mergeCommit)
    }

    @Test fun `bad or empty input yields empty list`() {
        assertTrue(GitHub.parse("").isEmpty())
        assertTrue(GitHub.parse("not json").isEmpty())
        assertTrue(GitHub.parse("{}").isEmpty())   // object, not array
        assertTrue(GitHub.parse("[]").isEmpty())
    }
}
