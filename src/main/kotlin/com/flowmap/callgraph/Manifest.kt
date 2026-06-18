package com.flowmap.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Lightweight `_manifest.json` writer — a shared contract across the 3 codebases
 * (backend analyzer / ts-analyzer / web). It is an ADDITIVE artifact: existing
 * outputs (`<project>.json`, `<project>.openapi.json`, `_combined.json`,
 * `_openapi.json`) are left untouched.
 *
 * Schema:
 * {
 *   "version": 1,
 *   "generated": "<ISO8601 UTC>",
 *   "projects": [
 *     { "name", "type": "backend", "graph": "<p>.json",
 *       "openapi": "<p>.openapi.json"|null, "impact": "<p>.impact.json"|null,
 *       "join": null, "screens": null, "nodes": N, "edges": M, "generated": "<ISO8601>" }
 *   ]
 * }
 *
 * The project set is decided by a directory scan that mirrors the `combine`
 * input filter: pure `<project>.json` files only (exclude `_*.json` and the
 * `.openapi.json`/`.impact.json`/`.join.json`/`.screens.json` siblings).
 */
object Manifest {
    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
    }

    /** ISO8601 UTC instant, e.g. `2026-06-13T11:00:00Z`. */
    private fun iso(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.atZone(java.time.ZoneOffset.UTC).toInstant())

    /** True for a pure call-graph JSON: not an aggregate (`_*`) or sibling artifact. */
    private fun isGraphFile(f: File): Boolean =
        f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") &&
            f.name != "manifest.json" &&  // app-facing manifest, not a project graph
            !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json") &&
            !f.name.endsWith(".pulls.json") && !f.name.endsWith(".gateway.json") &&
            !f.name.endsWith(".join.json") && !f.name.endsWith(".screens.json")

    /**
     * Project graph JSONs under [dir]: both the FLAT layout (`<dir>/<name>.json`, e.g.
     * frontend analyzers) and the per-project FOLDER layout (`<dir>/projects/<name>/<name>.json`,
     * the backend's `wallga`/projects split). Same filter `combine --dir` uses.
     */
    private fun projectGraphFiles(dir: File): List<File> {
        val flat = dir.listFiles { f -> isGraphFile(f) }?.toList() ?: emptyList()
        val nested = File(dir, "projects").listFiles { f -> f.isDirectory }
            ?.flatMap { pd -> pd.listFiles { f -> isGraphFile(f) }?.toList() ?: emptyList() } ?: emptyList()
        return (flat + nested).sortedBy { it.name }
    }

    /** Frontend-only node layers — their presence marks a graph as a frontend graph. */
    private val FRONTEND_LAYERS = setOf("SCREEN", "HOOK", "STORE", "API")

    /**
     * True if [graphFile] is a frontend (ts-analyzer) graph — any node carries a
     * frontend-only layer. Used by `refresh` to avoid pruning frontend artifacts
     * from a shared output dir. Unreadable/non-graph files read as non-frontend.
     */
    fun isFrontendGraph(graphFile: File): Boolean = try {
        mapper.readTree(graphFile.readText())["nodes"]?.takeIf { it.isArray }
            ?.any { it["layer"]?.asText() in FRONTEND_LAYERS } ?: false
    } catch (_: Exception) { false }

    /**
     * One manifest entry for a `<base>.json` graph. Type is detected from node
     * layers (so a shared output dir holding BOTH backend and frontend graphs is
     * catalogued correctly regardless of which tool wrote the manifest last), and
     * every sibling that exists on disk is linked (`openapi`/`impact` for backend,
     * `join`/`screens` for frontend).
     */
    private fun entryFor(dir: File, graphFile: File): LinkedHashMap<String, Any?> {
        val base = graphFile.name.removeSuffix(".json")
        val root = mapper.readTree(graphFile.readText())
        val meta = root["meta"]
        val nodesArr = root["nodes"]?.takeIf { it.isArray }
        val nodes = meta?.get("nodes")?.takeIf { it.isNumber }?.asInt() ?: (nodesArr?.size() ?: 0)
        val edges = meta?.get("edges")?.takeIf { it.isNumber }?.asInt()
            ?: (root["edges"]?.takeIf { it.isArray }?.size() ?: 0)
        val isFrontend = nodesArr?.any { it["layer"]?.asText() in FRONTEND_LAYERS } ?: false
        // Path fields are RELATIVE TO [dir] (the data dir): a flat graph is just its file
        // name; a folder-layout graph is `projects/<name>/<file>`. The web app fetches
        // `data/<field>`, and impact/pull shard dirs are derived by stripping `.json`.
        val parentRel = graphFile.parentFile.relativeToOrNull(dir)?.path?.replace(File.separatorChar, '/')?.ifEmpty { null }
        fun rel(name: String) = if (parentRel == null) name else "$parentRel/$name"
        fun sibling(suffix: String) = File(graphFile.parentFile, "$base.$suffix").takeIf { it.isFile }?.let { rel(it.name) }
        // Project name = the FOLDER name in the folder layout (works whether the inner graph
        // is `<name>.json` (backend) or `graph.json` (frontend)); the bare file base when flat.
        val projectName = if (parentRel == null) base else graphFile.parentFile.name
        return linkedMapOf(
            "name" to projectName,
            "type" to if (isFrontend) "frontend" else "backend",
            // git work tree this service belongs to (frontend analyzer stamps meta.gitRepo);
            // lets repo-level views group a monorepo's sibling sub-roots. Null when absent.
            "repo" to meta?.get("gitRepo")?.takeIf { it.isTextual }?.asText(),
            "graph" to rel(graphFile.name),
            "openapi" to sibling("openapi.json"),
            "impact" to sibling("impact.json"),
            "pulls" to sibling("pulls.json"),
            "gateway" to sibling("gateway.json"),
            "join" to sibling("join.json"),
            "screens" to sibling("screens.json"),
            "nodes" to nodes,
            "edges" to edges,
            "entryPoints" to entryPointTotals(nodesArr),
            "modules" to moduleSummaries(nodesArr),
            "generated" to iso(Instant.ofEpochMilli(graphFile.lastModified())),
        )
    }

    /** Project-wide entry-point counts by kind, e.g. `{"HTTP":12,"KAFKA":2}` (empty when none). */
    private fun entryPointTotals(nodesArr: com.fasterxml.jackson.databind.JsonNode?): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        nodesArr?.forEach { n ->
            n["entryPoint"]?.takeIf { !it.isNull }?.asText()?.let { out[it] = (out[it] ?: 0) + 1 }
        }
        return out
    }

    /**
     * Per-module breakdown so a multi-module project is catalogued module-by-module:
     * each entry carries its node count and its entry-point counts by kind. A module
     * with no entry points (e.g. a pure domain/library module) still appears, with an
     * empty `entryPoints` map — making "which module owns the endpoints" explicit.
     */
    private fun moduleSummaries(nodesArr: com.fasterxml.jackson.databind.JsonNode?): List<LinkedHashMap<String, Any?>> {
        if (nodesArr == null) return emptyList()
        data class Acc(var nodes: Int = 0, val eps: LinkedHashMap<String, Int> = LinkedHashMap())
        val byModule = LinkedHashMap<String, Acc>()
        nodesArr.forEach { n ->
            val module = n["module"]?.takeIf { !it.isNull }?.asText() ?: return@forEach
            val acc = byModule.getOrPut(module) { Acc() }
            acc.nodes++
            n["entryPoint"]?.takeIf { !it.isNull }?.asText()?.let { acc.eps[it] = (acc.eps[it] ?: 0) + 1 }
        }
        return byModule.entries.sortedBy { it.key }.map { (name, acc) ->
            linkedMapOf<String, Any?>("name" to name, "nodes" to acc.nodes, "entryPoints" to acc.eps)
        }
    }

    /**
     * Repo-level (graph-less) impact folders: a `projects/<name>/` carrying a `<name>.impact.json`
     * but NO `<name>.json` graph. Emitted for a wallga monorepo whose PR impact is analyzed once for
     * the whole work tree (not per sub-project), so it has no service graph of its own. Linked here
     * (with `graph:null`) so the commit/PR views can load the repo's impact; the overview/structure
     * views — which need a graph — skip it. Each is its own `repo` id.
     */
    private fun impactOnlyEntries(dir: File): List<LinkedHashMap<String, Any?>> {
        val pdirs = File(dir, "projects").listFiles { f -> f.isDirectory } ?: return emptyList()
        return pdirs.sortedBy { it.name }.mapNotNull { pd ->
            val name = pd.name
            if (File(pd, "$name.json").isFile) return@mapNotNull null            // has a graph → entryFor handles it
            val impact = File(pd, "$name.impact.json").takeIf { it.isFile } ?: return@mapNotNull null
            fun rel(n: String) = "projects/$name/$n"
            fun sibling(suffix: String) = File(pd, "$name.$suffix").takeIf { it.isFile }?.let { rel(it.name) }
            linkedMapOf<String, Any?>(
                "name" to name,
                "type" to "backend",
                "repo" to name,                                                  // the repo aggregate IS its own repo id
                "graph" to null,                                                 // no service graph — commit/PR views only
                "openapi" to sibling("openapi.json"),
                "impact" to rel(impact.name),
                "pulls" to sibling("pulls.json"),
                "gateway" to null,
                "join" to null,
                "screens" to null,
                "nodes" to 0,
                "edges" to 0,
                "entryPoints" to LinkedHashMap<String, Int>(),
                "modules" to emptyList<Any?>(),
                "generated" to iso(Instant.ofEpochMilli(impact.lastModified())),
            )
        }
    }

    /**
     * Build the manifest entries + serialized JSON for [dir], then write it into
     * [dir] as [fileName] (default `_manifest.json`; the sync step writes the
     * app-facing `manifest.json`). Returns the number of project entries written.
     */
    fun write(dir: File, fileName: String = "_manifest.json"): Int {
        val projects = projectGraphFiles(dir).map { entryFor(dir, it) } + impactOnlyEntries(dir)
        val manifest = linkedMapOf<String, Any?>(
            "version" to 1,
            "generated" to iso(Instant.now()),
            "projects" to projects,
        )
        File(dir, fileName).writeText(mapper.writeValueAsString(manifest))
        return projects.size
    }
}
