package com.flowmap.callgraph

import java.io.File

/**
 * Change-impact analysis: map each commit's changed lines to call-graph node ids and
 * propagate via reverse BFS (callers) to find affected endpoints/services. Also detects
 * DELETED endpoints (present in a commit's parent but not in the commit) and flags those
 * still called by other services as breaking changes.
 *
 * Join model ("recent range → current graph"): a method is "changed" in a commit when
 * the commit's changed line ranges fall inside the method's range *at that revision*
 * ([PsiSourceParser] on the blob). Its node id (`<fqcn>#<method>`) is joined to the
 * CURRENT analyzed graph; methods absent from it report `inGraph:false`.
 *
 * Deletion model: for each changed file, the parent blob is parsed too; methods present
 * in the parent but gone in the commit are "deleted". A deleted method that was a
 * controller endpoint (verb+path from PSI) is a deleted endpoint; if a current EXTERNAL
 * caller node (best with `_combined.json`) still targets that (verb, normPath), it is a
 * BREAKING change and the calling services are listed.
 *
 * Analysis unit is the merged PULL REQUEST (via [GitHub]), not the raw commit: each
 * PR is reduced to its merge commit, whose first-parent diff is the PR's net change
 * set, fed into the line→node join above.
 *
 * Outputs: `pulls[]` (changed + deleted per PR), `subgraph` (changed + callers),
 * `endpointImpact[]` (endpoint → affecting PR numbers), `deletedEndpoints[]` (+ breaking).
 */
object Impact {

    /**
     * Analyze impact per merged pull request: each PR is reduced to its merge
     * commit, whose first-parent diff is the PR's net change set. The deep-link is
     * `${repoUrl}/pull/${number}`, built by the consumer from `repoUrl` + the PR
     * number on each entry. PRs without a merge commit available locally are skipped.
     */
    fun analyze(repo: File, base: String, pulls: List<GitHub.Pr>, graph: CallGraph, depth: Int): Map<String, Any?> {
        // Repo web base (e.g. https://github.com/owner/repo) — deep-link each PR as
        // `${repoUrl}/pull/${number}` from the number already on each entry.
        val webBase = GitLog.webBaseUrl(repo)
        val nodeById = graph.nodes.associateBy { it.id }
        val breaking = BreakingIndex(graph)
        val parser = PsiSourceParser()
        val perPull = ArrayList<Map<String, Any?>>()
        val allChangedInGraph = LinkedHashSet<String>()
        val endpointToPulls = LinkedHashMap<String, LinkedHashSet<Int>>()
        val deletedAgg = LinkedHashMap<String, DeletedEndpoint>()   // node id -> aggregate

        try {
            for (pr in pulls) {
                val sha = pr.mergeCommit ?: continue
                val parent = GitLog.firstParent(repo, sha)
                val changes = GitLog.changesIn(repo, sha)
                val changedIds = LinkedHashSet<String>()
                val deletedIds = LinkedHashSet<String>()
                val deletedEps = ArrayList<Map<String, Any?>>()

                for (ch in changes) {
                    if (!ch.path.endsWith(".kt")) continue
                    val newFns = if (ch.changeType == "DELETE") emptyList()
                    else GitLog.fileAt(repo, sha, ch.path)?.let { parser.functions(ch.path, it) } ?: emptyList()

                    // changed methods: hunks ∩ new-revision method ranges
                    if (ch.changeType != "DELETE" && ch.newRanges.isNotEmpty()) {
                        for (fn in newFns) if (ch.newRanges.any { it.first <= fn.endLine && fn.startLine <= it.last }) changedIds.add(fn.nodeId)
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

                val inGraph = changedIds.filter { it in nodeById }
                allChangedInGraph.addAll(inGraph)
                val sub = Bfs.bfs(graph, inGraph, Bfs.Direction.CALLERS, depth)
                val endpoints = sub.nodes.filter { it.endpoint != null || it.httpMethod != null }
                val services = sub.nodes.mapNotNull { it.project ?: it.externalService }.distinct().sorted()
                for (ep in endpoints) endpointToPulls.getOrPut(ep.id) { LinkedHashSet() }.add(pr.number)

                perPull.add(linkedMapOf(
                    "number" to pr.number, "title" to pr.title, "author" to pr.author,
                    "mergedAt" to pr.mergedAt, "mergeCommit" to sha,
                    "changedFiles" to changes.map { it.path },
                    "changedNodes" to changedIds.map { linkedMapOf("id" to it, "inGraph" to (it in nodeById)) },
                    "deletedNodes" to deletedIds.toList(),
                    "deletedEndpoints" to deletedEps,
                    "impactedEndpoints" to endpoints.map { endpointRef(it) },
                    "impactedServices" to services,
                ))
            }
        } finally {
            parser.close()
        }

        val subgraph = Bfs.bfs(graph, allChangedInGraph.toList(), Bfs.Direction.CALLERS, depth)
        val endpointImpact = endpointToPulls.entries.map { (id, nums) ->
            val n = nodeById[id]
            linkedMapOf<String, Any?>(
                "id" to id, "httpMethod" to n?.httpMethod, "endpoint" to n?.endpoint,
                "service" to (n?.project ?: n?.externalService), "description" to n?.description,
                "pulls" to nums.toList(),
            )
        }.sortedByDescending { (it["pulls"] as List<*>).size }

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
            "base" to base, "repoUrl" to webBase, "pullCount" to pulls.size, "depth" to depth,
            "changedNodeCount" to allChangedInGraph.size,
            "deletedEndpointCount" to deletedEndpoints.size,
            "trulyDeletedEndpointCount" to deletedEndpoints.count { it["pathStillServed"] == false },
            "breakingDeletionCount" to deletedEndpoints.count { it["breaking"] == true },
            "pulls" to perPull,
            "subgraph" to subgraph.toNodeLink(linkedMapOf("kind" to "impact-subgraph")),
            "endpointImpact" to endpointImpact,
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

    private fun endpointRef(n: MethodNode): Map<String, Any?> = linkedMapOf(
        "id" to n.id, "httpMethod" to n.httpMethod, "endpoint" to n.endpoint,
        "service" to (n.project ?: n.externalService), "description" to n.description,
    )
}
