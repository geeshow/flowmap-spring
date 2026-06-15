package com.flowmap.callgraph

import java.io.File

/**
 * Change-impact analysis (git-side facts only). For each merged PR it reports the
 * methods it changed — joined to the current call graph by node id (`<fqcn>#<method>`)
 * — and the endpoints it deleted (with breaking-change detection). Both Kotlin and
 * Java sources are mapped.
 *
 * Impact PROPAGATION (which endpoints/services a change reaches) is intentionally NOT
 * precomputed here: the web UI walks the in-memory call graph live (BFS over the
 * shared graph) from these changed nodes, so the analyzer stays a thin, graph-free
 * emitter of what a PR actually touched. This avoids shipping a redundant traversal
 * (`subgraph`/`endpointImpact`) and a `depth` that the UI ignored anyway.
 *
 * Join model ("recent range → current graph"): a method is "changed" in a PR when the
 * merge commit's changed line ranges fall inside the method's range *at that revision*
 * ([PsiSourceParser] on the blob). Methods absent from the current graph report
 * `inGraph:false`. Each changed method carries its `visibility` and a `public` flag —
 * public methods are the API surface whose change can affect other code.
 *
 * Deletion model: for each changed file, the parent blob is parsed too; methods present
 * in the parent but gone after the merge are "deleted". A deleted controller endpoint
 * (verb+path from PSI) whose (verb, normPath) is still targeted by a current EXTERNAL
 * caller node (best with `_combined.json`) is a BREAKING change; the callers are listed.
 *
 * Outputs: `pulls[]` (changed + deleted per PR), `deletedEndpoints[]` (+ breaking).
 */
object Impact {

    /**
     * Analyze impact per merged pull request: each PR is reduced to its merge
     * commit, whose first-parent diff is the PR's net change set. The deep-link is
     * `${repoUrl}/pull/${number}`, built by the consumer from `repoUrl` + the PR
     * number on each entry. PRs without a merge commit available locally are skipped.
     */
    fun analyze(repo: File, base: String, pulls: List<GitHub.Pr>, graph: CallGraph): Map<String, Any?> {
        // Repo web base (e.g. https://github.com/owner/repo) — deep-link each PR as
        // `${repoUrl}/pull/${number}` from the number already on each entry.
        val webBase = GitLog.webBaseUrl(repo)
        val nodeById = graph.nodes.associateBy { it.id }
        val breaking = BreakingIndex(graph)
        val parser = PsiSourceParser()
        val perPull = ArrayList<Map<String, Any?>>()
        val allChangedInGraph = LinkedHashSet<String>()
        val allChangedPublic = LinkedHashSet<String>()
        val deletedAgg = LinkedHashMap<String, DeletedEndpoint>()   // node id -> aggregate

        try {
            for (pr in pulls) {
                val sha = pr.mergeCommit ?: continue
                val parent = GitLog.firstParent(repo, sha)
                val changes = GitLog.changesIn(repo, sha)
                // id -> the changed method's parsed range (carries visibility); first-seen wins.
                val changedFns = LinkedHashMap<String, PsiSourceParser.FnRange>()
                val deletedIds = LinkedHashSet<String>()
                val deletedEps = ArrayList<Map<String, Any?>>()

                for (ch in changes) {
                    // Kotlin AND Java sources are mapped to method node ids; other files are skipped.
                    if (!ch.path.endsWith(".kt") && !ch.path.endsWith(".java")) continue
                    val newFns = if (ch.changeType == "DELETE") emptyList()
                    else GitLog.fileAt(repo, sha, ch.path)?.let { parser.functions(ch.path, it) } ?: emptyList()

                    // changed methods: hunks ∩ new-revision method ranges
                    if (ch.changeType != "DELETE" && ch.newRanges.isNotEmpty()) {
                        for (fn in newFns) if (ch.newRanges.any { it.first <= fn.endLine && fn.startLine <= it.last }) {
                            changedFns.putIfAbsent(fn.nodeId, fn)
                        }
                    }

                    // deleted methods: present in the PR's base, gone after the merge
                    if (parent != null) {
                        val oldPath = ch.oldPath ?: ch.path
                        val oldFns = GitLog.fileAt(repo, parent, oldPath)?.let { parser.functions(oldPath, it) } ?: emptyList()
                        val newIds = newFns.mapTo(HashSet()) { it.nodeId }
                        for (fn in oldFns) if (fn.nodeId !in newIds) {
                            deletedIds.add(fn.nodeId)
                            if (fn.isEndpoint) {
                                deletedEps.add(linkedMapOf("id" to fn.nodeId, "httpMethod" to fn.httpMethod, "endpoint" to fn.endpoint))
                                deletedAgg.getOrPut(fn.nodeId) { DeletedEndpoint(fn.nodeId, fn.httpMethod, fn.endpoint) }
                                    .pulls.add(pr.number)
                            }
                        }
                    }
                }

                allChangedInGraph.addAll(changedFns.keys.filter { it in nodeById })

                // A changed method's visibility: prefer the analyzed graph node (authoritative),
                // else the parser's reading of the changed revision. "public" methods are the
                // module's API surface — the ones whose change can affect other code.
                fun visOf(fn: PsiSourceParser.FnRange) = nodeById[fn.nodeId]?.visibility ?: fn.visibility
                val changedNodes = changedFns.values.map { fn ->
                    val vis = visOf(fn)
                    linkedMapOf<String, Any?>(
                        "id" to fn.nodeId, "inGraph" to (fn.nodeId in nodeById),
                        "visibility" to vis, "public" to (vis == "public"),
                    )
                }
                val changedPublic = changedFns.values.filter { visOf(it) == "public" }.map { it.nodeId }
                allChangedPublic.addAll(changedPublic)

                perPull.add(linkedMapOf(
                    "number" to pr.number, "title" to pr.title, "author" to pr.author,
                    "mergedAt" to pr.mergedAt, "mergeCommit" to sha,
                    "changedFiles" to changes.map { it.path },
                    "changedNodes" to changedNodes,
                    "changedPublicMethods" to changedPublic,
                    "deletedNodes" to deletedIds.toList(),
                    "deletedEndpoints" to deletedEps,
                ))
            }
        } finally {
            parser.close()
        }

        val deletedEndpoints = deletedAgg.values.map { d ->
            // path still served by another current controller? -> moved/renamed, not truly deleted
            val served = breaking.servedNow(d.httpMethod, d.endpoint)
            val callers = if (served) emptyList() else breaking.callersOf(d.httpMethod, d.endpoint)
            linkedMapOf<String, Any?>(
                "id" to d.id, "httpMethod" to d.httpMethod, "endpoint" to d.endpoint,
                "removedInPulls" to d.pulls.toList(),
                "pathStillServed" to served,                 // true = endpoint moved to another controller
                "breaking" to callers.isNotEmpty(),
                "stillCalledBy" to callers,
            )
        }.sortedWith(compareByDescending<Map<String, Any?>> { it["breaking"] == true }
            .thenBy { it["pathStillServed"] == true })

        return linkedMapOf(
            "base" to base, "repoUrl" to webBase, "pullCount" to pulls.size,
            "changedNodeCount" to allChangedInGraph.size,
            "changedPublicMethodCount" to allChangedPublic.size,
            "deletedEndpointCount" to deletedEndpoints.size,
            "trulyDeletedEndpointCount" to deletedEndpoints.count { it["pathStillServed"] == false },
            "breakingDeletionCount" to deletedEndpoints.count { it["breaking"] == true },
            "pulls" to perPull,
            "deletedEndpoints" to deletedEndpoints,
        )
    }

    private class DeletedEndpoint(val id: String, val httpMethod: String?, val endpoint: String?) {
        val pulls = LinkedHashSet<Int>()
    }

    /** Index of current EXTERNAL caller nodes by (verb, normPath) to flag breaking deletions. */
    private class BreakingIndex(graph: CallGraph) {
        private val nodeById = graph.nodes.associateBy { it.id }
        // external target id -> caller nodes (sources of edges into it)
        private val callersByExt = HashMap<String, MutableList<MethodNode>>()
        private val externals: List<MethodNode>
        private val controllers: List<Pair<String?, String>>   // (verb, normPath) of current endpoints

        init {
            for (e in graph.edges) {
                val tgt = nodeById[e.target]
                if (tgt?.layer == Layer.EXTERNAL) nodeById[e.source]?.let { callersByExt.getOrPut(e.target) { mutableListOf() }.add(it) }
            }
            externals = graph.nodes.filter { it.layer == Layer.EXTERNAL && !it.endpoint.isNullOrEmpty() }
            controllers = graph.nodes.filter { it.layer == Layer.CONTROLLER && !it.endpoint.isNullOrEmpty() }
                .map { it.httpMethod to normPath(it.endpoint) }
        }

        /** Is (verb, path) still served by some current controller? (deleted node but path moved) */
        fun servedNow(verb: String?, path: String?): Boolean {
            val np = normPath(path)
            return np.isNotEmpty() && controllers.any { it.second == np && verbOk(it.first, verb) }
        }

        /** Services/nodes whose outbound call still targets (verb, path) of a now-deleted endpoint. */
        fun callersOf(verb: String?, path: String?): List<Map<String, Any?>> {
            val np = normPath(path)
            if (np.isEmpty()) return emptyList()
            val out = LinkedHashMap<String, Map<String, Any?>>()
            for (ext in externals) {
                if (normPath(ext.endpoint) != np || !verbOk(ext.httpMethod, verb)) continue
                for (caller in callersByExt[ext.id].orEmpty()) {
                    out.putIfAbsent(caller.id, linkedMapOf("caller" to caller.id, "service" to (caller.project ?: caller.externalService)))
                }
            }
            return out.values.toList()
        }

        private fun normPath(p: String?): String {
            if (p.isNullOrEmpty()) return ""
            var s = p.substringBefore("?").replace(Regex("\\{[^}]*}"), "{}")
            if (s.length > 1) s = s.trimEnd('/')
            return s
        }

        private fun verbOk(a: String?, b: String?): Boolean =
            a == null || b == null || a == "ANY" || b == "ANY" || a == b
    }
}
