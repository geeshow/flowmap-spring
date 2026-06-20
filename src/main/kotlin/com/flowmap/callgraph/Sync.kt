package com.flowmap.callgraph

import java.io.File

/**
 * Port of `flowmap/scripts/sync-data.sh` — assembles the web app's data directory
 * from one or more analyzer output dirs:
 *   1. copy every per-project artifact (`<p>.json` / `.openapi.json` / `.impact.json`
 *      / `.pulls.json` + the `<p>.pulls/` lazy-load shard dir / `.join.json` /
 *      `.screens.json`) from each source into [dest], EXCLUDING the `_*` aggregates
 *      (`_combined.json`, `_openapi.json`, `_manifest.json`) and the app manifest itself;
 *   2. (re)build the app-facing `manifest.json` by scanning [dest] — [Manifest]
 *      detects backend vs frontend by node layers and links the siblings that
 *      actually exist on disk, so a merged dir is catalogued correctly without
 *      merging the per-analyzer `_manifest.json` files by hand;
 *   3. delete legacy single-graph files, DEPARTED projects (graph whose base is gone
 *      from every source — removed or renamed, e.g. whole-repo `graph` → per-root
 *      `graph-<root>`; type-scoped so a partial sync can't wipe the other side), and
 *      stale siblings/shard-dirs of still-present projects.
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

    /** Canonical sibling suffix matched by [name] — bare (`openapi.json`, nexcore) OR prefixed (`<base>.openapi.json`), else null. */
    private fun siblingSuffix(name: String): String? =
        SIBLING_SUFFIXES.firstOrNull { name == it || name.endsWith(".$it") }

    /** The pure graph JSON among a project's artifacts (not an openapi/impact/pulls/join/screens sibling). */
    private fun isGraphArtifact(f: File): Boolean = isArtifact(f) && siblingSuffix(f.name) == null

    /** A lazy-loaded per-PR shard directory: `<project>.pulls/` (file diffs) or `<project>.impact/` (change detail). */
    private fun isShardDir(f: File): Boolean = f.isDirectory && (f.name.endsWith(".pulls") || f.name.endsWith(".impact"))

    /** Project base name of a shard dir (strips `.pulls`/`.impact`). */
    private fun shardBaseOf(name: String): String = name.removeSuffix(".pulls").removeSuffix(".impact")

    /**
     * Delete an artifact AND its optional gzip companion (`<f>.gz`, written by the web
     * build's compression step), logging each removal with [why]. Keeping the `.gz`
     * paired with the `.json` is what prevents a pruned project from lingering as a
     * still-served compressed orphan.
     */
    private fun pruneArtifact(f: File, why: String) {
        if (f.delete()) System.err.println("    ~ $why ${f.name}")
        val gz = File(f.path + ".gz")
        if (gz.delete()) System.err.println("    ~ $why ${gz.name}")
    }

    /** Project base name of an artifact (strips the graph/sibling suffix). */
    private fun baseOf(name: String): String = name
        .removeSuffix(".openapi.json").removeSuffix(".impact.json").removeSuffix(".pulls.json")
        .removeSuffix(".gateway.json").removeSuffix(".join.json").removeSuffix(".screens.json").removeSuffix(".json")

    /**
     * A project's artifacts gathered from one source. Each entry pairs the SOURCE file/dir
     * with its CANONICAL dest name (`<base>.<suffix>`), so divergent internal namings
     * (`graph.json`, bare `openapi.json`, `<base>.*`) all converge on `<base>.*` on copy.
     */
    private class ProjectArtifacts(
        val relPath: String,                       // path under dest `projects/`: <ns>/<repo>/<perRoot> (or legacy <name>)
        val base: String,                          // leaf dir name (perRoot) — canonical `<base>.*` rename + shard naming
        val files: List<Pair<File, String>>,       // source file -> canonical dest name
        val shardDirs: List<Pair<File, String>>,   // source shard dir -> canonical dest name
    )

    // Canonical sibling suffixes (used in `<base>.<suffix>` form) the web data dir expects.
    private val SIBLING_SUFFIXES = listOf("openapi.json", "impact.json", "pulls.json", "gateway.json", "join.json", "screens.json")

    /** Source subdir whose children are the nested per-service tree. All three analyzers now
     *  stage under `projects/<ns>/<repo>/<perRoot>/` (was spring=projects, nexcore=service,
     *  react=frontend). Only `projects` is read so stale pre-migration `service`/`frontend`
     *  trees left on disk aren't double-ingested. */
    private val FOLDER_GROUPS = listOf("projects")

    /** Leaf project dirs under [root]: dirs directly holding artifacts (skips `.pulls`/`.impact` shard dirs). */
    private fun leafDirsUnder(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun rec(d: File) {
            val children = d.listFiles() ?: return
            if (children.any { isArtifact(it) || isShardDir(it) }) out.add(d)
            children.forEach { if (it.isDirectory && !isShardDir(it)) rec(it) }
        }
        rec(root)
        return out
    }

    /** Remove now-empty intermediate dirs under [root] (deepest first), leaving [root] itself. */
    private fun pruneEmptyDirs(root: File) {
        if (!root.isDirectory) return
        root.listFiles { f -> f.isDirectory }?.forEach { child ->
            pruneEmptyDirs(child)
            if (child.listFiles()?.isEmpty() == true) child.delete()
        }
    }

    /**
     * Canonical dest name for an artifact of project [base]: the graph → `<base>.json`,
     * a sibling (bare `openapi.json` OR prefixed `graph.openapi.json`) → `<base>.openapi.json`.
     */
    private fun canonicalName(base: String, f: File): String {
        siblingSuffix(f.name)?.let { return "$base.$it" }   // siblings first (bare `openapi.json` is NOT a graph)
        return if (isGraphArtifact(f)) "$base.json" else f.name
    }

    /**
     * Every project found in [src], from the supported layouts — all normalized to one
     * per-project bundle so the dest is always folder-structured (`projects/<name>/<name>.*`):
     *  - folder: `<src>/{projects,service,frontend}/<name>/…` (+ siblings + `<*>.pulls/`/`<*>.impact/`).
     *    The project NAME is the FOLDER name and inner files are RENAMED to `<name>.*`, so it
     *    works whether the inner graph is `<name>.json` (spring), `graph.json` (ts), or a bare
     *    `openapi.json`/`impact.json` sibling (nexcore);
     *  - flat:   `<src>/<base>.json` (+ siblings + shard dirs) — transitional/legacy; name = file base.
     */
    private fun collectProjects(src: File): List<ProjectArtifacts> {
        // key = relPath under the group dir (`<ns>/<repo>/<perRoot>`, or legacy `<name>`).
        val acc = LinkedHashMap<String, Triple<String, MutableList<Pair<File, String>>, MutableList<Pair<File, String>>>>()
        fun bucket(relPath: String, base: String) = acc.getOrPut(relPath) { Triple(base, mutableListOf(), mutableListOf()) }
        // Each analyzer stages a `<group>/.../<perRoot>/` tree (depth-agnostic: legacy `<group>/<name>/`
        // AND nested `<group>/<ns>/<repo>/<perRoot>/`). Recurse each group dir to the LEAF project dirs
        // (those directly holding artifacts/shard dirs) and remember their path so the dest mirrors it.
        for (group in FOLDER_GROUPS) {
            val groupDir = File(src, group)
            if (!groupDir.isDirectory) continue
            fun rec(d: File) {
                val children = d.listFiles() ?: return
                if (children.any { isArtifact(it) || isShardDir(it) }) {
                    val relPath = d.relativeTo(groupDir).path.replace(File.separatorChar, '/')
                    val b = bucket(relPath, d.name)
                    children.forEach { f ->
                        when {
                            isArtifact(f) -> b.second.add(f to canonicalName(d.name, f))
                            isShardDir(f) -> b.third.add(f to "${d.name}.${if (f.name.endsWith(".pulls")) "pulls" else "impact"}")
                        }
                    }
                }
                children.forEach { if (it.isDirectory && !isShardDir(it)) rec(it) }
            }
            groupDir.listFiles { f -> f.isDirectory }?.forEach { rec(it) }
        }
        // Flat is a STRICT fallback: only when the source has no folder-grouped projects
        // (a not-yet-migrated analyzer). Otherwise stale pre-migration flat files at the
        // root (e.g. a leftover `graph-<svc>.json`) would surface as phantom projects.
        if (acc.isEmpty()) {
            src.listFiles { f -> isArtifact(f) }?.forEach { val b = baseOf(it.name); bucket(b, b).second.add(it to it.name) }
            src.listFiles { f -> isShardDir(f) }?.forEach { val b = shardBaseOf(it.name); bucket(b, b).third.add(it to it.name) }
        }
        return acc.entries.filter { it.value.second.isNotEmpty() || it.value.third.isNotEmpty() }
            .map { (rel, t) -> ProjectArtifacts(rel, t.first, t.second, t.third) }
    }

    fun run(sources: List<File>, dest: File): Result {
        dest.mkdirs()
        val destC = dest.canonicalFile
        var copied = 0
        // base -> "frontend"/"backend" for every project synced this run (type-scoped pruning).
        val syncedType = LinkedHashMap<String, String>()

        for (src in sources) {
            if (!src.isDirectory) { System.err.println("  sync: source dir missing: ${src.path}"); continue }
            if (src.canonicalFile == destC) { System.err.println("  sync: ${src.path} is the dest (already in place)"); continue }
            val projects = collectProjects(src)
            for (pa in projects) {
                val destPdir = File(dest, "projects/${pa.relPath}").also { it.mkdirs() }
                val keep = HashSet<String>()
                for ((f, destName) in pa.files) {
                    f.copyTo(File(destPdir, destName), overwrite = true); copied++; keep.add(destName)
                    if (isGraphArtifact(f)) syncedType[pa.relPath] = if (Manifest.isFrontendGraph(f)) "frontend" else "backend"
                }
                // mirror lazy-load shard dirs (drop then copy) so stale shards don't linger.
                for ((d, destName) in pa.shardDirs) {
                    val target = File(destPdir, destName); target.deleteRecursively(); d.copyRecursively(target, overwrite = true)
                    copied += d.listFiles { f -> f.isFile }?.size ?: 0; keep.add(destName)
                }
                // (3) stale-sibling prune WITHIN this project folder: artifacts/shard dirs not re-synced.
                destPdir.listFiles()?.forEach { existing ->
                    if (existing.name in keep) return@forEach
                    when {
                        isArtifact(existing) -> pruneArtifact(existing, "pruned stale")
                        isShardDir(existing) -> if (existing.deleteRecursively()) System.err.println("    ~ pruned stale ${pa.base}/${existing.name}/")
                    }
                }
            }
            System.err.println("  sync: ${src.path} -> ${projects.size} projects")
        }

        // legacy: remove pre-`projects/` bare single-graph files + any flat per-project
        // artifacts left at the dest root by the old layout (the manifest now lives under projects/).
        LEGACY.forEach { val lf = File(dest, it); if (lf.delete()) System.err.println("    ~ removed legacy $it") }
        dest.listFiles { f -> isArtifact(f) }?.forEach { pruneArtifact(it, "pruned legacy flat") }
        dest.listFiles { f -> isShardDir(f) }?.forEach { if (it.deleteRecursively()) System.err.println("    ~ pruned legacy flat ${it.name}/") }

        // (2) departed-project prune: a `projects/<ns>/<repo>/<perRoot>/` leaf gone from every source.
        // Type-scoped — only pruned when a graph of the SAME type was synced this run, so a
        // partial (e.g. backend-only) sync never wipes the other side. Keyed by relPath.
        val destProjects = File(dest, "projects")
        val syncedFrontend = syncedType.containsValue("frontend")
        val syncedBackend = syncedType.containsValue("backend")
        leafDirsUnder(destProjects).forEach { pd ->
            val relPath = pd.relativeTo(destProjects).path.replace(File.separatorChar, '/')
            if (relPath in syncedType) return@forEach                     // freshly synced — keep
            val graph = pd.listFiles { f -> isGraphArtifact(f) }?.firstOrNull() ?: return@forEach
            val frontend = Manifest.isFrontendGraph(graph)
            if (!(frontend && syncedFrontend || !frontend && syncedBackend)) return@forEach  // type not synced — leave it
            if (pd.deleteRecursively()) System.err.println("    ~ pruned departed ${if (frontend) "frontend" else "backend"} projects/$relPath/")
        }
        pruneEmptyDirs(destProjects)   // drop now-empty <ns>/<repo>/ parents left after leaf prunes

        val projects = Manifest.write(dest, "manifest.json")
        System.err.println("    + manifest.json ($projects projects)")
        return Result(copied, projects)
    }
}
