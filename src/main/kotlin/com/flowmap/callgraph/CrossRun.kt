package com.flowmap.callgraph

/**
 * Cross-run combine: merge several per-project graphs into one MSA graph and
 * resolve server-to-server (S2S) edges.
 *
 * Each per-project `analyze` emits EXTERNAL nodes for outbound HTTP calls
 * (`@FeignClient`/`@HttpExchange`/imperative clients) carrying the target
 * verb + path. When such a call's (verb, path) matches a CONTROLLER endpoint
 * exposed by *another* analyzed project, the call is a service-to-service hop:
 * the edge is retargeted onto the provider's controller node and tagged `s2s`,
 * and the now-redundant external stub is dropped. Calls with no matching
 * provider (third-party APIs) stay EXTERNAL.
 *
 * Pure — no Analysis API. Mirrors the Python tool's registry/build S2S linking
 * (registry.norm_path + build._match_endpoint), but operates on already-built
 * per-project graphs instead of a stateful registry.
 */
object CrossRun {

    fun combine(graphs: List<CallGraph>, gateways: List<Gateway.Source> = emptyList()): CallGraph {
        // 1) union nodes (first-seen wins) and edges (dedup by key)
        val nodes = LinkedHashMap<String, MethodNode>()
        for (g in graphs) for (n in g.nodes) nodes.putIfAbsent(n.id, n)
        val srcEdges = LinkedHashMap<List<Any?>, CallEdge>()
        for (g in graphs) for (e in g.edges) srcEdges.putIfAbsent(e.key(), e)

        // 2) provider index: CONTROLLER endpoints exposed across all projects
        val providers = nodes.values.filter { it.layer == Layer.CONTROLLER && !it.endpoint.isNullOrEmpty() }

        // 3) retarget edges whose EXTERNAL target matches a provider endpoint
        val externals = nodes.values.filter { it.layer == Layer.EXTERNAL }.associateBy { it.id }
        val newEdges = LinkedHashMap<List<Any?>, CallEdge>()
        for (e in srcEdges.values) {
            val ext = externals[e.target]
            val caller = nodes[e.source]
            val provider = ext?.let {
                if (it.endpoint.isNullOrEmpty()) null
                else matchProvider(providers, it.httpMethod, it.endpoint, hintTokens(it),
                    it.s2sService, caller?.project, caller?.module)
            }
            val ne = if (provider != null) e.copy(target = provider.id, kind = EdgeKind.S2S) else e
            newEdges.putIfAbsent(ne.key(), ne)
        }

        // 4) gateway pass: a GATEWAY node per route + `gateway` edges to the routed
        //    backend service's endpoints (frontend prefix vs server path made visible).
        wireGateways(gateways, providers, nodes, newEdges)

        // 5) drop external stubs that no surviving edge references anymore
        val referenced = HashSet<String>()
        for (e in newEdges.values) { referenced.add(e.source); referenced.add(e.target) }
        val finalNodes = nodes.values.filter { it.layer != Layer.EXTERNAL || it.id in referenced }
        return CallGraph(finalNodes, newEdges.values.toList())
    }

    // ---- gateway routing ----

    private fun wireGateways(
        gateways: List<Gateway.Source>, providers: List<MethodNode>,
        nodes: LinkedHashMap<String, MethodNode>, edges: LinkedHashMap<List<Any?>, CallEdge>,
    ) {
        if (gateways.isEmpty()) return
        val byProject = providers.groupBy { it.project }
        for (gw in gateways) for (route in gw.routes) {
            val gwId = "gateway:${gw.name}#${route.routeId}"
            nodes.putIfAbsent(gwId, gatewayNode(gwId, gw.name, route))
            val svc = matchService(route.targetService, byProject.keys) ?: continue
            val bp = route.backendPrefix
            for (ep in byProject[svc].orEmpty()) {
                if (bp.isNotEmpty() && !normPath(ep.endpoint).startsWith(bp)) continue
                if (route.methods.isNotEmpty() && ep.httpMethod != null &&
                    ep.httpMethod != "ANY" && ep.httpMethod !in route.methods) continue
                val e = CallEdge(gwId, ep.id, CallMode.SYNC, EdgeKind.GATEWAY, "gateway:route", null, null)
                edges.putIfAbsent(e.key(), e)
            }
        }
    }

    private fun gatewayNode(id: String, gatewayName: String, route: Gateway.Route): MethodNode = MethodNode(
        id = id, fqcn = gatewayName, method = route.routeId, layer = Layer.GATEWAY, visibility = "public",
        isAsync = false, returnType = null,
        httpMethod = route.methods.singleOrNull(), endpoint = route.publicPrefix,
        externalService = route.targetService, externalUrl = route.uri,
        file = null, line = null, project = gatewayName, module = null,
        urlPlaceholder = null, clientPackage = null,
        description = route.filters.ifEmpty { null },
    )

    /** Match a route's lb:// service to an analyzed project name (normalized). */
    private fun matchService(target: String?, projects: Set<String?>): String? {
        if (target == null) return null
        val t = target.lowercase().filter(Char::isLetterOrDigit)
        projects.filterNotNull().firstOrNull { it == target }?.let { return it }
        return projects.filterNotNull().firstOrNull {
            val p = it.lowercase().filter(Char::isLetterOrDigit)
            p == t || p.contains(t) || t.contains(p)
        }
    }

    // ---- endpoint matching (verb + normalized path, project hint as tie-breaker) ----

    /**
     * Retarget an EXTERNAL call onto a CONTROLLER endpoint of another deployable unit.
     *
     * Path matching is two-tier so that a base-path difference (server.servlet.context-path,
     * Feign `path=` base, gateway prefix) doesn't drop an otherwise-clear S2S hop to EXTERNAL:
     *  - **exact** normalized-path match — always accepted (any plausible provider).
     *  - **suffix** match — the shorter path is a clean trailing-segment suffix of the longer
     *    (≥2 shared segments). Accepted when EITHER a service signal corroborates (the provider
     *    lives in the yml-resolved [s2sService], or its project/module aliases a call hint) OR the
     *    suffix match is globally unambiguous (exactly one cross-unit provider, concrete verbs) —
     *    the latter still links a call whose host config doesn't textually name its owner. A loose
     *    overlap with neither a signal nor uniqueness never fabricates a cross-service edge.
     *
     * [s2sService] is the HostRegistry-resolved target project (yml host union) — the strongest
     * signal we have, so it both unlocks suffix matches and wins ties.
     */
    private fun matchProvider(
        providers: List<MethodNode>,
        verb: String?,
        path: String?,
        hints: List<String>,
        s2sService: String?,
        callerProject: String?,
        callerModule: String?,
    ): MethodNode? {
        val np = normPath(path)
        if (np.isEmpty()) return null
        // A provider is a valid S2S target if it lives in a different deployable unit than
        // the caller. Across projects that means a different project; within a single
        // multi-module project (services-as-modules) it means a different module.
        val cands = providers.filter {
            verbOk(it.httpMethod, verb) &&
                (it.project != callerProject || (callerModule != null && it.module != callerModule))
        }
        if (cands.isEmpty()) return null

        // Tier 1: exact path match (existing behaviour).
        cands.filter { normPath(it.endpoint) == np }.takeIf { it.isNotEmpty() }
            ?.let { return pickByHint(it, s2sService, hints, callerProject) }

        // Tier 2: suffix match (base-path/context-path/gateway-prefix tolerant).
        val suffix = cands.mapNotNull { p ->
            suffixDrop(np, normPath(p.endpoint))?.let { drop -> p to drop }
        }
        if (suffix.isEmpty()) return null
        val bestDrop = suffix.minOf { it.second }
        val tied = suffix.filter { it.second == bestDrop }.map { it.first }   // closest (fewest dropped) wins

        // 2a: a service signal (yml host / Feign name / placeholder) corroborates the closest match.
        val signal = tied.filter {
            it.project == s2sService || projectMatchesHint(it.project, hints) || moduleMatchesHint(it.module, hints)
        }
        if (signal.isNotEmpty()) return pickByHint(signal, s2sService, hints, callerProject)

        // 2b: no signal — accept only a globally UNIQUE suffix match with concrete verbs, so a
        //     single unambiguous endpoint owner is linked even when host config doesn't name it.
        if (verb != null && verb != "ANY" && suffix.size == 1 &&
            suffix.single().first.httpMethod.let { it != null && it != "ANY" }
        ) return suffix.single().first
        return null
    }

    /** Among equally-good path matches: yml-resolved target > call hint alias > any cross-project > first. */
    private fun pickByHint(
        cands: List<MethodNode>, s2sService: String?, hints: List<String>, callerProject: String?,
    ): MethodNode {
        cands.firstOrNull { it.project == s2sService }?.let { return it }
        cands.firstOrNull { projectMatchesHint(it.project, hints) || moduleMatchesHint(it.module, hints) }?.let { return it }
        cands.firstOrNull { it.project != callerProject }?.let { return it }
        return cands.first()
    }

    /**
     * If the shorter of two normalized paths is a clean trailing-segment suffix of the longer
     * (sharing ≥2 segments), return how many leading segments the longer one has extra (the
     * dropped base path) — smaller is a closer match. null when not a suffix relation. Exact
     * equality is handled by the caller's tier-1 and never reaches here.
     */
    private fun suffixDrop(a: String, b: String): Int? {
        val sa = a.split('/').filter { it.isNotEmpty() }
        val sb = b.split('/').filter { it.isNotEmpty() }
        val (short, long) = if (sa.size <= sb.size) sa to sb else sb to sa
        if (short.size < 2 || short.size == long.size) return null
        if (long.takeLast(short.size) != short) return null
        return long.size - short.size
    }

    private fun moduleMatchesHint(module: String?, tokens: List<String>): Boolean {
        if (module == null || tokens.isEmpty()) return false
        val m = module.lowercase().filter(Char::isLetterOrDigit)
        return tokens.any { it == m || m.contains(it) || it.contains(m) }
    }

    /** `/users/{id}` == `/users/{userNo}`; drop query + trailing slash. Mirrors registry.norm_path. */
    private fun normPath(p: String?): String {
        if (p.isNullOrEmpty()) return ""
        var s = p.substringBefore("?").replace(Regex("\\{[^}]*}"), "{}")
        if (s.length > 1) s = s.trimEnd('/')
        return s
    }

    private fun verbOk(providerVerb: String?, callVerb: String?): Boolean =
        callVerb == null || callVerb == "ANY" || providerVerb == null || providerVerb == "ANY" ||
            providerVerb == callVerb

    /** Candidate service identifiers from the Feign name and any `${...}` URL placeholder segment. */
    private fun hintTokens(ext: MethodNode): List<String> {
        val toks = ArrayList<String>()
        ext.externalService?.let { toks.add(it) }
        for (raw in listOfNotNull(ext.externalUrl, ext.urlPlaceholder)) {
            Regex("\\$\\{([^}]*)}").findAll(raw).forEach { m -> toks.add(m.groupValues[1].substringAfterLast('.')) }
        }
        return toks.map { it.lowercase().filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
    }

    private fun projectMatchesHint(project: String?, tokens: List<String>): Boolean {
        if (project == null || tokens.isEmpty()) return false
        val p = project.lowercase().filter(Char::isLetterOrDigit)
        return tokens.any { it == p || it.contains(p) || p.contains(it) }
    }
}
