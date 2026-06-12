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
    val graph = GraphBuilder(files, includeOther = opts.has("--include-other")).build()
    val meta = linkedMapOf<String, Any?>(
        "command" to "analyze", "repo" to repo, "project" to opts["--project"],
        "profile" to opts["--profile"], "files" to files.size,
        "nodes" to graph.nodes.size, "edges" to graph.edges.size,
    )
    dump(graph, opts["--out"], meta)
}

private fun cmdCombine(opts: Opts) {
    val paths = collectGraphPaths(opts)
    if (paths.isEmpty()) {
        System.err.println("combine: provide --graphs a.json,b.json,... or --dir <dir of *.json>")
        exitProcess(2)
    }
    val graphs = paths.map { JsonOutput.read(File(it).readText()) }
    val result = CrossRun.combine(graphs)
    val s2s = result.edges.count { it.kind == EdgeKind.S2S }
    val meta = linkedMapOf<String, Any?>(
        "command" to "combine",
        "inputs" to paths.map { File(it).name },
        "projects" to result.nodes.mapNotNull { it.project }.distinct().sorted(),
        "nodes" to result.nodes.size, "edges" to result.edges.size, "s2sEdges" to s2s,
    )
    dump(result, opts["--out"], meta)
    System.err.println("combined ${paths.size} graphs: ${result.nodes.size} nodes, ${result.edges.size} edges, $s2s s2s")
}

/** Graph inputs for `combine`: explicit `--graphs` CSV, and/or every `*.json` under `--dir`. */
private fun collectGraphPaths(opts: Opts): List<String> {
    val out = LinkedHashSet<String>()
    opts["--graphs"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { out.add(it) }
    opts["--dir"]?.let { dir ->
        // skip "_*.json" so a prior combine output (e.g. _combined.json) isn't re-ingested
        File(dir).listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith("_") }
            ?.sortedBy { it.name }?.forEach { out.add(it.path) }
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
          analyze --repo <dir> [--project P] [--out f.json] [--include-other] [--profile p] [--props kv.txt]
          combine --graphs a.json,b.json,... | --dir <dir of *.json> [--out f.json]
          search  --method M [--graph g.json | --repo <dir>] [--direction both|callers|callees] [--depth N] [--out f]
          stats   [--graph g.json | --repo <dir>] [--project P] [--profile p]
        """.trimIndent()
    )
}
