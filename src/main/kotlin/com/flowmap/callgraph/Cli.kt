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
private fun writePullFiles(outDir: File, project: String, repo: File, branch: String,
                           pulls: List<GitHub.Pr>, refetch: Boolean): Pair<Int, Int> {
    val shardDirName = "$project.pulls"
    val shardDir = File(outDir, shardDirName).also { it.mkdirs() }
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
            val files = GitHub.pullFiles(repo, pr)
            if (files == null) {                               // both sources failed: preserve any prior shard, don't prune it
                if (shardFile.isFile) {
                    keep.add(shardFile.name)
                    GitHub.readShard(shardFile)?.let { entries.add(GitHub.indexEntry(it, shardDirName)) }
                }
                continue
            }
            shard = GitHub.buildShard(pr, files, webBase)
            shardFile.writeText(JsonOutput.writeValue(shard)); fetched++
        }
        keep.add(shardFile.name)
        entries.add(GitHub.indexEntry(shard, shardDirName))
    }
    shardDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { if (it.name !in keep) it.delete() }
    File(outDir, "$project.pulls.json").writeText(JsonOutput.writeValue(
        GitHub.pullIndexDoc(branch, webBase, shardDirName, entries)))
    return fetched to reused
}

/** Remove a project's PR file-diff artifacts: the `<project>.pulls.json` index and `<project>.pulls/` shard dir. */
private fun removePullFiles(outDir: File, project: String): Boolean {
    val idx = File(outDir, "$project.pulls.json").delete()
    val dir = File(outDir, "$project.pulls").takeIf { it.isDirectory }?.deleteRecursively() ?: false
    return idx || dir
}

/**
 * Write the per-PR impact SHARDS into `<outDir>/<project>.impact/<number>.json` (the
 * heavy detail lazy-loaded by the UI), pruning shards for PRs no longer in this run.
 * The lean `<project>.impact.json` index is written separately by the caller.
 */
private fun writeImpactShards(outDir: File, project: String, shards: Map<Int, Map<String, Any?>>) {
    val dir = File(outDir, "$project.impact").also { it.mkdirs() }
    val keep = HashSet<String>()
    for ((number, shard) in shards) {
        File(dir, "$number.json").writeText(JsonOutput.writeValue(shard)); keep.add("$number.json")
    }
    dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { if (it.name !in keep) it.delete() }
    if (dir.listFiles()?.isEmpty() == true) dir.delete()
}

/** Remove a project's impact artifacts: the `<project>.impact.json` index and `<project>.impact/` shard dir. */
private fun removeImpactFiles(outDir: File, project: String): Boolean {
    val idx = File(outDir, "$project.impact.json").delete()
    val dir = File(outDir, "$project.impact").takeIf { it.isDirectory }?.deleteRecursively() ?: false
    return idx || dir
}

private fun cmdRefresh(opts: Opts) {
    val repo = File(opts["--repo"] ?: DEFAULT_REPO)
    val outDir = File(opts["--out-dir"] ?: "./json").also { it.mkdirs() }
    val profile = opts["--profile"]
    val props = loadProps(opts["--props"])
    val includeOther = opts.has("--include-other")
    val publicOnly = opts.has("--public-only")
    val projects = repo.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        ?.sortedBy { it.name } ?: emptyList()
    if (projects.isEmpty()) { System.err.println("refresh: no project dirs under ${repo.path}"); exitProcess(2) }

    // 1) pull each project's currently-checked-out branch (fast-forward only)
    if (!opts.has("--no-pull")) {
        for (p in projects) {
            if (!GitLog.isRepoRoot(p)) { System.err.println("  - ${p.name}: (not a standalone git repo, skip pull)"); continue }
            val branch = GitLog.currentBranch(p)
            val (ok, msg) = GitLog.pull(p)
            val tail = msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(80) ?: ""
            System.err.println("  - ${p.name}@$branch: ${if (ok) "pulled" else "PULL FAILED"} ($tail)")
        }
    }

    // 2) analyze each project once; reuse the IR for both graph and OpenAPI
    val builtGraphs = ArrayList<CallGraph>()
    val allFiles = ArrayList<IrFile>()
    val liveBases = LinkedHashSet<String>()
    for (p in projects) {
        val files = AnalysisSession().analyze(repo.path, p.name, profile, props)
        if (files.isEmpty()) continue // no kt/java sources (e.g. a frontend dir) — no ghost output
        liveBases.add(p.name)
        allFiles.addAll(files)
        val snippets = File(p, "build/generated-snippets").takeIf { it.isDirectory }?.path
        val graph = GraphBuilder(files, includeOther, RestDocs.load(snippets), publicOnly = publicOnly).build()
        builtGraphs.add(graph)
        File(outDir, "${p.name}.json").writeText(JsonOutput.write(graph, linkedMapOf(
            "command" to "analyze", "project" to p.name, "nodes" to graph.nodes.size, "edges" to graph.edges.size)))
        val oapi = OpenApi.build(files, title = p.name, enrich = RestDocs.loadApi(snippets))
        File(outDir, "${p.name}.openapi.json").writeText(JsonOutput.writeValue(oapi))
        @Suppress("UNCHECKED_CAST")
        val pPaths = (oapi["paths"] as? Map<String, *>)?.size ?: 0
        System.err.println("  + ${p.name}.json (${graph.nodes.size} nodes, ${graph.edges.size} edges)")
        System.err.println("  + ${p.name}.openapi.json ($pPaths paths)")
    }

    // 3) prune ghost BACKEND outputs for projects no longer present/sourced.
    //    Leave frontend artifacts (ts-analyzer *.join.json/*.screens.json and
    //    frontend graphs) untouched so a SHARED output dir is safe to refresh.
    outDir.listFiles { f ->
        f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json" &&
            !f.name.endsWith(".join.json") && !f.name.endsWith(".screens.json")
    }?.forEach { f ->
        val isBackendSibling = f.name.endsWith(".openapi.json") || f.name.endsWith(".impact.json") || f.name.endsWith(".pulls.json")
        val base = f.name.removeSuffix(".impact.json").removeSuffix(".openapi.json").removeSuffix(".pulls.json").removeSuffix(".json")
        if (base in liveBases) return@forEach
        if (!isBackendSibling && Manifest.isFrontendGraph(f)) return@forEach  // a frontend graph, not a ghost
        f.delete(); System.err.println("  ~ pruned ghost ${f.name}")
    }
    // also prune ghost shard directories (`<project>.pulls/`, `<project>.impact/`) for absent projects
    outDir.listFiles { f -> f.isDirectory && (f.name.endsWith(".pulls") || f.name.endsWith(".impact")) }?.forEach { d ->
        val b = d.name.removeSuffix(".pulls").removeSuffix(".impact")
        if (b !in liveBases && d.deleteRecursively())
            System.err.println("  ~ pruned ghost ${d.name}/")
    }

    // 4) combine (in-memory) + repo-wide OpenAPI.
    //    Gateways are AUTO-DISCOVERED from each project's resource YAMLs
    //    (spring.cloud.gateway.routes), so GATEWAY nodes + `gateway` edges land in
    //    _combined.json without a manual --gateway-routes. An explicit
    //    --gateway-routes still works as an extra/override gateway.
    val gateways = ArrayList<Gateway.Source>()
    val seenGw = HashSet<String>()
    opts["--gateway-routes"]?.let { path ->
        val name = opts["--gateway-name"] ?: File(path).nameWithoutExtension
        val r = Gateway.load(path, name)
        if (r.isNotEmpty()) { gateways.add(Gateway.Source(name, r)); seenGw.add(name)
            System.err.println("  gateway: ${r.size} routes from $path (as '$name')") }
    }
    for (p in projects) {
        if (p.name in seenGw) continue
        val r = Gateway.discover(p)
        if (r.isNotEmpty()) { gateways.add(Gateway.Source(p.name, r)); seenGw.add(p.name)
            System.err.println("  gateway: discovered ${r.size} routes in ${p.name}") }
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

    // 5) per-project PR analysis — mine each project's merged PRs against the
    // COMBINED graph (so cross-service breaking-change detection sees external
    // callers): impact (changed/deleted nodes + breaking endpoints) plus the
    // lazy-loaded per-PR file diffs. Each project logs its step and a ✓/✗/· status
    // (success / failure / skipped) so a no-op run is self-explaining; a single
    // project's failure is isolated (caught) and never aborts the whole refresh.
    var impactCount = 0
    if (opts.has("--no-impact")) {
        System.err.println("[5/7] PR analysis: skipped (--no-impact)")
    } else {
        val impactMax = opts["--impact-max"]?.toIntOrNull() ?: 10
        val candidates = projects.count { it.name in liveBases }
        System.err.println("[5/7] PR analysis (impact + pull-files): $candidates/${projects.size} project(s) with backend sources, max=$impactMax")
        var skipped = 0; var failed = 0
        for (p in projects) {
            if (p.name !in liveBases) continue        // non-backend dir (e.g. frontend) — already reported in step 2
            val isRoot = GitLog.isRepoRoot(p)
            val branch = if (isRoot) GitLog.resolveBranch(p, opts["--branch"]) else null
            when {
                !isRoot -> { System.err.println("  · ${p.name}: skip — not a standalone git repo"); skipped++ }
                branch == null -> { System.err.println("  · ${p.name}: skip — no default branch (try --branch)"); skipped++ }
                else -> {
                    System.err.println("  → ${p.name}@$branch: fetching merged PRs via gh (max $impactMax)…")
                    val pulls = GitHub.mergedPulls(p, branch, impactMax)
                    val impactFile = File(outDir, "${p.name}.impact.json")
                    when {
                        pulls == null -> {             // no PR source: keep any prior impact, don't overwrite/delete
                            System.err.println("  ✗ ${p.name}: no PR source for base $branch (no git PR markers + gh unavailable) — keeping existing impact")
                            failed++
                        }
                        pulls.isEmpty() -> {           // gh ran, no merged PRs: drop stale impact + pull-files so they aren't served
                            val rm = removeImpactFiles(outDir, p.name) or removePullFiles(outDir, p.name)
                            System.err.println("  · ${p.name}: no merged PRs for base $branch — skip" + if (rm) " (removed stale artifacts)" else "")
                            skipped++
                        }
                        else -> {
                            val ok = try {
                                System.err.println("  → ${p.name}: ${pulls.size} PRs — analyzing impact…")
                                val result = Impact.analyze(p, branch, pulls, combined)
                                impactFile.writeText(JsonOutput.writeValue(result.index))
                                writeImpactShards(outDir, p.name, result.shards)
                                impactCount++
                                System.err.println("  ✓ ${p.name}.impact.json + ${p.name}.impact/ (${pulls.size} PRs, ${result.index["changedNodeCount"]} changed nodes, ${result.index["impactedEndpointCount"]} impacted endpoints, ${result.index["breakingDeletionCount"]} breaking)")
                                true
                            } catch (e: Exception) {
                                System.err.println("  ✗ ${p.name}: impact analysis FAILED — ${e.message}")
                                failed++; false
                            }
                            // pull-files is best-effort: a failure here does NOT fail the project (impact already succeeded)
                            if (ok && !opts.has("--no-pull-files")) {
                                try {
                                    System.err.println("  → ${p.name}: collecting per-PR file diffs (status+patch) via gh…")
                                    val (fetched, reused) = writePullFiles(outDir, p.name, p, branch, pulls, opts.has("--refetch-pull-files"))
                                    System.err.println("  ✓ ${p.name}.pulls.json (${fetched + reused} PRs: $fetched fetched, $reused reused) → ${p.name}.pulls/")
                                } catch (e: Exception) {
                                    System.err.println("  ⚠ ${p.name}: pull-files collection failed — ${e.message} (impact kept)")
                                }
                            }
                        }
                    }
                }
            }
        }
        System.err.println("[5/7] PR analysis done: $impactCount analyzed, $skipped skipped, $failed failed")
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

    System.err.println("refresh done: ${liveBases.size} projects, combined ${combined.nodes.size} nodes / $s2s s2s, openapi $paths paths, impact $impactCount, manifest $manifestCount projects -> ${outDir.path}")
}

private fun cmdOpenApi(opts: Opts) {
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
    // combine works on prebuilt graph JSONs (no source tree) → no auto-discovery here;
    // pass an explicit --gateway-routes to add a gateway. refresh auto-discovers.
    val gateways = ArrayList<Gateway.Source>()
    opts["--gateway-routes"]?.let { path ->
        val name = opts["--gateway-name"] ?: File(path).nameWithoutExtension
        val r = Gateway.load(path, name)
        System.err.println("gateway: loaded ${r.size} routes from $path (as '$name')")
        if (r.isNotEmpty()) gateways.add(Gateway.Source(name, r))
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

    // Lightweight manifest (additive). Target the output directory: parent of
    // --out if given, else the scanned --dir, else the cwd.
    val manifestDir = opts["--out"]?.let { File(it).absoluteFile.parentFile }
        ?: opts["--dir"]?.let { File(it) }
        ?: File(".")
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
        // (*.openapi.json, *.impact.json). A defensive non-graph check in cmdCombine
        // also drops anything that slips through.
        File(dir).listFiles { f ->
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") && f.name != "manifest.json" &&
                !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json") && !f.name.endsWith(".pulls.json")
        }?.sortedBy { it.name }?.forEach { out.add(it.path) }
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

          --- single-analysis tools (debugging / ad-hoc) ---
          analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--public-only] [--profile p] [--props kv.txt] [--restdocs dir]
          openapi --repo <dir> [--project P] [--out f.json] [--restdocs dir] [--title T] [--api-version V] [--profile p] [--props kv.txt]
          impact  --git <repo> (--graph g.json | --repo <dir> --project P) [--branch b] [--max N (default 10)] [--out f.json] [--pull-files <dir>] [--refetch-pull-files]
                  # change-impact per merged PR (git-first: `git log --first-parent`; falls back to `gh` only if git finds no PR markers)
                  # --pull-files <dir>: also write a <project>.pulls.json index + <project>.pulls/<number>.json shards (lazy-load, incremental)
          combine --graphs a.json,b.json,... | --dir <dir of *.json> [--gateway-routes routes.yml] [--gateway-name N] [--out f.json]
          sync    --out-dir <analyzer out> --sync-dir <web data dir> [--frontend-dir d1,d2]
                  # assemble the web data dir from existing artifacts (refresh's step 7, standalone) — run AFTER the frontend analyzer
          search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
          stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
        """.trimIndent()
    )
}
