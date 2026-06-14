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
    // Pre-manifest single-graph files. NOT `graph.json`: a frontend run with
    // NAME=graph legitimately produces `graph.json` + `graph.screens.json`, so the
    // stale-sibling prune (below) handles graph.* by source presence instead.
    private val LEGACY = listOf("graph.json.bak", "openapi.json", "impact.json")

    data class Result(val copied: Int, val projects: Int)

    /** True for per-project artifacts that should land in the web data dir. */
    private fun isArtifact(f: File): Boolean =
        f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json"

    /** Project base name of an artifact (strips the graph/sibling suffix). */
    private fun baseOf(name: String): String = name
        .removeSuffix(".openapi.json").removeSuffix(".impact.json")
        .removeSuffix(".join.json").removeSuffix(".screens.json").removeSuffix(".json")

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
        // Clean up BEFORE building the manifest (a disk scan), so stale files aren't
        // mis-catalogued or served:
        //  1) legacy bare single-graph files the manifest replaces, and
        //  2) stale siblings of a SYNCED project — e.g. `<p>.impact.json` left over
        //     when `<p>` no longer has impact this run. Scoped to projects whose graph
        //     IS in a source, so frontend artifacts survive a backend-only sync.
        LEGACY.forEach { val lf = File(dest, it); if (lf.delete()) System.err.println("    ~ removed legacy $it") }
        val sourceNames = sources.filter { it.isDirectory }
            .flatMap { it.listFiles { f -> isArtifact(f) }?.toList() ?: emptyList() }
            .map { it.name }.toSet()
        val syncedGraphBases = sourceNames.filter { it == "${baseOf(it)}.json" }.map { baseOf(it) }.toSet()
        dest.listFiles { f -> isArtifact(f) }?.forEach { f ->
            if (f.name in sourceNames) return@forEach          // freshly synced — keep
            if (baseOf(f.name) in syncedGraphBases && f.delete()) System.err.println("    ~ pruned stale ${f.name}")
        }
        val projects = Manifest.write(dest, "manifest.json")
        System.err.println("    + manifest.json ($projects projects)")
        return Result(copied, projects)
    }
}
