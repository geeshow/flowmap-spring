package com.flowmap.callgraph

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTest {

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("synctest").toFile()
    private fun graph(dir: File, name: String, layer: String) =
        File(dir, name).writeText("""{"nodes":[{"id":"n","layer":"$layer"}],"edges":[]}""")
    private fun sibling(dir: File, name: String, body: String = "{}") = File(dir, name).writeText(body)

    @Test fun `full sync prunes departed projects of both types, keeps present`() {
        val src = tmp(); val dest = tmp()
        // source: one present backend (+openapi) and one present per-root frontend (+screens)
        graph(src, "svc.json", "CONTROLLER"); sibling(src, "svc.openapi.json", """{"paths":{}}""")
        graph(src, "graph-app.json", "SCREEN"); sibling(src, "graph-app.screens.json", """{"screens":[]}""")
        // dest pre-populated with DEPARTED frontend leftovers: whole-repo `graph.*` and an
        // old-naming `old-web.*` (renamed away). Also a departed backend `gone-svc.*`.
        // Each `graph.*` also has a `.gz` companion (web build's compression) to prune.
        graph(dest, "graph.json", "SCREEN"); sibling(dest, "graph.screens.json", """{"screens":[]}"""); sibling(dest, "graph.join.json")
        sibling(dest, "graph.json.gz", "x"); sibling(dest, "graph.screens.json.gz", "x"); sibling(dest, "graph.join.json.gz", "x")
        graph(dest, "old-web.json", "SCREEN"); sibling(dest, "old-web.screens.json", """{"screens":[]}""")
        graph(dest, "gone-svc.json", "CONTROLLER"); sibling(dest, "gone-svc.openapi.json", """{"paths":{}}""")

        Sync.run(listOf(src), dest)

        // present projects kept
        assertTrue(File(dest, "svc.json").exists())
        assertTrue(File(dest, "svc.openapi.json").exists())
        assertTrue(File(dest, "graph-app.json").exists())
        assertTrue(File(dest, "graph-app.screens.json").exists())
        // departed frontend (whole-repo + renamed) pruned with all siblings + gz companions
        assertFalse(File(dest, "graph.json").exists())
        assertFalse(File(dest, "graph.screens.json").exists())
        assertFalse(File(dest, "graph.join.json").exists())
        assertFalse(File(dest, "graph.json.gz").exists())
        assertFalse(File(dest, "graph.screens.json.gz").exists())
        assertFalse(File(dest, "graph.join.json.gz").exists())
        assertFalse(File(dest, "old-web.json").exists())
        assertFalse(File(dest, "old-web.screens.json").exists())
        // departed backend pruned too (backend WAS synced this run)
        assertFalse(File(dest, "gone-svc.json").exists())
        assertFalse(File(dest, "gone-svc.openapi.json").exists())
    }

    @Test fun `frontend-only sync preserves departed backend (partial-sync safety)`() {
        val src = tmp(); val dest = tmp()
        graph(src, "graph-app.json", "SCREEN")                       // only a frontend graph is synced
        graph(dest, "gone-svc.json", "CONTROLLER"); sibling(dest, "gone-svc.openapi.json", """{"paths":{}}""")
        graph(dest, "old-web.json", "SCREEN"); sibling(dest, "old-web.screens.json", """{"screens":[]}""")

        Sync.run(listOf(src), dest)

        // backend NOT synced this run -> its departed graph must survive (no cross-type wipe)
        assertTrue(File(dest, "gone-svc.json").exists())
        assertTrue(File(dest, "gone-svc.openapi.json").exists())
        // departed frontend pruned (frontend type WAS synced)
        assertFalse(File(dest, "old-web.json").exists())
        assertFalse(File(dest, "old-web.screens.json").exists())
    }

    @Test fun `still-present project's stale sibling is pruned, graph kept`() {
        val src = tmp(); val dest = tmp()
        graph(src, "svc.json", "CONTROLLER")                          // graph present, but no impact this run
        graph(dest, "svc.json", "CONTROLLER"); sibling(dest, "svc.impact.json", """{"pulls":[]}""")  // stale sibling

        Sync.run(listOf(src), dest)

        assertTrue(File(dest, "svc.json").exists())                   // present graph kept
        assertFalse(File(dest, "svc.impact.json").exists())          // stale sibling of present graph pruned
    }
}
