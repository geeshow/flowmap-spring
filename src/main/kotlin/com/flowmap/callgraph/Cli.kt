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
    val boolNames = setOf("--include-other", "--no-pull", "--no-impact")
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
    return GraphBuilder(files, includeOther = opts.has("--include-other")).build() to files.size
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
    val graph = GraphBuilder(files, includeOther = opts.has("--include-other"), descriptions = descriptions).build()
    val meta = linkedMapOf<String, Any?>(
        "command" to "analyze", "repo" to repo, "project" to opts["--project"],
        "profile" to opts["--profile"], "files" to files.size,
        "nodes" to graph.nodes.size, "edges" to graph.edges.size,
    )
    dump(graph, opts["--out"], meta)
}

private fun cmdRefresh(opts: Opts) {
    val repo = File(opts["--repo"] ?: DEFAULT_REPO)
    val outDir = File(opts["--out-dir"] ?: "./json").also { it.mkdirs() }
    val profile = opts["--profile"]
    val props = loadProps(opts["--props"])
    val includeOther = opts.has("--include-other")
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
        val graph = GraphBuilder(files, includeOther, RestDocs.load(snippets)).build()
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
        val isBackendSibling = f.name.endsWith(".openapi.json") || f.name.endsWith(".impact.json")
        val base = f.name.removeSuffix(".impact.json").removeSuffix(".openapi.json").removeSuffix(".json")
        if (base in liveBases) return@forEach
        if (!isBackendSibling && Manifest.isFrontendGraph(f)) return@forEach  // a frontend graph, not a ghost
        f.delete(); System.err.println("  ~ pruned ghost ${f.name}")
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

    // 5) per-project commit/impact — mine each project's git history against the
    // COMBINED graph (so cross-service breaking-change detection sees external
    // callers). Projects without a git work tree are skipped.
    var impactCount = 0
    if (!opts.has("--no-impact")) {
        val impactMax = opts["--impact-max"]?.toIntOrNull() ?: 50
        val impactDepth = opts["--impact-depth"]?.toIntOrNull() ?: 3
        for (p in projects) {
            if (p.name !in liveBases) continue
            if (!GitLog.isRepoRoot(p)) { System.err.println("  - ${p.name}: (not a standalone git repo, skip impact)"); continue }
            val branch = GitLog.resolveBranch(p, opts["--branch"])
            if (branch == null) { System.err.println("  - ${p.name}: (no default branch, skip impact)"); continue }
            val commits = GitLog.commits(p, branch, impactMax, null)
            val result = Impact.analyze(p, branch, commits, combined, impactDepth)
            File(outDir, "${p.name}.impact.json").writeText(JsonOutput.writeValue(result))
            impactCount++
            val breaking = result["breakingDeletionCount"]
            System.err.println("  + ${p.name}.impact.json (@$branch: ${commits.size} commits, ${result["changedNodeCount"]} changed nodes, $breaking breaking deletions)")
        }
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
    val range = opts["--range"]
    val max = opts["--max"]?.toIntOrNull() ?: 50
    val depth = opts["--depth"]?.toIntOrNull() ?: 3
    val commits = GitLog.commits(git, branch, if (range == null) max else null, range)
    System.err.println("impact: ${git.name}@$branch, ${commits.size} commits, depth $depth")
    val result = Impact.analyze(git, branch, commits, graph, depth)
    val text = JsonOutput.writeValue(result)
    val out = opts["--out"]
    if (out != null) {
        File(out).writeText(text)
        @Suppress("UNCHECKED_CAST")
        val eps = (result["endpointImpact"] as? List<*>)?.size ?: 0
        System.err.println("wrote $out: ${commits.size} commits, ${result["changedNodeCount"]} changed nodes, $eps impacted endpoints")
    } else {
        println(text)
    }
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
                !f.name.endsWith(".openapi.json") && !f.name.endsWith(".impact.json")
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
            refresh [--repo <dir>] [--out-dir ./json] [--no-pull] [--no-impact]
                    [--impact-max N] [--impact-depth N] [--branch b]
                    [--include-other] [--profile p] [--props kv.txt] [--title T]
                    [--gateway-routes routes.yml] [--gateway-name N]   # explicit gateway (else auto-discovered)
                    [--sync-dir <web data dir>] [--frontend-dir d1,d2] # copy per-project artifacts + manifest.json there

          --- single-analysis tools (debugging / ad-hoc) ---
          analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--profile p] [--props kv.txt] [--restdocs dir]
          openapi --repo <dir> [--project P] [--out f.json] [--restdocs dir] [--title T] [--api-version V] [--profile p] [--props kv.txt]
          impact  --git <repo> (--graph g.json | --repo <dir> --project P) [--branch b] [--max N | --range A..B] [--depth N] [--out f.json]
          combine --graphs a.json,b.json,... | --dir <dir of *.json> [--gateway-routes routes.yml] [--gateway-name N] [--out f.json]
          search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
          stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
        """.trimIndent()
    )
}
