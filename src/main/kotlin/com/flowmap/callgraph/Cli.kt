package com.flowmap.callgraph

import java.io.File
import kotlin.system.exitProcess

/**
 * Default analysis root. The analyzer runs from the project root and the
 * analyzed projects live under `.repo/`, so the default `--repo` is `.repo`.
 * Override with `--repo <dir>`.
 */
const val DEFAULT_REPO = ".repo"

/**
 * Config file read when the program is launched with NO args (e.g. a bare
 * `./gradlew run`). Path is overridable with the `FLOWMAP_CONFIG` env var.
 * Format: shell-style `KEY=VALUE` lines, `#` comments, `${VAR}`/`$VAR`
 * expansion against earlier keys then the environment. Recognised keys:
 *   COMMAND   the subcommand to run         (default: refresh)
 *   REPO      analysis repo root            -> --repo
 *   OUT_DIR   output directory              -> --out-dir
 *   SYNC_DIR  web app data dir to assemble  -> --sync-dir   (optional)
 *   FRONTEND_DIR  ts-analyzer output dir(s) -> --frontend-dir (optional, CSV)
 *   PUBLIC_ONLY  true -> --public-only      (drop non-public methods, optional)
 *   EXTRA_ARGS  extra CLI flags, space-separated, appended verbatim
 * Keys used only by the frontend ts-analyzer (NAME, BACKEND, …) are ignored here.
 */
const val DEFAULT_CONFIG = "flowmap.config"

/**
 * CLI. The one-shot command is `refresh`: pull every project under `--repo`,
 * then run ALL analyses at once (call graph + OpenAPI + RestDocs enrichment +
 * per-project commit/impact), and write each project's graph/openapi/impact
 * plus the combined graph, repo-wide OpenAPI and a manifest into `--out-dir`.
 * The other commands are single-analysis tools kept for debugging / ad-hoc use:
 *   analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--profile p] [--props f]
 *   search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
 *   stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
 *
 * With no args, the effective args are read from [DEFAULT_CONFIG] (so a bare
 * `./gradlew run` runs `refresh` against the configured repo/out-dir).
 */
fun main(rawArgs: Array<String>) {
    val args = if (rawArgs.isEmpty()) argsFromConfig() else rawArgs
    if (args.isEmpty()) { usage(); exitProcess(2) }
    val cmd = args[0]
    val opts = parseOpts(args.drop(1))
    when (cmd) {
        "analyze" -> cmdAnalyze(opts)
        "refresh" -> cmdRefresh(opts)
        "openapi" -> cmdOpenApi(opts)
        "impact" -> cmdImpact(opts)
        "combine" -> cmdCombine(opts)
        "sync" -> cmdSync(opts)
        "search" -> cmdSearch(opts)
        "stats" -> cmdStats(opts)
        "-h", "--help", "help" -> usage()
        else -> { System.err.println("unknown command: $cmd"); usage(); exitProcess(2) }
    }
}

private class Opts(
    val flags: Map<String, String>,
    val bools: Set<String>,
) {
    operator fun get(k: String): String? = flags[k]
    fun has(k: String): Boolean = k in bools
}

private fun parseOpts(args: List<String>): Opts {
    val flags = HashMap<String, String>()
    val bools = HashSet<String>()
    val boolNames = setOf("--include-other", "--no-pull", "--no-impact", "--no-pull-files", "--refetch-pull-files", "--public-only")
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            if (a in boolNames) { bools.add(a); i++ }
            else { flags[a] = args.getOrNull(i + 1) ?: ""; i += 2 }
        } else i++
    }
    return Opts(flags, bools)
}

private fun loadProps(path: String?): Map<String, String> {
    if (path == null) return emptyMap()
    val f = File(path)
    if (!f.isFile) return emptyMap()
    return f.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) null
        else t.indexOf('=').takeIf { it > 0 }?.let { t.substring(0, it).trim() to t.substring(it + 1).trim() }
    }.toMap()
}

/**
 * Build the effective CLI args from [DEFAULT_CONFIG] (or `$FLOWMAP_CONFIG`).
 * Returns an empty array when no config exists, so `main` falls back to usage.
 */
private fun argsFromConfig(): Array<String> {
    val path = System.getenv("FLOWMAP_CONFIG") ?: DEFAULT_CONFIG
    val f = File(path)
    if (!f.isFile) {
        System.err.println("no args and no config file ($path) — run a command, or create $DEFAULT_CONFIG")
        return emptyArray()
    }
    val cfg = parseConfig(f)
    val out = ArrayList<String>()
    out.add(cfg["COMMAND"]?.takeIf { it.isNotBlank() } ?: "refresh")
    cfg["REPO"]?.takeIf { it.isNotBlank() }?.let { out.add("--repo"); out.add(it) }
    cfg["OUT_DIR"]?.takeIf { it.isNotBlank() }?.let { out.add("--out-dir"); out.add(it) }
    cfg["SYNC_DIR"]?.takeIf { it.isNotBlank() }?.let { out.add("--sync-dir"); out.add(it) }
    cfg["FRONTEND_DIR"]?.takeIf { it.isNotBlank() }?.let { out.add("--frontend-dir"); out.add(it) }
    cfg["PUBLIC_ONLY"]?.takeIf { it.equals("true", true) || it == "1" }?.let { out.add("--public-only") }
    cfg["EXTRA_ARGS"]?.takeIf { it.isNotBlank() }?.let { extra ->
        out.addAll(extra.split(Regex("\\s+")).filter { it.isNotEmpty() })
    }
    System.err.println("config: ${f.path} -> ${out.joinToString(" ")}")
    return out.toTypedArray()
}

private val CONFIG_VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")

/**
 * Parse a shell-style `KEY=VALUE` config: skips blank/`#` lines, strips matching
 * surrounding quotes, and expands `${VAR}`/`$VAR` against keys already parsed
 * (in order) then the process environment (unknown vars expand to empty).
 */
private fun parseConfig(f: File): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (line in f.readLines()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) continue
        val eq = t.indexOf('=')
        if (eq <= 0) continue
        val k = t.substring(0, eq).trim()
        var v = t.substring(eq + 1).trim()
        if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) {
            v = v.substring(1, v.length - 1)
        }
        map[k] = CONFIG_VAR.replace(v) { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            map[name] ?: System.getenv(name) ?: ""
        }
    }
    return map
}

private fun graphFromOpts(opts: Opts): Pair<CallGraph, Int> {
    opts["--graph"]?.let { g ->
        return JsonOutput.read(File(g).readText()) to -1
    }
    val repo = opts["--repo"] ?: DEFAULT_REPO
    val files = AnalysisSession().analyze(
        repoRoot = repo,
        projectFilter = opts["--project"],
        profile = opts["--profile"],
        extraProps = loadProps(opts["--props"]),
    )
    return GraphBuilder(files, includeOther = opts.has("--include-other"),
        publicOnly = opts.has("--public-only")).build() to files.size
}

private fun cmdAnalyze(opts: Opts) {
    // Folder mode (granular pipeline): analyze the WHOLE --repo into per-project graphs under
    // `<out-dir>/projects/<name>/<name>.json`, wallga-aware (a monorepo is analyzed once and
    // split into its sub-projects + shared modules — same engine as `refresh`). Single-file
    // `--out` mode below stays for ad-hoc, single-project analysis.
    opts["--out-dir"]?.let { outPath ->
        val repo = File(opts["--repo"] ?: DEFAULT_REPO)
        val outDir = File(outPath).also { it.mkdirs() }
        val projects = discoverGroups(repo).flatMap {
            analyzeGroup(it, opts["--profile"], loadProps(opts["--props"]), opts.has("--include-other"), opts.has("--public-only"))
        }
        if (projects.isEmpty()) { System.err.println("analyze: no projects found under ${repo.path}"); exitProcess(2) }
        val live = projects.mapTo(LinkedHashSet()) { it.name }
        // prune ghost project folders + legacy FLAT per-project graphs (pre-`projects/` layout)
        // so `combine --dir` doesn't double-count a project from both layouts.
        File(outDir, "projects").listFiles { f -> f.isDirectory }?.forEach { d ->
            if (d.name !in live && d.deleteRecursively()) System.err.println("  ~ pruned ghost projects/${d.name}/")
        }
        outDir.listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json"
        }?.forEach { it.delete() }
        for (u in projects) {
            val pdir = projectDir(outDir, u.name)
            File(pdir, "${u.name}.json").writeText(JsonOutput.write(u.graph, linkedMapOf(
                "command" to "analyze", "project" to u.name, "nodes" to u.graph.nodes.size, "edges" to u.graph.edges.size)))
            System.err.println("  + projects/${u.name}/${u.name}.json (${u.graph.nodes.size} nodes, ${u.graph.edges.size} edges)")
        }
        return
    }
    val repo = opts["--repo"] ?: DEFAULT_REPO
    val files = AnalysisSession().analyze(
        repoRoot = repo,
        projectFilter = opts["--project"],
        profile = opts["--profile"],
        extraProps = loadProps(opts["--props"]),
    )
    val descriptions = RestDocs.load(opts["--restdocs"])
    if (opts["--restdocs"] != null) {
        System.err.println("restdocs: loaded ${descriptions.size} API descriptions from ${opts["--restdocs"]}")
    }
    val graph = GraphBuilder(files, includeOther = opts.has("--include-other"), descriptions = descriptions,
        publicOnly = opts.has("--public-only")).build()
    val meta = linkedMapOf<String, Any?>(
        "command" to "analyze", "repo" to repo, "project" to opts["--project"],
        "profile" to opts["--profile"], "files" to files.size,
        "nodes" to graph.nodes.size, "edges" to graph.edges.size,
    )
    dump(graph, opts["--out"], meta)
}

/**
 * Write the split PR file-diffs for one project: a light `<project>.pulls.json`
 * index plus one heavy `<project>.pulls/<number>.json` shard per PR (lazy-loaded
 * on demand).
 *
 * INCREMENTAL: a merged PR's files are immutable, so a PR whose shard already
 * exists on disk is REUSED as-is — no `gh api` call — and only NEW PRs are
 * fetched. Pass [refetch] = true to re-fetch every PR regardless. Stale shards
 * (PRs no longer in the window) are pruned. Returns (fetched, reused) counts.
 */
private fun writePullFiles(projectDir: File, project: String, repo: File, branch: String,
                           pulls: List<GitHub.Pr>, refetch: Boolean,
                           pathFilter: ((String) -> Boolean)? = null): Pair<Int, Int> {
    val shardDirName = "$project.pulls"
    val shardDir = File(projectDir, shardDirName).also { it.mkdirs() }
    val webBase = GitLog.webBaseUrl(repo)
    val entries = ArrayList<Map<String, Any?>>()
    val keep = HashSet<String>()
    var fetched = 0; var reused = 0
    for (pr in pulls) {
        val shardFile = File(shardDir, "${pr.number}.json")
        // reuse an already-collected PR (immutable) unless a refetch is forced
        var shard: Map<String, Any?>? =
            if (!refetch && shardFile.isFile) GitHub.readShard(shardFile)?.also { reused++ } else null
        if (shard == null) {                                   // new PR (or forced/unreadable): collect via git/gh
            val all = GitHub.pullFiles(repo, pr)
            if (all == null) {                                 // both sources failed: preserve any prior shard, don't prune it
                if (shardFile.isFile) {
                    keep.add(shardFile.name)
                    GitHub.readShard(shardFile)?.let { entries.add(GitHub.indexEntry(it, shardDirName)) }
                }
                continue
            }
            // sub-project mode: keep only files under this sub-project's build.path; a PR
            // touching none of them is omitted (mirrors Impact's attribution).
            val files = if (pathFilter == null) all
                else all.filter { pathFilter(it.path) || (it.previousPath?.let(pathFilter) ?: false) }
            if (pathFilter != null && files.isEmpty()) continue
            shard = GitHub.buildShard(pr, files, webBase)
            shardFile.writeText(JsonOutput.writeValue(shard)); fetched++
        }
        keep.add(shardFile.name)
        entries.add(GitHub.indexEntry(shard, shardDirName))
    }
    shardDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { if (it.name !in keep) it.delete() }
    File(projectDir, "$project.pulls.json").writeText(JsonOutput.writeValue(
        GitHub.pullIndexDoc(branch, webBase, shardDirName, entries)))
    return fetched to reused
}

/** Remove a project's PR file-diff artifacts: the `<project>.pulls.json` index and `<project>.pulls/` shard dir. */
private fun removePullFiles(projectDir: File, project: String): Boolean {
    val idx = File(projectDir, "$project.pulls.json").delete()
    val dir = File(projectDir, "$project.pulls").takeIf { it.isDirectory }?.deleteRecursively() ?: false
    return idx || dir
}

/**
 * Write the per-PR impact SHARDS into `<outDir>/<project>.impact/<number>.json` (the
 * heavy detail lazy-loaded by the UI), pruning shards for PRs no longer in this run.
 * The lean `<project>.impact.json` index is written separately by the caller.
 */
private fun writeImpactShards(projectDir: File, project: String, shards: Map<Int, Map<String, Any?>>) {
    val dir = File(projectDir, "$project.impact").also { it.mkdirs() }
    val keep = HashSet<String>()
    for ((number, shard) in shards) {
        File(dir, "$number.json").writeText(JsonOutput.writeValue(shard)); keep.add("$number.json")
    }
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { if (it.name !in keep) it.delete() }
    if (dir.listFiles()?.isEmpty() == true) dir.delete()
}

/** Remove a project's impact artifacts: the `<project>.impact.json` index and `<project>.impact/` shard dir. */
private fun removeImpactFiles(projectDir: File, project: String): Boolean {
    val idx = File(projectDir, "$project.impact.json").delete()
    val dir = File(projectDir, "$project.impact").takeIf { it.isDirectory }?.deleteRecursively() ?: false
    return idx || dir
}

/**
 * One analysis unit = one emitted "project". Legacy: a top-level repo dir (whole repo).
 * Sub-project (wallga.yml present): one deployable carved out of a monorepo by its
 * [sourcePaths] (= `build.path`), sharing the monorepo's [gitRoot] with its siblings.
 */
private class RefreshUnit(
    val name: String,
    val analyzeRoot: File,            // repoRoot passed to AnalysisSession
    val projectFilter: String?,       // legacy single-subdir filter; null in sub-project mode
    val sourcePaths: List<String>?,   // wallga build.path (analyzeRoot-relative); null in legacy mode
    val gitRoot: File,                // git work tree for pull / impact / pull-files
) {
    val isSubProject get() = sourcePaths != null
    /** Dirs to scan for gateway routes / restdocs snippets (each source dir, or the whole repo). */
    private fun unitDirs(): List<File> = sourcePaths?.map { File(analyzeRoot, it) } ?: listOf(gitRoot)
    fun snippets(): String? = unitDirs().map { File(it, "build/generated-snippets") }.firstOrNull { it.isDirectory }?.path
    fun gatewayDirs(): List<File> = unitDirs().filter { it.isDirectory }
    /** Restrict a PR's changed file (repo-relative) to this sub-project's paths; null in legacy mode. */
    fun pathFilter(): ((String) -> Boolean)? = sourcePaths?.let { sp -> { path -> Wallga.matches(path, sp) } }
}

/**
 * The projects to analyze under [repo]. A repo root carrying a `wallga.yml` is expanded
 * into its sub-projects (each its own unit, sharing the repo's git root); a repo without
 * one stays a single whole-repo unit. Checks [repo] itself first (REPO IS the monorepo),
 * then each top-level dir.
 */
private fun discoverUnits(repo: File): List<RefreshUnit> {
    Wallga.subProjects(repo).takeIf { it.isNotEmpty() }?.let { subs ->
        System.err.println("  wallga.yml: ${repo.name} → ${subs.size} sub-projects (${subs.joinToString { it.name }})")
        return subs.map { RefreshUnit(it.name, repo, null, it.paths, repo) }
    }
    val dirs = repo.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
    val out = ArrayList<RefreshUnit>()
    for (p in dirs) {
        val subs = Wallga.subProjects(p)
        if (subs.isNotEmpty()) {
            System.err.println("  wallga.yml: ${p.name} → ${subs.size} sub-projects (${subs.joinToString { it.name }})")
            subs.forEach { out.add(RefreshUnit(it.name, p, null, it.paths, p)) }
        } else {
            out.add(RefreshUnit(p.name, repo, p.name, null, p))
        }
    }
    return out
}

/** Staging output dir for one project: `<outDir>/projects/<name>/` (created on demand). */
private fun projectDir(outDir: File, name: String): File = File(outDir, "projects/$name").also { it.mkdirs() }

/**
 * One ANALYSIS pass = one resolved IR/graph. Legacy: a top-level repo dir analyzed as a
 * single whole-repo project. Monorepo (wallga.yml present): the whole [analyzeRoot] is
 * analyzed ONCE — so calls into shared/common modules resolve and nothing is dropped —
 * then the single graph is PARTITIONED by owning sub-project (plus a stand-alone project
 * for any code under no `build.path`). [gitRoot] is shared by every project of the group.
 */
private class AnalysisGroup(
    val analyzeRoot: File,                       // repoRoot passed to AnalysisSession
    val gitRoot: File,                           // git work tree (pull / impact / pull-files)
    val projectName: String?,                    // legacy whole-repo project name; null in monorepo mode
    val subProjects: List<Wallga.SubProject>?,   // wallga sub-projects; null in legacy mode
)

/** One EMITTED project: its built graph + the inputs the later steps (openapi/gateway/impact) need. */
private class ProjectUnit(
    val name: String,
    val gitRoot: File,
    val graph: CallGraph,
    val files: List<IrFile>,                     // this project's IR files (per-project OpenAPI)
    val snippets: String?,                       // build/generated-snippets dir (REST Docs enrich), if any
    val gatewayDirs: List<File>,                 // dirs scanned for spring.cloud.gateway.routes
    val pathFilter: ((String) -> Boolean)?,      // restrict a PR's changed files to this project (impact); null = whole repo
)

/**
 * The analysis groups under [repo]. A repo root carrying a `wallga.yml` becomes ONE
 * monorepo group (analyzed whole, then partitioned); otherwise each top-level dir is its
 * own group — itself a monorepo group when IT carries a `wallga.yml`, else a legacy
 * whole-repo project. Checks [repo] itself first (REPO IS the monorepo), then its children.
 */
private fun discoverGroups(repo: File): List<AnalysisGroup> {
    Wallga.subProjects(repo).takeIf { it.isNotEmpty() }?.let { subs ->
        System.err.println("  wallga.yml: ${repo.name} → ${subs.size} sub-projects (${subs.joinToString { it.name }})")
        return listOf(AnalysisGroup(repo, repo, null, subs))
    }
    val dirs = repo.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
    val out = ArrayList<AnalysisGroup>()
    for (p in dirs) {
        val subs = Wallga.subProjects(p)
        if (subs.isNotEmpty()) {
            System.err.println("  wallga.yml: ${p.name} → ${subs.size} sub-projects (${subs.joinToString { it.name }})")
            out.add(AnalysisGroup(p, p, null, subs))
        } else {
            out.add(AnalysisGroup(repo, p, p.name, null))
        }
    }
    return out
}

/** The build/generated-snippets dir under the first of [dirs] that has one, or null. */
private fun snippetsIn(dirs: List<File>): String? =
    dirs.map { File(it, "build/generated-snippets") }.firstOrNull { it.isDirectory }?.path

/**
 * Carve project [name]'s subgraph out of the monorepo's [full] graph: every node it owns
 * (`project == name`) plus the shared, project-less target nodes (ext:/redis/db/...) its
 * outgoing edges reference, and exactly those edges whose SOURCE it owns. Edges into ANOTHER
 * sub-project stay (their target node lives in that sub-project's graph) so the manifest-merged
 * and `_combined` views keep cross-project calls; partitioning by source makes the per-project
 * graphs a complete, disjoint cover of the monorepo's edges (no node or edge is lost).
 */
private fun partition(full: CallGraph, name: String): CallGraph {
    val nodeById = full.nodes.associateBy { it.id }
    val ownIds = full.nodes.filter { it.project == name }.mapTo(HashSet()) { it.id }
    val edges = full.edges.filter { it.source in ownIds }
    val keep = HashSet(ownIds)
    for (e in edges) nodeById[e.target]?.let { if (it.project == null) keep.add(it.id) }
    return CallGraph(full.nodes.filter { it.id in keep }, edges)
}

/**
 * Analyze one [group] into its emitted [ProjectUnit]s. Legacy → one project. Monorepo →
 * analyze the whole root once (full cross-module resolution), build one graph, and split it
 * by owning sub-project; code under no `build.path` surfaces as its own (shared) project so
 * its nodes and the edges into it are never dropped.
 */
private fun analyzeGroup(
    group: AnalysisGroup, profile: String?, props: Map<String, String>,
    includeOther: Boolean, publicOnly: Boolean,
): List<ProjectUnit> {
    val subs = group.subProjects
    if (subs == null) {
        val name = group.projectName!!
        val files = AnalysisSession().analyze(group.analyzeRoot.path, name, profile, props)
        if (files.isEmpty()) return emptyList()
        val dirs = listOf(File(group.analyzeRoot, name)).filter { it.isDirectory }
        val snips = snippetsIn(dirs)
        val graph = GraphBuilder(files, includeOther, RestDocs.load(snips), publicOnly = publicOnly).build()
        return listOf(ProjectUnit(name, group.gitRoot, graph, files, snips, dirs, pathFilter = null))
    }
    // Monorepo: one whole-repo pass, stamped per owning sub-project (Provenance).
    val all = AnalysisSession().analyze(group.analyzeRoot.path, null, profile, props, subProjects = subs)
    if (all.isEmpty()) return emptyList()
    // REST Docs for the FULL build = merge of every sub-project's snippets.
    val descriptions = subs.flatMap { it.paths }
        .mapNotNull { snippetsIn(listOf(File(group.analyzeRoot, it))) }.distinct()
        .fold(emptyMap<Pair<String, String>, String>()) { acc, s -> acc + RestDocs.load(s) }
    val full = GraphBuilder(all, includeOther, descriptions, publicOnly = publicOnly).build()
    val subByName = subs.associateBy { it.name }
    val filesByProject = all.groupBy { it.project }
    // Order: declared sub-projects first, then any shared projects (stable, name-sorted).
    val names = (subs.map { it.name } + filesByProject.keys.filterNotNull().sorted())
        .filter { it in filesByProject.keys }.distinct()
    return names.map { name ->
        val files = filesByProject[name].orEmpty()
        val sub = subByName[name]
        val dirs = (sub?.paths ?: listOf(name)).map { File(group.analyzeRoot, it) }.filter { it.isDirectory }
        val filter: (String) -> Boolean =
            if (sub != null) { p -> Wallga.matches(p, sub.paths) }
            else { p -> p.replace('\\', '/').let { it == name || it.startsWith("$name/") } }
        ProjectUnit(name, group.gitRoot, partition(full, name), files, snippetsIn(dirs), dirs, filter)
    }
}

/** One PR-impact target: a project [name], the git work tree to mine, and the optional
 *  changed-file [pathFilter] that attributes a PR to it (sub-project mode). */
private class ImpactTarget(val name: String, val gitRoot: File, val pathFilter: ((String) -> Boolean)?)

/**
 * Per-project PR analysis against the [graph] (the combined graph, so cross-service
 * breaking-change detection sees external callers): impact index + shards + lazy-loaded
 * per-PR file diffs, written into `<outDir>/projects/<name>/`. Targets sharing a git work
 * tree fetch its merged-PR list ONCE (cached) and attribute each PR via their [pathFilter].
 * Shared by `refresh` (step 5) and the granular `impact --out-dir` step.
 */
private fun runImpactStep(targets: List<ImpactTarget>, graph: CallGraph, outDir: File, opts: Opts) {
    val impactMax = opts["--impact-max"]?.toIntOrNull() ?: opts["--max"]?.toIntOrNull() ?: 10
    System.err.println("[impact] PR analysis (impact + pull-files): ${targets.size} project(s), max=$impactMax")
    var impactCount = 0; var skipped = 0; var failed = 0
    val pullsCache = HashMap<String, List<GitHub.Pr>?>()   // (gitRoot|branch) -> pulls (null = no source)
    for (u in targets) {
        val g = u.gitRoot
        val isRoot = GitLog.isRepoRoot(g)
        val branch = if (isRoot) GitLog.resolveBranch(g, opts["--branch"]) else null
        val pdir = projectDir(outDir, u.name)
        when {
            !isRoot -> { System.err.println("  · ${u.name}: skip — not a standalone git repo"); skipped++ }
            branch == null -> { System.err.println("  · ${u.name}: skip — no default branch (try --branch)"); skipped++ }
            else -> {
                val cacheKey = "${g.canonicalPath} $branch"
                val pulls = pullsCache.getOrPut(cacheKey) {
                    System.err.println("  → ${g.name}@$branch: fetching merged PRs (git-first, max $impactMax)…")
                    GitHub.mergedPulls(g, branch, impactMax)
                }
                val impactFile = File(pdir, "${u.name}.impact.json")
                val filter = u.pathFilter
                when {
                    pulls == null -> {             // no PR source: keep any prior impact, don't overwrite/delete
                        System.err.println("  ✗ ${u.name}: no PR source for base $branch (no git PR markers + gh unavailable) — keeping existing impact")
                        failed++
                    }
                    pulls.isEmpty() -> {           // gh ran, no merged PRs: drop stale impact + pull-files so they aren't served
                        val rm = removeImpactFiles(pdir, u.name) or removePullFiles(pdir, u.name)
                        System.err.println("  · ${u.name}: no merged PRs for base $branch — skip" + if (rm) " (removed stale artifacts)" else "")
                        skipped++
                    }
                    else -> {
                        val ok = try {
                            System.err.println("  → ${u.name}: ${pulls.size} PRs — analyzing impact${if (filter != null) " (filtered to build.path)" else ""}…")
                            val result = Impact.analyze(g, branch, pulls, graph, filter)
                            impactFile.writeText(JsonOutput.writeValue(result.index))
                            writeImpactShards(pdir, u.name, result.shards)
                            impactCount++
                            System.err.println("  ✓ projects/${u.name}/${u.name}.impact.json (${result.index["pullCount"]} PRs, ${result.index["changedNodeCount"]} changed nodes, ${result.index["impactedEndpointCount"]} impacted endpoints, ${result.index["breakingDeletionCount"]} breaking)")
                            true
                        } catch (e: Exception) {
                            System.err.println("  ✗ ${u.name}: impact analysis FAILED — ${e.message}")
                            failed++; false
                        }
                        // pull-files is best-effort: a failure here does NOT fail the project (impact already succeeded)
                        if (ok && !opts.has("--no-pull-files")) {
                            try {
                                System.err.println("  → ${u.name}: collecting per-PR file diffs (status+patch)…")
                                val (fetched, reused) = writePullFiles(pdir, u.name, g, branch, pulls, opts.has("--refetch-pull-files"), filter)
                                System.err.println("  ✓ projects/${u.name}/${u.name}.pulls.json (${fetched + reused} PRs: $fetched fetched, $reused reused)")
                            } catch (e: Exception) {
                                System.err.println("  ⚠ ${u.name}: pull-files collection failed — ${e.message} (impact kept)")
                            }
                        }
                    }
                }
            }
        }
    }
    System.err.println("[impact] PR analysis done: $impactCount analyzed, $skipped skipped, $failed failed")
}

/**
 * The PR-impact targets for [repo] (granular `impact --out-dir`), derived WITHOUT re-analysis:
 * a wallga monorepo's sub-projects (attributed by `build.path`) plus any shared-module project
 * already emitted into `<outDir>/projects/<name>/` (attributed by its top-level dir); a legacy
 * repo dir is one whole-repo target. Only projects with an emitted graph are included.
 */
private fun impactTargets(repo: File, outDir: File): List<ImpactTarget> {
    val emitted = { name: String -> File(outDir, "projects/$name/$name.json").isFile }
    val out = ArrayList<ImpactTarget>()
    for (group in discoverGroups(repo)) {
        val subs = group.subProjects
        if (subs == null) {
            val name = group.projectName!!
            if (emitted(name)) out.add(ImpactTarget(name, group.gitRoot, null))
            continue
        }
        val subNames = subs.mapTo(HashSet()) { it.name }
        for (sub in subs) if (emitted(sub.name)) out.add(ImpactTarget(sub.name, group.gitRoot) { p -> Wallga.matches(p, sub.paths) })
        // shared modules: emitted projects under this monorepo's root that no build.path claims
        File(outDir, "projects").listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val name = d.name
            if (name in subNames || !emitted(name) || !File(group.analyzeRoot, name).isDirectory) return@forEach
            out.add(ImpactTarget(name, group.gitRoot) { p -> p.replace('\\', '/').let { it == name || it.startsWith("$name/") } })
        }
    }
    return out
}

/**
 * Write a gateway project's route table to `<projectDir>/<name>.gateway.json`. The web app
 * loads this to resolve front→backend joins through the gateway: a front call to a route's
 * `publicPrefix` is rewritten by `backendPrefix` and matched against the backend endpoint —
 * far more precise than stripping the first path segment.
 */
private fun writeGatewayRoutes(projectDir: File, name: String, routes: List<Gateway.Route>) {
    val doc = linkedMapOf<String, Any?>(
        "command" to "gateway", "name" to name, "routeCount" to routes.size,
        "routes" to routes.map { r ->
            linkedMapOf<String, Any?>(
                "routeId" to r.routeId, "publicPrefix" to r.publicPrefix, "backendPrefix" to r.backendPrefix,
                "targetService" to r.targetService, "methods" to r.methods, "uri" to r.uri,
            )
        },
    )
    File(projectDir, "$name.gateway.json").writeText(JsonOutput.writeValue(doc))
}

private fun cmdRefresh(opts: Opts) {
    val repo = File(opts["--repo"] ?: DEFAULT_REPO)
    val outDir = File(opts["--out-dir"] ?: "./json").also { it.mkdirs() }
    val profile = opts["--profile"]
    val props = loadProps(opts["--props"])
    val includeOther = opts.has("--include-other")
    val publicOnly = opts.has("--public-only")
    val groups = discoverGroups(repo)
    if (groups.isEmpty()) { System.err.println("refresh: no projects found under ${repo.path}"); exitProcess(2) }

    // 1) pull each DISTINCT git work tree's checked-out branch (fast-forward only).
    //    Sub-projects of one monorepo share a git root — pull it once.
    if (!opts.has("--no-pull")) {
        for (g in groups.map { it.gitRoot }.distinctBy { it.canonicalPath }) {
            if (!GitLog.isRepoRoot(g)) { System.err.println("  - ${g.name}: (not a standalone git repo, skip pull)"); continue }
            val branch = GitLog.currentBranch(g)
            val (ok, msg) = GitLog.pull(g)
            val tail = msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: ""
            System.err.println("  - ${g.name}@$branch: ${if (ok) "pulled" else "PULL FAILED"} ($tail)")
        }
    }

    // 2) analyze each group, then emit per project into `<outDir>/projects/<name>/` (graph +
    //    openapi siblings). A wallga monorepo is analyzed ONCE and split into its sub-projects
    //    (plus a stand-alone project per shared module) — see [analyzeGroup]/[partition].
    val projects = groups.flatMap { analyzeGroup(it, profile, props, includeOther, publicOnly) }
    val builtGraphs = ArrayList<CallGraph>()
    val allFiles = ArrayList<IrFile>()
    val liveBases = LinkedHashSet<String>()
    for (u in projects) {
        liveBases.add(u.name)
        allFiles.addAll(u.files)
        builtGraphs.add(u.graph)
        val pdir = projectDir(outDir, u.name)
        File(pdir, "${u.name}.json").writeText(JsonOutput.write(u.graph, linkedMapOf(
            "command" to "analyze", "project" to u.name, "nodes" to u.graph.nodes.size, "edges" to u.graph.edges.size)))
        val oapi = OpenApi.build(u.files, title = u.name, enrich = RestDocs.loadApi(u.snippets))
        File(pdir, "${u.name}.openapi.json").writeText(JsonOutput.writeValue(oapi))
        @Suppress("UNCHECKED_CAST")
        val pPaths = (oapi["paths"] as? Map<String, *>)?.size ?: 0
        System.err.println("  + projects/${u.name}/${u.name}.json (${u.graph.nodes.size} nodes, ${u.graph.edges.size} edges)")
        System.err.println("  + projects/${u.name}/${u.name}.openapi.json ($pPaths paths)")
    }

    // 3) prune ghost project folders (a `projects/<name>/` no longer produced) plus any
    //    legacy FLAT per-project artifacts left from the pre-`projects/` layout. Aggregates
    //    (`_combined.json`/`_openapi.json`/`_manifest.json`) are kept.
    File(outDir, "projects").listFiles { f -> f.isDirectory }?.forEach { d ->
        if (d.name !in liveBases && d.deleteRecursively())
            System.err.println("  ~ pruned ghost projects/${d.name}/")
    }
    outDir.listFiles { f ->
        f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json"
    }?.forEach { it.delete() }
    outDir.listFiles { f -> f.isDirectory && (f.name.endsWith(".pulls") || f.name.endsWith(".impact")) }
        ?.forEach { it.deleteRecursively() }

    // 4) combine (in-memory) + repo-wide OpenAPI. Gateways are AUTO-DISCOVERED from each
    //    unit's resource YAMLs (spring.cloud.gateway.routes). An explicit --gateway-routes
    //    still works as an extra/override gateway.
    val gateways = ArrayList<Gateway.Source>()
    val seenGw = HashSet<String>()
    opts["--gateway-routes"]?.let { path ->
        val name = opts["--gateway-name"] ?: File(path).nameWithoutExtension
        val r = Gateway.load(path, name)
        if (r.isNotEmpty()) { gateways.add(Gateway.Source(name, r)); seenGw.add(name)
            System.err.println("  gateway: ${r.size} routes from $path (as '$name')") }
    }
    for (u in projects) {
        if (u.name in seenGw) continue
        val r = u.gatewayDirs.flatMap { Gateway.discover(it) }
        if (r.isNotEmpty()) { gateways.add(Gateway.Source(u.name, r)); seenGw.add(u.name)
            System.err.println("  gateway: discovered ${r.size} routes in ${u.name}") }
    }
    // Emit each gateway PROJECT's route table as a per-project sibling
    // (projects/<gw>/<gw>.gateway.json) so the web app can join front→backend through the
    // gateway's real publicPrefix→backendPrefix transform (not a strip-first-segment guess).
    for (src in gateways) {
        if (src.name !in liveBases) continue   // only gateways that are themselves analyzed projects
        writeGatewayRoutes(projectDir(outDir, src.name), src.name, src.routes)
        System.err.println("  + projects/${src.name}/${src.name}.gateway.json (${src.routes.size} routes)")
    }
    val combined = CrossRun.combine(builtGraphs, gateways)
    val s2s = combined.edges.count { it.kind == EdgeKind.S2S }
    val gw = combined.edges.count { it.kind == EdgeKind.GATEWAY }
    File(outDir, "_combined.json").writeText(JsonOutput.write(combined, linkedMapOf(
        "command" to "refresh/combine", "projects" to liveBases.toList(),
        "nodes" to combined.nodes.size, "edges" to combined.edges.size, "s2sEdges" to s2s, "gatewayEdges" to gw)))
    System.err.println("  + _combined.json (${combined.nodes.size} nodes, ${combined.edges.size} edges, $s2s s2s, $gw gateway)")
    val allOapi = OpenApi.build(allFiles, title = opts["--title"] ?: "flowmap-all")
    File(outDir, "_openapi.json").writeText(JsonOutput.writeValue(allOapi))
    @Suppress("UNCHECKED_CAST")
    val paths = (allOapi["paths"] as? Map<String, *>)?.size ?: 0
    System.err.println("  + _openapi.json ($paths paths)")

    // 5) per-project PR analysis against the COMBINED graph (so cross-service breaking-change
    // detection sees external callers): impact + lazy-loaded per-PR file diffs, written into
    // `projects/<name>/`. Sub-projects of one monorepo share the repo's merged-PR list (fetched
    // once, cached) and ATTRIBUTE each PR by filtering its changed files to their build.path.
    if (opts.has("--no-impact")) {
        System.err.println("[5/7] PR analysis: skipped (--no-impact)")
    } else {
        runImpactStep(projects.map { ImpactTarget(it.name, it.gitRoot, it.pathFilter) }, combined, outDir, opts)
    }

    // 6) lightweight manifest (additive — leaves _combined.json and friends intact)
    val manifestCount = Manifest.write(outDir)
    System.err.println("  + _manifest.json ($manifestCount projects)")

    // 7) optional sync: assemble the web app's data dir (ports scripts/sync-data.sh).
    //    Copies per-project artifacts from OUT_DIR + any --frontend-dir into
    //    --sync-dir, writes the app-facing manifest.json there, drops legacy files.
    opts["--sync-dir"]?.takeIf { it.isNotBlank() }?.let { syncPath ->
        val dest = File(syncPath)
        val sources = ArrayList<File>().apply {
            add(outDir)
            opts["--frontend-dir"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { add(File(it)) }
        }
        val r = Sync.run(sources, dest)
        System.err.println("  sync: ${r.copied} files copied, manifest.json with ${r.projects} projects -> ${dest.path}")
    }

    System.err.println("refresh done: ${liveBases.size} projects, combined ${combined.nodes.size} nodes / $s2s s2s, openapi $paths paths, manifest $manifestCount projects -> ${outDir.path}")
}

private fun cmdOpenApi(opts: Opts) {
    // Folder mode (granular pipeline): per-project OpenAPI under `<out-dir>/projects/<name>/`,
    // plus a repo-wide `<out-dir>/_openapi.json`. Wallga-aware (same split as analyze/refresh).
    opts["--out-dir"]?.let { outPath ->
        val repo = File(opts["--repo"] ?: DEFAULT_REPO)
        val outDir = File(outPath).also { it.mkdirs() }
        val version = opts["--api-version"] ?: "1.0.0"
        val projects = discoverGroups(repo).flatMap {
            analyzeGroup(it, opts["--profile"], loadProps(opts["--props"]), opts.has("--include-other"), opts.has("--public-only"))
        }
        val allFiles = ArrayList<IrFile>()
        for (u in projects) {
            allFiles.addAll(u.files)
            val doc = OpenApi.build(u.files, title = u.name, version = version, enrich = RestDocs.loadApi(u.snippets))
            File(projectDir(outDir, u.name), "${u.name}.openapi.json").writeText(JsonOutput.writeValue(doc))
            @Suppress("UNCHECKED_CAST")
            val pc = (doc["paths"] as? Map<String, *>)?.size ?: 0
            System.err.println("  + projects/${u.name}/${u.name}.openapi.json ($pc paths)")
        }
        val allDoc = OpenApi.build(allFiles, title = opts["--title"] ?: "flowmap-all", version = version)
        File(outDir, "_openapi.json").writeText(JsonOutput.writeValue(allDoc))
        @Suppress("UNCHECKED_CAST")
        val paths = (allDoc["paths"] as? Map<String, *>)?.size ?: 0
        System.err.println("  + _openapi.json ($paths paths)")
        return
    }
    val repo = opts["--repo"] ?: DEFAULT_REPO
    val files = AnalysisSession().analyze(
        repoRoot = repo,
        projectFilter = opts["--project"],
        profile = opts["--profile"],
        extraProps = loadProps(opts["--props"]),
    )
    val enrich = RestDocs.loadApi(opts["--restdocs"])
    if (opts["--restdocs"] != null) {
        System.err.println("restdocs: loaded ${enrich.size} API descriptions from ${opts["--restdocs"]}")
    }
    val title = opts["--title"] ?: opts["--project"] ?: "API"
    val version = opts["--api-version"] ?: "1.0.0"
    val doc = OpenApi.build(files, title = title, version = version, enrich = enrich)
    val text = JsonOutput.writeValue(doc)
    val out = opts["--out"]
    @Suppress("UNCHECKED_CAST")
    val pathCount = (doc["paths"] as? Map<String, *>)?.size ?: 0
    @Suppress("UNCHECKED_CAST")
    val schemaCount = ((doc["components"] as? Map<String, *>)?.get("schemas") as? Map<String, *>)?.size ?: 0
    if (out != null) {
        File(out).writeText(text)
        System.err.println("wrote $out: $pathCount paths, $schemaCount schemas")
    } else {
        println(text)
    }
}

private fun cmdImpact(opts: Opts) {
    // Folder mode (granular pipeline): per-project PR impact for the WHOLE --repo against the
    // combined --graph, written into `<out-dir>/projects/<name>/`. Wallga-aware — a monorepo's
    // sub-projects (+ shared modules) each get their own impact, attributed by build.path.
    opts["--out-dir"]?.let { outPath ->
        val repo = File(opts["--repo"] ?: DEFAULT_REPO)
        val outDir = File(outPath)
        val graphPath = opts["--graph"] ?: run {
            System.err.println("impact --out-dir: --graph <combined.json> required"); exitProcess(2)
        }
        val graph = JsonOutput.read(File(graphPath).readText())
        val targets = impactTargets(repo, outDir)
        if (targets.isEmpty()) { System.err.println("impact: no emitted projects under ${outDir.path}/projects (run analyze first)"); exitProcess(2) }
        runImpactStep(targets, graph, outDir, opts)
        return
    }
    // git repo to mine: explicit --git, else <--repo>/<--project>, else --repo
    val git = opts["--git"]?.let { File(it) }
        ?: opts["--project"]?.let { File(opts["--repo"] ?: DEFAULT_REPO, it) }
        ?: File(opts["--repo"] ?: DEFAULT_REPO)
    if (!GitLog.isRepo(git)) {
        System.err.println("impact: ${git.path} is not a git work tree (pass --git <repo>)"); exitProcess(2)
    }
    val branch = GitLog.resolveBranch(git, opts["--branch"]) ?: run {
        System.err.println("impact: could not resolve a default branch (try --branch)"); exitProcess(2)
    }
    // current graph: load --graph, else analyze --repo/--project
    val graph = opts["--graph"]?.let { JsonOutput.read(File(it).readText()) } ?: graphFromOpts(opts).first
    val max = opts["--max"]?.toIntOrNull() ?: 10
    val pulls = GitHub.mergedPulls(git, branch, max)
    if (pulls == null) {
        System.err.println("impact: no PR source for base $branch — git has no PR markers (merge/squash) and gh is unavailable"); exitProcess(1)
    }
    if (pulls.isEmpty()) {
        System.err.println("impact: no merged PRs for base $branch"); exitProcess(1)
    }
    System.err.println("impact: ${git.name} base $branch, ${pulls.size} PRs")
    val result = Impact.analyze(git, branch, pulls, graph)
    val out = opts["--out"]
    if (out != null) {
        val outFile = File(out)
        outFile.writeText(JsonOutput.writeValue(result.index))
        // heavy per-PR shards next to the index: <base>.impact/<number>.json
        val base = outFile.name.removeSuffix(".json").removeSuffix(".impact")
        writeImpactShards(outFile.absoluteFile.parentFile ?: File("."), base, result.shards)
        System.err.println("wrote $out + $base.impact/: ${pulls.size} PRs, ${result.index["changedNodeCount"]} changed nodes, " +
            "${result.index["impactedEndpointCount"]} impacted endpoints, ${result.index["breakingDeletionCount"]} breaking")
    } else {
        println(JsonOutput.writeValue(result.index))
    }
    // Optional separate artifact: per-PR file-level diffs (status + patch) via gh api,
    // split into a light `<project>.pulls.json` index + `<project>.pulls/<number>.json`
    // shards under the given output dir (lazy-loaded on demand).
    opts["--pull-files"]?.let { pfDir ->
        val dir = File(pfDir).also { it.mkdirs() }
        val (fetched, reused) = writePullFiles(dir, git.name, git, branch, pulls, opts.has("--refetch-pull-files"))
        System.err.println("wrote ${git.name}.pulls.json (index, ${fetched + reused} PRs) — $fetched fetched, $reused reused in ${git.name}.pulls/ under ${dir.path}")
    }
}

/**
 * Standalone web-data assembly — the same step `refresh` runs last (step 7), but
 * invokable on its own so a pipeline can run it AFTER the frontend analyzer has
 * produced fresh artifacts (refresh's own combine must run BEFORE the frontend to
 * provide `_combined.json`, but its sync must run AFTER). Copies per-project
 * artifacts from `--out-dir` (+ any `--frontend-dir`) into `--sync-dir`, prunes
 * departed/stale files, and (re)writes the app-facing `manifest.json`.
 */
private fun cmdSync(opts: Opts) {
    val outDir = File(opts["--out-dir"] ?: "./json")
    val syncPath = opts["--sync-dir"]?.takeIf { it.isNotBlank() }
        ?: run { System.err.println("sync: --sync-dir <web data dir> required"); exitProcess(2) }
    if (!outDir.isDirectory) { System.err.println("sync: --out-dir ${outDir.path} is not a directory"); exitProcess(2) }
    val sources = ArrayList<File>().apply {
        add(outDir)
        opts["--frontend-dir"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { add(File(it)) }
    }
    val dest = File(syncPath)
    val r = Sync.run(sources, dest)
    System.err.println("sync: ${r.copied} files copied, manifest.json with ${r.projects} projects -> ${dest.path}")
}

private fun cmdCombine(opts: Opts) {
    val paths = collectGraphPaths(opts)
    if (paths.isEmpty()) {
        System.err.println("combine: provide --graphs a.json,b.json,... or --dir <dir of *.json>")
        exitProcess(2)
    }
    val usable = paths.filter { p ->
        JsonOutput.isGraph(File(p).readText()).also {
            if (!it) System.err.println("combine: skipping non-graph JSON ${File(p).name}")
        }
    }
    val graphs = usable.map { JsonOutput.read(File(it).readText()) }
    // Output dir: parent of --out if given, else the scanned --dir, else cwd. Per-project
    // artifacts (and the manifest) live here.
    val manifestDir = opts["--out"]?.let { File(it).absoluteFile.parentFile }
        ?: opts["--dir"]?.let { File(it) }
        ?: File(".")
    val gateways = ArrayList<Gateway.Source>()
    opts["--gateway-routes"]?.let { path ->
        val name = opts["--gateway-name"] ?: File(path).nameWithoutExtension
        val r = Gateway.load(path, name)
        System.err.println("gateway: loaded ${r.size} routes from $path (as '$name')")
        if (r.isNotEmpty()) gateways.add(Gateway.Source(name, r))
    }
    // With --repo, AUTO-DISCOVER gateways from the source tree (like refresh) so the granular
    // analyze→merge pipeline also wires `gateway` edges AND emits each gateway project's route
    // table (<name>.gateway.json) for the web front→backend join. (combine on prebuilt graphs
    // alone has no source tree, so this is opt-in via --repo.)
    opts["--repo"]?.let { repoPath ->
        val repo = File(repoPath)
        for (u in discoverUnits(repo)) {
            val routes = u.gatewayDirs().flatMap { Gateway.discover(it) }
            if (routes.isEmpty()) continue
            gateways.add(Gateway.Source(u.name, routes))
            // write next to the project's existing graph: folder layout if present, else flat.
            val folder = File(manifestDir, "projects/${u.name}")
            val dest = (if (folder.isDirectory) folder else manifestDir).also { it.mkdirs() }
            writeGatewayRoutes(dest, u.name, routes)
            System.err.println("gateway: discovered ${routes.size} routes in ${u.name} -> ${dest.name}/${u.name}.gateway.json")
        }
    }
    val result = CrossRun.combine(graphs, gateways)
    val s2s = result.edges.count { it.kind == EdgeKind.S2S }
    val gw = result.edges.count { it.kind == EdgeKind.GATEWAY }
    val meta = linkedMapOf<String, Any?>(
        "command" to "combine",
        "inputs" to usable.map { File(it).name },
        "projects" to result.nodes.mapNotNull { it.project }.distinct().sorted(),
        "nodes" to result.nodes.size, "edges" to result.edges.size, "s2sEdges" to s2s, "gatewayEdges" to gw,
    )
    dump(result, opts["--out"], meta)
    System.err.println("combined ${usable.size} graphs: ${result.nodes.size} nodes, ${result.edges.size} edges, $s2s s2s, $gw gateway")

    // Lightweight manifest (additive), written into the same output dir computed above.
    if (manifestDir.isDirectory) {
        val n = Manifest.write(manifestDir)
        System.err.println("manifest: ${manifestDir.path}/_manifest.json ($n projects)")
    }
}

/** Graph inputs for `combine`: explicit `--graphs` CSV, and/or every `*.json` under `--dir`. */
private fun collectGraphPaths(opts: Opts): List<String> {
    val out = LinkedHashSet<String>()
    opts["--graphs"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { out.add(it) }
    opts["--dir"]?.let { dir ->
        // Only ingest call-graph JSONs: skip "_*.json" (prior combine output like
        // _combined.json) and sibling artifacts that share the dir but aren't graphs
        // (*.openapi.json, *.impact.json, ...). A defensive non-graph check in cmdCombine
        // also drops anything that slips through. Scans BOTH the flat layout (`<dir>/<name>.json`)
        // and the folder layout (`<dir>/projects/<name>/<name>.json` — what refresh and the
        // wallga split emit), so the granular `combine --dir` sees per-sub-project graphs too.
        val isGraph = { f: File ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json" &&
                !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json") &&
                !f.name.endsWith(".pulls.json") && !f.name.endsWith(".gateway.json") &&
                !f.name.endsWith(".join.json") && !f.name.endsWith(".screens.json")
        }
        val d = File(dir)
        d.listFiles(isGraph)?.sortedBy { it.name }?.forEach { out.add(it.path) }
        File(d, "projects").listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { pd ->
            pd.listFiles(isGraph)?.sortedBy { it.name }?.forEach { out.add(it.path) }
        }
    }
    return out.toList()
}

private fun cmdSearch(opts: Opts) {
    val method = opts["--method"] ?: run { System.err.println("--method required"); exitProcess(2) }
    val (graph, _) = graphFromOpts(opts)
    val matches = Bfs.findNodes(graph, method)
    if (matches.isEmpty()) { System.err.println("no node matches '$method'"); exitProcess(1) }
    if (matches.size > 1) {
        System.err.println("matched ${matches.size} nodes for '$method':")
        matches.forEach { System.err.println("  - ${it.id}  (${it.layer}, ${it.file}:${it.line})") }
    }
    val direction = when (opts["--direction"]) {
        "callers" -> Bfs.Direction.CALLERS
        "callees" -> Bfs.Direction.CALLEES
        else -> Bfs.Direction.BOTH
    }
    val depth = opts["--depth"]?.toIntOrNull() ?: 3
    val sub = Bfs.bfs(graph, matches.map { it.id }, direction, depth)
    val meta = linkedMapOf<String, Any?>(
        "command" to "search", "query" to method, "roots" to matches.map { it.id },
        "direction" to direction.name.lowercase(), "depth" to depth,
        "nodes" to sub.nodes.size, "edges" to sub.edges.size,
    )
    dump(sub, opts["--out"], meta)
}

private fun cmdStats(opts: Opts) {
    val (graph, _) = graphFromOpts(opts)
    val layers = graph.nodes.groupingBy { it.layer.name }.eachCount()
    val kinds = graph.edges.groupingBy { it.kind.json }.eachCount()
    val modes = graph.edges.groupingBy { it.mode.json }.eachCount()
    val external = graph.nodes.count { it.layer == Layer.EXTERNAL }
    val withUrl = graph.nodes.count { it.externalUrl != null }
    println("nodes: ${graph.nodes.size}   edges: ${graph.edges.size}")
    println("layers: $layers")
    println("edge kinds: $kinds")
    println("edge modes: $modes")
    println("external nodes: $external   (with resolved/placeholder URL: $withUrl)")
}

private fun dump(graph: CallGraph, out: String?, meta: Map<String, Any?>) {
    val text = JsonOutput.write(graph, meta)
    if (out != null) {
        File(out).writeText(text)
        System.err.println("wrote $out: ${graph.nodes.size} nodes, ${graph.edges.size} edges")
    } else {
        println(text)
    }
}

private fun usage() {
    System.err.println(
        """
        callgraph (Kotlin Analysis API)   default --repo: $DEFAULT_REPO

          (no args) — read the command + flags from $DEFAULT_CONFIG (or ${'$'}FLOWMAP_CONFIG):
                      COMMAND=refresh / REPO=.repo / OUT_DIR=<dir> / EXTRA_ARGS=...
                      lets `./gradlew run` work with zero arguments.

          refresh — ONE-SHOT: pull every project + run ALL analyses (graph + openapi + restdocs + impact)
                    + combine (auto-discovers gateways from spring.cloud.gateway.routes) + manifest
                    + optional sync (assemble the web app's data dir; ports sync-data.sh)
            refresh [--repo <dir>] [--out-dir ./json] [--no-pull] [--no-impact] [--no-pull-files] [--refetch-pull-files]
                    [--impact-max N (default 10)] [--branch b]
                    # --no-pull-files: skip per-PR file diffs (status+patch) -> <project>.pulls.json + <project>.pulls/<n>.json
                    # incremental by default: PRs with an existing shard are reused (no gh call); --refetch-pull-files forces re-fetch
                    [--include-other] [--public-only] [--profile p] [--props kv.txt] [--title T]
                    [--gateway-routes routes.yml] [--gateway-name N]   # explicit gateway (else auto-discovered)
                    [--sync-dir <web data dir>] [--frontend-dir d1,d2] # copy per-project artifacts + manifest.json there
                    # --public-only: keep only public methods, contracting public->private->public to public->public

          --- granular pipeline steps (per-project folder layout; wallga-aware, same engine as refresh) ---
          # --out-dir splits the WHOLE --repo into projects/<name>/<name>.* (a wallga.yml monorepo →
          # its sub-projects + a stand-alone project per shared module); without it, the single-project
          # --out / --project forms below stay for ad-hoc debugging.
          analyze --repo <dir> --out-dir <dir>   [--include-other] [--public-only] [--profile p] [--props kv.txt]
          openapi --repo <dir> --out-dir <dir>   [--title T] [--api-version V] [--profile p] [--props kv.txt]
          impact  --repo <dir> --out-dir <dir> --graph _combined.json  [--branch b] [--max N (default 10)] [--no-pull-files] [--refetch-pull-files]

          --- single-analysis tools (debugging / ad-hoc) ---
          analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--public-only] [--profile p] [--props kv.txt] [--restdocs dir]
          openapi --repo <dir> [--project P] [--out f.json] [--restdocs dir] [--title T] [--api-version V] [--profile p] [--props kv.txt]
          impact  --git <repo> (--graph g.json | --repo <dir> --project P) [--branch b] [--max N (default 10)] [--out f.json] [--pull-files <dir>] [--refetch-pull-files]
                  # change-impact per merged PR (git-first: `git log --first-parent`; falls back to `gh` only if git finds no PR markers)
                  # --pull-files <dir>: also write a <project>.pulls.json index + <project>.pulls/<number>.json shards (lazy-load, incremental)
          combine --graphs a.json,b.json,... | --dir <dir of *.json> [--repo <dir>] [--gateway-routes routes.yml] [--gateway-name N] [--out f.json]
                  # --repo: auto-discover gateway routes from the source tree → wire gateway edges + emit <name>.gateway.json
          sync    --out-dir <analyzer out> --sync-dir <web data dir> [--frontend-dir d1,d2]
                  # assemble the web data dir from existing artifacts (refresh's step 7, standalone) — run AFTER the frontend analyzer
          search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
          stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
        """.trimIndent()
    )
}
