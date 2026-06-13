package com.flowmap.callgraph

import java.io.File
import kotlin.system.exitProcess

/**
 * CLI mirroring the Python tool:
 *   analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--profile p] [--props f]
 *   search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
 *   stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) { usage(); exitProcess(2) }
    val cmd = args[0]
    val opts = parseOpts(args.drop(1))
    when (cmd) {
        "analyze" -> cmdAnalyze(opts)
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
    val boolNames = setOf("--include-other")
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

private fun graphFromOpts(opts: Opts): Pair<CallGraph, Int> {
    opts["--graph"]?.let { g ->
        return JsonOutput.read(File(g).readText()) to -1
    }
    val repo = opts["--repo"] ?: "../.repo"
    val files = AnalysisSession().analyze(
        repoRoot = repo,
        projectFilter = opts["--project"],
        profile = opts["--profile"],
        extraProps = loadProps(opts["--props"]),
    )
    return GraphBuilder(files, includeOther = opts.has("--include-other")).build() to files.size
}

private fun cmdAnalyze(opts: Opts) {
    val repo = opts["--repo"] ?: "../.repo"
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

private fun cmdOpenApi(opts: Opts) {
    val repo = opts["--repo"] ?: "../.repo"
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
        ?: opts["--project"]?.let { File(opts["--repo"] ?: "../.repo", it) }
        ?: File(opts["--repo"] ?: "../.repo")
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
    val result = CrossRun.combine(graphs)
    val s2s = result.edges.count { it.kind == EdgeKind.S2S }
    val meta = linkedMapOf<String, Any?>(
        "command" to "combine",
        "inputs" to usable.map { File(it).name },
        "projects" to result.nodes.mapNotNull { it.project }.distinct().sorted(),
        "nodes" to result.nodes.size, "edges" to result.edges.size, "s2sEdges" to s2s,
    )
    dump(result, opts["--out"], meta)
    System.err.println("combined ${usable.size} graphs: ${result.nodes.size} nodes, ${result.edges.size} edges, $s2s s2s")
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
            f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") &&
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
        callgraph (Kotlin Analysis API)
          analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--profile p] [--props kv.txt] [--restdocs dir]
          openapi --repo <dir> [--project P] [--out f.json] [--restdocs dir] [--title T] [--api-version V] [--profile p] [--props kv.txt]
          impact  --git <repo> (--graph g.json | --repo <dir> --project P) [--branch b] [--max N | --range A..B] [--depth N] [--out f.json]
          combine --graphs a.json,b.json,... | --dir <dir of *.json> [--out f.json]
          search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
          stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
        """.trimIndent()
    )
}
