package com.flowmap.callgraph

import java.io.File

/**
 * Change-impact analysis, emitted as a LEAN INDEX + per-PR SHARDS so the index stops
 * growing with every method/file a PR touches.
 *
 * - INDEX (`<project>.impact.json`): only what the commit-impact LIST/overview needs —
 *   per-PR metadata, `changedNodeCount`, `changedFileCount`, and `impactedEndpoints`
 *   (the controller endpoints a PR's NON-PRIVATE changed methods reach, found by a
 *   reverse walk over the call graph). The list shows "변경 N / 영향 N" and the overview
 *   inverts these into the endpoint→commits table — all without loading any shard.
 * - SHARD (`<project>.impact/<number>.json`): the heavy per-PR detail (`changedNodes`
 *   with visibility/inGraph, `changedApiMethods` seeds, `changedFiles`, `deletedNodes`,
 *   `deletedEndpoints`), lazy-loaded by the UI only when a commit is opened (graph view).
 *
 * "Changed API methods" = the non-private blast surface of a PR: every directly-changed
 * method that another class could call (public / protected / internal / package-private),
 * PLUS the nearest non-private caller(s) of each directly-changed PRIVATE method (a private
 * change propagates UP to the public method that calls it). This set is the reverse-walk
 * seed for impacted endpoints — so editing only private helpers still surfaces the public
 * callers and the endpoints they reach.
 *
 * Join model: a method is "changed" in a PR when the merge commit's changed line ranges
 * fall inside the method's range *at that revision* ([PsiSourceParser] on the blob,
 * Kotlin + Java). Methods absent from the current graph report `inGraph:false`.
 *
 * Deletion model: methods present in the parent blob but gone after the merge are
 * "deleted". A deleted controller endpoint still targeted by a current EXTERNAL caller
 * is a BREAKING change; callers are listed. Deletion detection is path-based.
 */
object Impact {

    /** Split output: the lean [index] plus per-PR-number heavy [shards]. */
    class Result(val index: Map<String, Any?>, val shards: Map<Int, Map<String, Any?>>)

    /**
     * @param pathFilter when non-null, a PR's changed files are restricted to those it
     *   accepts (repo-relative path). Used to attribute a monorepo's PRs to one
     *   `wallga.yml` sub-project: a PR that touches none of the sub-project's `build.path`
     *   files is dropped from that sub-project's impact entirely.
     */
    fun analyze(
        repo: File, base: String, pulls: List<GitHub.Pr>, graph: CallGraph,
        pathFilter: ((String) -> Boolean)? = null,
    ): Result {
        val webBase = GitLog.webBaseUrl(repo)
        val nodeById = graph.nodes.associateBy { it.id }
        // callers adjacency: target id -> source ids (for the reverse walk to endpoints)
        val callers = HashMap<String, MutableList<String>>()
        for (e in graph.edges) callers.getOrPut(e.target) { mutableListOf() }.add(e.source)
        val breaking = BreakingIndex(graph)
        val parser = PsiSourceParser()
        val perPullIndex = ArrayList<Map<String, Any?>>()
        val shards = LinkedHashMap<Int, Map<String, Any?>>()
        val allChangedInGraph = LinkedHashSet<String>()
        val allImpacted = LinkedHashSet<String>()
        val deletedAgg = LinkedHashMap<String, DeletedEndpoint>()   // node id -> aggregate

        try {
            for (pr in pulls) {
                val sha = pr.mergeCommit ?: continue
                val parent = GitLog.firstParent(repo, sha)
                val changes = GitLog.changesIn(repo, sha).let { all ->
                    if (pathFilter == null) all
                    else all.filter { pathFilter(it.path) || (it.oldPath?.let(pathFilter) ?: false) }
                }
                if (pathFilter != null && changes.isEmpty()) continue   // PR doesn't touch this sub-project
                // id -> the changed method's parsed range (carries visibility); first-seen wins.
                val changedFns = LinkedHashMap<String, PsiSourceParser.FnRange>()
                val deletedIds = LinkedHashSet<String>()
                val deletedEps = ArrayList<Map<String, Any?>>()

                for (ch in changes) {
                    // Kotlin AND Java sources are mapped to method node ids; other files are skipped.
                    if (!ch.path.endsWith(".kt") && !ch.path.endsWith(".java")) continue
                    val newText = if (ch.changeType == "DELETE") null else GitLog.fileAt(repo, sha, ch.path)
                    val newFns = newText?.let { parser.functions(ch.path, it) } ?: emptyList()

                    // changed methods: CODE hunks ∩ new-revision method ranges. Comment-only / blank
                    // line changes are ignored — a changed line counts only if it carries code
                    // (not inside //, /* */, or /** */ — see PsiSourceParser.codeLines).
                    if (ch.changeType != "DELETE" && ch.newRanges.isNotEmpty() && newText != null) {
                        val codeLines = parser.codeLines(ch.path, newText)
                        val changedCode = ch.newRanges.asSequence()
                            .flatMap { (it.first..it.last).asSequence() }.filter { it in codeLines }.toSet()
                        if (changedCode.isNotEmpty()) {
                            for (fn in newFns) if (changedCode.any { it in fn.startLine..fn.endLine }) {
                                changedFns.putIfAbsent(fn.nodeId, fn)
                            }
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

                // visibility: prefer the analyzed graph node (authoritative), else the parser's
                // reading of the changed revision. API surface = everything but `private`.
                fun visOf(fn: PsiSourceParser.FnRange) = nodeById[fn.nodeId]?.visibility ?: fn.visibility
                val changedNodes = changedFns.values.map { fn ->
                    linkedMapOf<String, Any?>(
                        "id" to fn.nodeId, "inGraph" to (fn.nodeId in nodeById), "visibility" to visOf(fn),
                    )
                }
                // Changed API surface: directly-changed non-private methods, PLUS — for each changed
                // PRIVATE method — the nearest non-private caller(s) reached by walking UP the call
                // graph. A private method can't be called across classes, but changing it changes the
                // behavior of the public method(s) that call it, so those callers are the real changed
                // API surface (and the reverse-walk seeds). Without lifting, a PR editing only private
                // helpers would surface no changed-API method and no impacted endpoint.
                val directApi = changedFns.values.filter { visOf(it) != "private" }.map { it.nodeId }
                val privateChanged = changedFns.values.filter { visOf(it) == "private" }
                    .map { it.nodeId }.filter { it in nodeById }
                val changedApi = (directApi + liftToApi(privateChanged, callers, nodeById)).distinct()
                // impacted endpoints: reverse-walk callers from the in-graph API seeds.
                val seeds = changedApi.filter { it in nodeById }
                val impacted = impactedControllers(seeds, callers, nodeById)
                impacted.forEach { allImpacted.add(it.id) }

                // LEAN index row: list/overview data only (counts + precomputed endpoints).
                perPullIndex.add(linkedMapOf(
                    "number" to pr.number, "title" to pr.title, "author" to pr.author,
                    "mergedAt" to pr.mergedAt, "mergeCommit" to sha,
                    "changedNodeCount" to changedFns.size,
                    "changedFileCount" to changes.size,
                    "impactedEndpoints" to impacted.map { endpointRef(it) },
                ))
                // HEAVY shard: full per-PR detail, lazy-loaded on commit open.
                shards[pr.number] = linkedMapOf(
                    "number" to pr.number, "mergeCommit" to sha,
                    "changedFiles" to changes.map { it.path },
                    "changedNodes" to changedNodes,
                    "changedApiMethods" to changedApi,
                    "deletedNodes" to deletedIds.toList(),
                    "deletedEndpoints" to deletedEps,
                )
            }
        } finally {
            parser.close()
        }

        val deletedEndpoints = deletedAgg.values.map { d ->
            val served = breaking.servedNow(d.httpMethod, d.endpoint)
            val callerz = if (served) emptyList() else breaking.callersOf(d.httpMethod, d.endpoint)
            linkedMapOf<String, Any?>(
                "id" to d.id, "httpMethod" to d.httpMethod, "endpoint" to d.endpoint,
                "removedInPulls" to d.pulls.toList(),
                "pathStillServed" to served,                 // true = endpoint moved to another controller
                "breaking" to callerz.isNotEmpty(),
                "stillCalledBy" to callerz,
            )
        }.sortedWith(compareByDescending<Map<String, Any?>> { it["breaking"] == true }
            .thenBy { it["pathStillServed"] == true })

        val index = linkedMapOf<String, Any?>(
            "base" to base, "repoUrl" to webBase,
            "pullCount" to if (pathFilter != null) perPullIndex.size else pulls.size,
            "changedNodeCount" to allChangedInGraph.size,
            "impactedEndpointCount" to allImpacted.size,
            "deletedEndpointCount" to deletedEndpoints.size,
            "trulyDeletedEndpointCount" to deletedEndpoints.count { it["pathStillServed"] == false },
            "breakingDeletionCount" to deletedEndpoints.count { it["breaking"] == true },
            "pulls" to perPullIndex,
            "deletedEndpoints" to deletedEndpoints,
        )
        return Result(index, shards)
    }

    /**
     * Lift each changed PRIVATE method to the nearest NON-PRIVATE method(s) that (transitively)
     * call it: walk UP the [callers] graph, stopping at the first non-private node on each branch
     * (and continuing through private callers). These public callers are the changed API surface a
     * private-only change actually affects. Cycle-safe via the visited set.
     */
    private fun liftToApi(
        privateSeeds: List<String>, callers: Map<String, List<String>>, nodeById: Map<String, MethodNode>,
    ): List<String> {
        val out = LinkedHashSet<String>()
        val seen = HashSet(privateSeeds)
        val stack = ArrayDeque(privateSeeds)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (src in callers[cur].orEmpty()) {
                if (!seen.add(src)) continue
                val n = nodeById[src] ?: continue
                if (n.visibility != "private") out.add(src) else stack.addLast(src)
            }
        }
        return out.toList()
    }

    /** Reverse-walk callers from [seeds]; collect the CONTROLLER endpoints reached (seed included). */
    private fun impactedControllers(
        seeds: List<String>, callers: Map<String, List<String>>, nodeById: Map<String, MethodNode>,
    ): List<MethodNode> {
        val found = LinkedHashSet<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        for (s in seeds) if (nodeById.containsKey(s) && seen.add(s)) stack.addLast(s)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (nodeById[cur]?.layer == Layer.CONTROLLER) found.add(cur)
            for (src in callers[cur].orEmpty()) if (seen.add(src)) stack.addLast(src)
        }
        return found.mapNotNull { nodeById[it] }
    }

    private fun endpointRef(n: MethodNode): Map<String, Any?> = linkedMapOf(
        "id" to n.id, "httpMethod" to n.httpMethod, "endpoint" to n.endpoint,
        "service" to (n.project ?: n.externalService),
    )

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
