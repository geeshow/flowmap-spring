package com.flowmap.callgraph

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Reads a monorepo's `wallga.yml` to redefine project boundaries by SUB-PROJECT.
 *
 * A single git repo may bundle several independently-deployable units. `wallga.yml`
 * declares them under `advanced.sub_project.projects.<key>`, each carrying a
 * `general.project_name` (the deployable's name) and a `build.path` list (the source
 * directories that make up that unit, relative to the repo root). When this file is
 * present at a repo root, the analyzer treats each sub-project as its own "project"
 * (its own graph/openapi/impact, split into `projects/<name>/`) instead of analyzing
 * the whole repo as one — and attributes per-PR impact to a sub-project by filtering
 * the PR's changed files to that sub-project's `build.path` prefixes.
 *
 * Tolerant: a missing file, malformed YAML, or a sub-project without a usable
 * name/path is skipped (→ empty list / dropped entry), so a repo without wallga.yml
 * falls back to the legacy whole-repo behavior.
 */
object Wallga {

    /** One deployable unit: its [name] (= general.project_name) and source [paths] (= build.path, repo-relative, '/'-normalized). */
    data class SubProject(val name: String, val paths: List<String>)

    /** The conventional file name at a repo root. */
    const val FILE_NAME = "wallga.yml"

    fun file(repoRoot: File): File = File(repoRoot, FILE_NAME)

    fun exists(repoRoot: File): Boolean = file(repoRoot).isFile

    /**
     * Parse `<repoRoot>/wallga.yml` into sub-projects. Returns empty when the file is
     * absent/unreadable or declares no usable `advanced.sub_project.projects`. Entries
     * lacking a name or any `build.path` are dropped.
     */
    fun subProjects(repoRoot: File): List<SubProject> {
        val f = file(repoRoot)
        if (!f.isFile) return emptyList()
        val root = runCatching { f.inputStream().use { Yaml().load<Any?>(it) } }.getOrNull() ?: return emptyList()
        val projects = nav(root, "advanced", "sub_project", "projects") as? Map<*, *> ?: return emptyList()
        val out = LinkedHashMap<String, SubProject>()  // first-seen wins; de-dup by name
        for ((key, raw) in projects) {
            val cfg = raw as? Map<*, *> ?: continue
            val name = (nav(cfg, "general", "project_name") as? String)?.trim()?.ifEmpty { null }
                ?: (key as? String)?.trim()?.ifEmpty { null } ?: continue
            val paths = pathList(nav(cfg, "build", "path"))
            if (paths.isEmpty()) continue
            out.putIfAbsent(name, SubProject(name, paths))
        }
        return out.values.toList()
    }

    /** Walk a nested YAML map by [keys]; null if any step is missing or not a map. */
    private fun nav(node: Any?, vararg keys: String): Any? {
        var cur = node
        for (k in keys) cur = (cur as? Map<*, *>)?.get(k) ?: return null
        return cur
    }

    /** A YAML scalar or list of scalars → normalized, de-duplicated path list (trailing '/' trimmed). */
    private fun pathList(node: Any?): List<String> {
        val raw = when (node) {
            is List<*> -> node.mapNotNull { it as? String }
            is String -> listOf(node)
            else -> emptyList()
        }
        return raw.map { it.trim().replace('\\', '/').removePrefix("./").trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** True if [relPath] (repo-relative) falls under any of [paths] (the path itself or a descendant). */
    fun matches(relPath: String, paths: List<String>): Boolean {
        val n = relPath.replace('\\', '/')
        return paths.any { n == it || n.startsWith("$it/") }
    }

    /**
     * The sub-project that OWNS [relPath] (repo-relative): the one whose `build.path`
     * most specifically contains it (longest matching prefix wins, so nested paths
     * disambiguate). Returns null when no sub-project claims it — i.e. shared/common
     * code (a module not listed under any `build.path`), which the caller treats as
     * its own stand-alone project so its nodes and the edges into it are never dropped.
     */
    fun owningProject(relPath: String, subs: List<SubProject>): SubProject? {
        val n = relPath.replace('\\', '/')
        var best: SubProject? = null
        var bestLen = -1
        for (sp in subs) for (p in sp.paths) {
            if ((n == p || n.startsWith("$p/")) && p.length > bestLen) { best = sp; bestLen = p.length }
        }
        return best
    }
}
