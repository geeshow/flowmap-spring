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

    /**
     * The same input filter `combine --dir` uses: pure call-graph JSONs only.
     * Excludes `_*.json` (combine output like `_combined.json`) and sibling
     * artifacts (`*.openapi.json`, `*.impact.json`, `*.join.json`, `*.screens.json`).
     */
    private fun projectGraphFiles(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") &&
                f.name != "manifest.json" &&  // app-facing manifest, not a project graph
                !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json") &&
                !f.name.endsWith(".pulls.json") &&
                !f.name.endsWith(".join.json") && !f.name.endsWith(".screens.json")
        }?.sortedBy { it.name } ?: emptyList()

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
        fun sibling(suffix: String) = File(dir, "$base.$suffix").takeIf { it.isFile }?.name
        return linkedMapOf(
            "name" to base,
            "type" to if (isFrontend) "frontend" else "backend",
            "graph" to graphFile.name,
            "openapi" to sibling("openapi.json"),
            "impact" to sibling("impact.json"),
            "pulls" to sibling("pulls.json"),
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
     * Build the manifest entries + serialized JSON for [dir], then write it into
     * [dir] as [fileName] (default `_manifest.json`; the sync step writes the
     * app-facing `manifest.json`). Returns the number of project entries written.
     */
    fun write(dir: File, fileName: String = "_manifest.json"): Int {
        val projects = projectGraphFiles(dir).map { entryFor(dir, it) }
        val manifest = linkedMapOf<String, Any?>(
            "version" to 1,
            "generated" to iso(Instant.now()),
            "projects" to projects,
        )
        File(dir, fileName).writeText(mapper.writeValueAsString(manifest))
        return projects.size
    }
}
