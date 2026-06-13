package com.flowmap.callgraph

import java.io.File

/**
 * Port of `flowmap/scripts/sync-data.sh` — assembles the web app's data directory
 * from one or more analyzer output dirs:
 *   1. copy every per-project artifact (`<p>.json` / `.openapi.json` / `.impact.json`
 *      / `.join.json` / `.screens.json`) from each source into [dest], EXCLUDING the
 *      `_*` aggregates (`_combined.json`, `_openapi.json`, `_manifest.json`) and the
 *      app manifest itself;
 *   2. (re)build the app-facing `manifest.json` by scanning [dest] — [Manifest]
 *      detects backend vs frontend by node layers and links the siblings that
 *      actually exist on disk, so a merged dir is catalogued correctly without
 *      merging the per-analyzer `_manifest.json` files by hand;
 *   3. delete the legacy single-graph files the manifest replaces.
 *
 * The shell merged two analyzers' `_manifest.json`; scanning [dest] after the copy
 * is equivalent and self-consistent (no orphan entries possible — every listed
 * project has a graph on disk).
 */
object Sync {
    private val LEGACY = listOf("graph.json", "graph.json.bak", "openapi.json", "impact.json")

    data class Result(val copied: Int, val projects: Int)

    /** True for per-project artifacts that should land in the web data dir. */
    private fun isArtifact(f: File): Boolean =
        f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json"

    fun run(sources: List<File>, dest: File): Result {
        dest.mkdirs()
        val destC = dest.canonicalFile
        var copied = 0
        for (src in sources) {
            if (!src.isDirectory) { System.err.println("  sync: source dir missing: ${src.path}"); continue }
            if (src.canonicalFile == destC) { System.err.println("  sync: ${src.path} is the dest (already in place)"); continue }
            val files = src.listFiles { f -> isArtifact(f) }?.toList() ?: emptyList()
            for (f in files) {
                f.copyTo(File(dest, f.name), overwrite = true); copied++
                System.err.println("    + ${f.name}")
            }
            System.err.println("  sync: ${src.path} -> ${files.size} files")
        }
        // Drop legacy single-graph files BEFORE building the manifest — the manifest
        // is a disk scan, so `graph.json`/`openapi.json` left in place would be
        // mis-catalogued as projects named "graph"/"openapi".
        LEGACY.forEach { val lf = File(dest, it); if (lf.delete()) System.err.println("    ~ removed legacy $it") }
        val projects = Manifest.write(dest, "manifest.json")
        System.err.println("    + manifest.json ($projects projects)")
        return Result(copied, projects)
    }
}
