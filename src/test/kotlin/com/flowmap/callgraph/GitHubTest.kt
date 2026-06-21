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

    @Test fun `derives PR status from state, draft and mergedAt`() {
        assertEquals("merged", GitHub.prStatus("merged", false, null))
        assertEquals("merged", GitHub.prStatus("open", false, "2024-01-01T00:00:00Z"))   // mergedAt wins
        assertEquals("draft", GitHub.prStatus("open", true, null))
        assertEquals("open", GitHub.prStatus("open", false, null))
        assertEquals("closed", GitHub.prStatus("closed", false, null))
        // parse(): mergedAt 있으면 merged, state/isDraft 없으면 merged 폴백
        assertEquals("merged", GitHub.parse("""[{"number":1,"title":"a","mergedAt":"2024-01-01T00:00:00Z"}]""")[0].status)
        assertEquals("open", GitHub.parse("""[{"number":2,"title":"b","state":"OPEN"}]""")[0].status)
    }

    @Test fun `parseOpen maps headOid, status, updatedAt for open and draft PRs`() {
        val json = """
            [
              {"number":42,"title":"wip","author":{"login":"alice"},"headRefOid":"abc123",
               "createdAt":"2026-06-01T00:00:00Z","updatedAt":"2026-06-10T09:00:00Z","isDraft":false},
              {"number":7,"title":"draft","author":{"login":"bob"},"headRefOid":"def456",
               "createdAt":"2026-05-01T00:00:00Z","updatedAt":"2026-05-02T00:00:00Z","isDraft":true}
            ]
        """.trimIndent()
        val prs = GitHub.parseOpen(json)
        assertEquals(2, prs.size)
        assertEquals("open", prs[0].status)
        assertEquals("abc123", prs[0].headOid)
        assertEquals("abc123", prs[0].analyzedCommit)   // no mergeCommit → head is analyzed revision
        assertEquals(null, prs[0].mergedAt)
        assertEquals("2026-06-10T09:00:00Z", prs[0].updatedAt)
        assertTrue(prs[0].isOpen)
        assertEquals("draft", prs[1].status)
        assertTrue(prs[1].isOpen)
    }

    @Test fun `parseOpen falls back to createdAt and tolerates garbage`() {
        val one = GitHub.parseOpen("""[{"number":1,"title":"t","headRefOid":"h","createdAt":"2026-01-01T00:00:00Z"}]""")
        assertEquals("2026-01-01T00:00:00Z", one[0].updatedAt)
        assertEquals("open", one[0].status)   // isDraft absent → open
        assertTrue(GitHub.parseOpen("not json").isEmpty())
        assertTrue(GitHub.parseOpen("[]").isEmpty())
    }

    @Test fun `merged Pr defaults are unchanged and not open`() {
        val merged = GitHub.Pr(5, "fix", "carol", "2026-02-02T00:00:00Z", "mergeSha")
        assertEquals("merged", merged.status)
        assertEquals("mergeSha", merged.analyzedCommit)
        assertEquals(null, merged.headOid)
        assertTrue(!merged.isOpen)
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

    @Test fun `parseGitLog recovers PRs from merge + squash markers, skips non-PR commits`() {
        val FS = "\u001f"; val RS = "\u001e"
        val log = listOf(
            "sha4${FS}2026-06-14T09:00:00Z${FS}Kyutae Park${FS}Merge pull request #4 from geeshow/feat-log${FS}feat(refresh): log PR analysis step\n\nbody detail",
            "sha42${FS}2026-06-10T00:00:00Z${FS}me${FS}feat(x): do a thing (#42)$FS",
            "shaX${FS}2026-06-09T00:00:00Z${FS}me${FS}chore: direct push, no PR$FS",
        ).joinToString("$RS\n") + RS
        val prs = GitHub.parseGitLog(log, 50)
        assertEquals(2, prs.size)                                    // the non-PR commit is dropped
        assertEquals(4, prs[0].number)
        assertEquals("feat(refresh): log PR analysis step", prs[0].title)   // merge: title from body
        assertEquals("sha4", prs[0].mergeCommit)
        assertEquals("Kyutae Park", prs[0].author)
        assertEquals(42, prs[1].number)
        assertEquals("feat(x): do a thing", prs[1].title)            // squash: subject minus (#N)
        assertEquals("sha42", prs[1].mergeCommit)
        assertEquals(1, GitHub.parseGitLog(log, 1).size)             // limit respected
    }

    @Test fun `parseShow yields per-file status, patch and counts`() {
        val diff = listOf(
            "diff --git a/src/A.kt b/src/A.kt", "index 111..222 100644", "--- a/src/A.kt", "+++ b/src/A.kt",
            "@@ -1,3 +1,4 @@", " ctx", "-old", "+new1", "+new2",
            "diff --git a/new.txt b/new.txt", "new file mode 100644", "--- /dev/null", "+++ b/new.txt",
            "@@ -0,0 +1,2 @@", "+a", "+b",
            "diff --git a/old/x.kt b/new/x.kt", "similarity index 95%", "rename from old/x.kt", "rename to new/x.kt",
            "diff --git a/gone.txt b/gone.txt", "deleted file mode 100644", "--- a/gone.txt", "+++ /dev/null",
            "@@ -1,1 +0,0 @@", "-bye",
            "diff --git a/img.png b/img.png", "index 000..555 100644", "Binary files a/img.png and b/img.png differ",
        ).joinToString("\n")
        val byPath = GitHub.parseShow(diff).associateBy { it.path }
        assertEquals(5, byPath.size)
        byPath["src/A.kt"]!!.let { assertEquals("modified", it.status); assertEquals(2, it.additions); assertEquals(1, it.deletions); assertEquals(3, it.changes); assertTrue(it.patch!!.startsWith("@@ -1,3 +1,4 @@")); assertEquals(null, it.previousPath) }
        byPath["new.txt"]!!.let { assertEquals("added", it.status); assertEquals(2, it.additions); assertEquals(0, it.deletions) }
        byPath["new/x.kt"]!!.let { assertEquals("renamed", it.status); assertEquals("old/x.kt", it.previousPath); assertEquals(null, it.patch) }
        byPath["gone.txt"]!!.let { assertEquals("removed", it.status); assertEquals(0, it.additions); assertEquals(1, it.deletions) }
        byPath["img.png"]!!.let { assertEquals("modified", it.status); assertEquals(null, it.patch); assertEquals(0, it.additions) }   // binary: no patch
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
