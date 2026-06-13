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

    fun combine(graphs: List<CallGraph>, gatewayRoutes: List<Gateway.Route> = emptyList(), gatewayName: String = "gateway"): CallGraph {
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
            val provider = ext?.let {
                if (it.endpoint.isNullOrEmpty()) null
                else matchProvider(providers, it.httpMethod, it.endpoint, hintTokens(it), nodes[e.source]?.project)
            }
            val ne = if (provider != null) e.copy(target = provider.id, kind = EdgeKind.S2S) else e
            newEdges.putIfAbsent(ne.key(), ne)
        }

        // 4) gateway pass: a GATEWAY node per route + `gateway` edges to the routed
        //    backend service's endpoints (frontend prefix vs server path made visible).
        wireGateways(gatewayRoutes, gatewayName, providers, nodes, newEdges)

        // 5) drop external stubs that no surviving edge references anymore
        val referenced = HashSet<String>()
        for (e in newEdges.values) { referenced.add(e.source); referenced.add(e.target) }
        val finalNodes = nodes.values.filter { it.layer != Layer.EXTERNAL || it.id in referenced }
        return CallGraph(finalNodes, newEdges.values.toList())
    }

    // ---- gateway routing ----

    private fun wireGateways(
        routes: List<Gateway.Route>, gatewayName: String, providers: List<MethodNode>,
        nodes: LinkedHashMap<String, MethodNode>, edges: LinkedHashMap<List<Any?>, CallEdge>,
    ) {
        if (routes.isEmpty()) return
        val byProject = providers.groupBy { it.project }
        for (route in routes) {
            val gwId = "gateway:$gatewayName#${route.routeId}"
            nodes.putIfAbsent(gwId, gatewayNode(gwId, gatewayName, route))
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

    private fun matchProvider(
        providers: List<MethodNode>,
        verb: String?,
        path: String?,
        hints: List<String>,
        callerProject: String?,
    ): MethodNode? {
        val np = normPath(path)
        if (np.isEmpty()) return null
        val cands = providers.filter {
            normPath(it.endpoint) == np && verbOk(it.httpMethod, verb) && it.project != callerProject
        }
        if (cands.isEmpty()) return null
        cands.firstOrNull { projectMatchesHint(it.project, hints) }?.let { return it }
        return cands.first()
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
