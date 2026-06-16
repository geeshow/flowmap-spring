package com.flowmap.callgraph

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncTest {

    private fun tmp(): File = java.nio.file.Files.createTempDirectory("synctest").toFile()

    /** A FLAT source graph (as the frontend analyzers emit): `<dir>/<name>.json`. */
    private fun graph(dir: File, name: String, layer: String) =
        File(dir, name).writeText("""{"nodes":[{"id":"n","layer":"$layer"}],"edges":[]}""")
    private fun sibling(dir: File, name: String, body: String = "{}") = File(dir, name).writeText(body)

    /** A FOLDER-layout project: `<dir>/projects/<base>/<file>`. */
    private fun pfile(dir: File, base: String, file: String, body: String) =
        File(dir, "projects/$base").also { it.mkdirs() }.let { File(it, file).writeText(body) }
    private fun pgraph(dir: File, base: String, layer: String) =
        pfile(dir, base, "$base.json", """{"nodes":[{"id":"n","layer":"$layer"}],"edges":[]}""")
    private fun pj(dir: File, base: String, file: String) = File(dir, "projects/$base/$file")

    @Test fun `full sync folderizes flat sources and prunes departed projects of both types`() {
        val src = tmp(); val dest = tmp()
        // source (flat): one present backend (+openapi) and one present per-root frontend (+screens)
        graph(src, "svc.json", "CONTROLLER"); sibling(src, "svc.openapi.json", """{"paths":{}}""")
        graph(src, "graph-app.json", "SCREEN"); sibling(src, "graph-app.screens.json", """{"screens":[]}""")
        // dest pre-populated (folder layout) with DEPARTED leftovers: a frontend `graph` whole-repo
        // project (+ .gz companions from the web build's compression), a renamed-away `old-web`
        // frontend, and a departed `gone-svc` backend.
        pgraph(dest, "graph", "SCREEN"); pfile(dest, "graph", "graph.screens.json", "{}"); pfile(dest, "graph", "graph.join.json", "{}")
        pfile(dest, "graph", "graph.json.gz", "x"); pfile(dest, "graph", "graph.screens.json.gz", "x")
        pgraph(dest, "old-web", "SCREEN"); pfile(dest, "old-web", "old-web.screens.json", "{}")
        pgraph(dest, "gone-svc", "CONTROLLER"); pfile(dest, "gone-svc", "gone-svc.openapi.json", "{}")

        Sync.run(listOf(src), dest)

        // present projects landed under projects/<base>/
        assertTrue(pj(dest, "svc", "svc.json").exists())
        assertTrue(pj(dest, "svc", "svc.openapi.json").exists())
        assertTrue(pj(dest, "graph-app", "graph-app.json").exists())
        assertTrue(pj(dest, "graph-app", "graph-app.screens.json").exists())
        // departed projects pruned whole-folder (both types, since both were synced this run)
        assertFalse(File(dest, "projects/graph").exists())
        assertFalse(File(dest, "projects/old-web").exists())
        assertFalse(File(dest, "projects/gone-svc").exists())
    }

    @Test fun `frontend-only sync preserves departed backend (partial-sync safety)`() {
        val src = tmp(); val dest = tmp()
        graph(src, "graph-app.json", "SCREEN")                       // only a frontend graph is synced
        pgraph(dest, "gone-svc", "CONTROLLER"); pfile(dest, "gone-svc", "gone-svc.openapi.json", "{}")
        pgraph(dest, "old-web", "SCREEN"); pfile(dest, "old-web", "old-web.screens.json", "{}")

        Sync.run(listOf(src), dest)

        // backend NOT synced this run -> its departed folder must survive (no cross-type wipe)
        assertTrue(pj(dest, "gone-svc", "gone-svc.json").exists())
        assertTrue(pj(dest, "gone-svc", "gone-svc.openapi.json").exists())
        // departed frontend pruned (frontend type WAS synced)
        assertFalse(File(dest, "projects/old-web").exists())
    }

    @Test fun `still-present project's stale sibling is pruned, graph kept`() {
        val src = tmp(); val dest = tmp()
        graph(src, "svc.json", "CONTROLLER")                          // graph present, but no impact this run
        pgraph(dest, "svc", "CONTROLLER"); pfile(dest, "svc", "svc.impact.json", """{"pulls":[]}""")  // stale sibling

        Sync.run(listOf(src), dest)

        assertTrue(pj(dest, "svc", "svc.json").exists())              // present graph kept
        assertFalse(pj(dest, "svc", "svc.impact.json").exists())     // stale sibling of present graph pruned
    }

    @Test fun `legacy flat artifacts at dest root are cleaned`() {
        val src = tmp(); val dest = tmp()
        graph(src, "svc.json", "CONTROLLER")
        // pre-`projects/` layout leftovers at the dest root
        graph(dest, "svc.json", "CONTROLLER"); sibling(dest, "svc.openapi.json", "{}")

        Sync.run(listOf(src), dest)

        assertFalse(File(dest, "svc.json").exists())                  // legacy flat removed
        assertFalse(File(dest, "svc.openapi.json").exists())
        assertTrue(pj(dest, "svc", "svc.json").exists())              // canonical folder copy present
    }
}
