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

    @Test fun `parses pulls files json with status and patch`() {
        val json = """
            [
              {"filename":"src/A.kt","status":"modified","additions":10,"deletions":2,"changes":12,
               "patch":"@@ -1,3 +1,5 @@\n line"},
              {"filename":"src/B.kt","previous_filename":"src/Old.kt","status":"renamed",
               "additions":0,"deletions":0,"changes":0},
              {"filename":"img/logo.png","status":"added","additions":0,"deletions":0,"changes":0}
            ]
        """.trimIndent()
        val files = GitHub.parseFiles(json)
        assertEquals(3, files.size)
        assertEquals("src/A.kt", files[0].path)
        assertEquals("modified", files[0].status)
        assertEquals(10, files[0].additions)
        assertEquals("@@ -1,3 +1,5 @@\n line", files[0].patch)
        assertEquals(null, files[0].previousPath)
        assertEquals("renamed", files[1].status)
        assertEquals("src/Old.kt", files[1].previousPath)
        assertEquals(null, files[1].patch)          // no patch field -> null (e.g. pure rename)
        assertEquals(null, files[2].patch)          // binary: no patch
    }

    @Test fun `defaults status when missing and tolerates non-array`() {
        val files = GitHub.parseFiles("""[{"filename":"x.kt"}]""")
        assertEquals(1, files.size)
        assertEquals("modified", files[0].status)
        assertEquals(0, files[0].additions)
        assertTrue(GitHub.parseFiles("").isEmpty())
        assertTrue(GitHub.parseFiles("{}").isEmpty())
        assertTrue(GitHub.parseFiles("not json").isEmpty())
    }

    @Test fun `buildShard carries patch, indexEntry stays light and links the shard`() {
        val pr = GitHub.Pr(220, "ecs to eb", "geeshow", "2022-12-22T13:31:13Z", "06bef06")
        val files = listOf(
            GitHub.PrFile("src/A.kt", "modified", 10, 2, 12, null, "@@ patch @@"),
            GitHub.PrFile("src/B.kt", "added", 5, 0, 5, null, null),
        )
        val shard = GitHub.buildShard(pr, files, "https://github.com/o/r")
        assertEquals(220, shard["number"])
        assertEquals(15, shard["additions"])
        assertEquals(2, shard["changedFiles"])
        assertEquals("https://github.com/o/r/pull/220", shard["url"])
        @Suppress("UNCHECKED_CAST")
        val shardFiles = shard["files"] as List<Map<String, Any?>>
        assertEquals("@@ patch @@", shardFiles[0]["patch"])   // shard keeps the heavy patch

        val entry = GitHub.indexEntry(shard, "myproj.pulls")
        assertEquals(220, entry["number"])
        assertEquals("ecs to eb", entry["title"])
        assertEquals(15, entry["additions"])
        assertEquals("myproj.pulls/220.json", entry["file"])  // lazy-load ref
        assertTrue("files" !in entry.keys)                    // index carries NO patch/files
    }

    @Test fun `readShard round-trips so an already-collected PR can be reused`() {
        val pr = GitHub.Pr(7, "x", "me", "2024-01-01T00:00:00Z", "deadbee")
        val shard = GitHub.buildShard(pr, listOf(GitHub.PrFile("f.kt", "modified", 1, 1, 2, null, "p")), null)
        val tmp = java.io.File.createTempFile("shard", ".json").apply { deleteOnExit() }
        tmp.writeText(JsonOutput.writeValue(shard))
        val back = GitHub.readShard(tmp)!!
        // index entry from the on-disk shard must match the in-memory one (incremental reuse path)
        assertEquals(GitHub.indexEntry(shard, "d"), GitHub.indexEntry(back, "d"))
        assertEquals(null, GitHub.readShard(java.io.File("/no/such/shard.json")))
    }
}
