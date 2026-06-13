package com.flowmap.callgraph

/**
 * Assembles the [CallGraph] from resolved [IrFile]s. Pure — no Analysis API.
 * Ports build.py: layer resolution, node creation for tracked layers, edge
 * resolution, Spring Batch wiring, controller endpoints, first-seen ordering,
 * and the (source,target,relation,line) edge dedup.
 */
class GraphBuilder(
    private val files: List<IrFile>,
    private val includeOther: Boolean = false,
    /** {(httpMethod, normalizedPath) -> description} from REST Docs (see [RestDocs]). */
    private val descriptions: Map<Pair<String, String>, String> = emptyMap(),
    /** Drop non-public methods, contracting `public -> private -> public` to `public -> public`. */
    private val publicOnly: Boolean = false,
) {
    private val typeByFqcn: Map<String, Pair<IrType, IrFile>> =
        files.flatMap { f -> f.types.map { it.fqcn to (it to f) } }.toMap()

    private val layerOfType: Map<String, Layer> =
        typeByFqcn.mapValues { (_, v) -> layerOf(v.first) }

    private val nodes = LinkedHashMap<String, MethodNode>()
    private val edges = LinkedHashMap<List<Any?>, CallEdge>()

    fun build(): CallGraph {
        // 1) nodes for tracked declarations
        for (f in files) for (t in f.types) {
            val layer = layerOfType.getValue(t.fqcn)
            for (fn in t.functions) {
                val nl = nodeLayer(layer, fn)
                if (!tracked(nl)) continue
                addNode(makeNode(t, fn, f, layer))
            }
        }
        // 2) edges (and pulled-in endpoint nodes)
        for (f in files) for (t in f.types) {
            val layer = layerOfType.getValue(t.fqcn)
            for (fn in t.functions) {
                val nl = nodeLayer(layer, fn)
                if (!tracked(nl)) continue
                val fromId = nid(t.fqcn, fn.name)
                for (call in fn.calls) resolveCall(call, fromId, f)
                if (isBatchBean(fn)) wireBatch(t, fn, f, fromId)
                wireKafka(fn, f, fromId)
            }
        }
        // 3) DB table resource edges (repository -> table)
        wireDb()
        val graph = CallGraph(nodes.values.toList(), edges.values.toList())
        return if (publicOnly) GraphTransform.publicOnly(graph) else graph
    }

    // ---- classification ----

    private fun layerOf(t: IrType): Layer {
        for (ann in t.annotationSimpleNames) Classify.LAYER_ANNOTATIONS[ann]?.let { return it }
        // Declarative HTTP clients (@FeignClient / @HttpExchange) are external — their
        // calls become `ext:` nodes; tracking the interface declaration too would emit
        // a duplicate OTHER node for the same client.
        if (t.isFeign || t.isHttpExchange) return Layer.EXTERNAL
        if (t.supertypeSimpleNames.any { it in Classify.REPOSITORY_BASE_TYPES }) return Layer.REPOSITORY
        if (t.supertypeSimpleNames.any { it in Classify.BATCH_COMPONENT_SUPERTYPES }) return Layer.BATCH
        return Layer.OTHER
    }

    private fun isAsyncFn(fn: IrFunction): Boolean =
        fn.isSuspend ||
            fn.annotationSimpleNames.any { it in Classify.ASYNC_ANNOTATIONS } ||
            (fn.returnTypeSimple != null && fn.returnTypeSimple in Classify.ASYNC_RETURN_TYPES)

    private fun isBatchBean(fn: IrFunction): Boolean =
        fn.isBean && (fn.returnTypeSimple in Classify.BATCH_RETURN_TYPES || fn.batchWiring.isNotEmpty())

    private fun nodeLayer(typeLayer: Layer, fn: IrFunction): Layer =
        if (isBatchBean(fn)) Layer.BATCH else typeLayer

    private fun tracked(layer: Layer): Boolean = when (layer) {
        Layer.EXTERNAL -> false
        Layer.OTHER -> includeOther
        else -> true
    }

    // ---- node id construction ----

    /**
     * Drop the synthetic Kotlin `.Companion` segment so a companion-object method
     * is attributed to its enclosing class (matching Java statics and the Python
     * tool). Nested-class nesting is intentionally preserved — flattening it would
     * collide distinct nested types. Applied to BOTH declared node ids and call
     * target ids so edges stay connected; map keys (typeByFqcn/layerOfType) keep
     * the full fqcn for resolution.
     */
    private fun normalizeFqcn(fqcn: String): String = fqcn.removeSuffix(".Companion")

    private fun nid(fqcn: String, method: String): String = "${normalizeFqcn(fqcn)}#$method"

    // ---- node construction ----

    private fun makeNode(t: IrType, fn: IrFunction, f: IrFile, typeLayer: Layer): MethodNode {
        val (verb, endpoint) = endpointOf(t, fn)
        return MethodNode(
            id = nid(t.fqcn, fn.name),
            fqcn = normalizeFqcn(t.fqcn),
            method = fn.name,
            layer = nodeLayer(typeLayer, fn),
            visibility = fn.visibility,
            isAsync = isAsyncFn(fn),
            returnType = fn.returnTypeSimple,
            httpMethod = verb,
            endpoint = endpoint,
            externalService = null,
            externalUrl = null,
            file = f.path,
            line = fn.line,
            project = f.project,
            module = f.module,
            urlPlaceholder = null,
            clientPackage = null,
            description = descriptionFor(verb, endpoint) ?: fn.apiDescription,
        )
    }

    /**
     * Endpoint description: REST Docs snippet (verb-specific, else ANY) takes
     * precedence; callers fall back to the function's Swagger `@Operation`
     * description so a Swagger-documented project gets the same enrichment.
     */
    private fun descriptionFor(verb: String?, endpoint: String?): String? {
        if (descriptions.isEmpty() || endpoint == null) return null
        val np = RestDocs.normalize(endpoint)
        return descriptions[verb to np] ?: descriptions["ANY" to np]
    }

    private fun endpointOf(t: IrType, fn: IrFunction): Pair<String?, String?> {
        if (fn.httpMethod == null && fn.path == null) return null to null
        return fn.httpMethod to compose(t.baseRequestPath, fn.path)
    }

    private fun compose(base: String?, path: String?): String {
        val segments = ("${base ?: ""}/${path ?: ""}").split("/").filter { it.isNotEmpty() }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun addNode(node: MethodNode) {
        nodes.putIfAbsent(node.id, node)
    }

    private fun emit(from: String, to: String, mode: CallMode, kind: EdgeKind, relation: String, f: IrFile, line: Int?) {
        val e = CallEdge(from, to, mode, kind, relation, f.path, line)
        edges.putIfAbsent(e.key(), e)
    }

    private fun asyncMode(inAsyncCtx: Boolean, calleeAsync: Boolean = false): CallMode =
        if (inAsyncCtx || calleeAsync) CallMode.ASYNC else CallMode.SYNC

    // ---- edge resolution ----

    private fun resolveCall(call: IrCall, fromId: String, f: IrFile) {
        when (val r = call.resolution) {
            is CallResolution.Internal -> {
                // Constructor calls (`Foo(...)`) aren't graph nodes — match the Python tool, which
                // never resolves a method named like a constructor. Drops the bulk of dangling edges.
                if (r.calleeMethod == "<init>") return
                val tid = nid(r.calleeFqcn, r.calleeMethod)
                if (tid !in nodes) ensureProjectNode(r.calleeFqcn, r.calleeMethod)
                // Method declared on a supertype/fragment (or synthetic, e.g. enum valueOf):
                // attribute it to the receiver type so the edge stays navigable (as Python does
                // by walking supertypes). Drops the edge only if the owner isn't a project type.
                if (tid !in nodes) stubNode(r.calleeFqcn, r.calleeMethod)?.let { addNode(it) }
                if (tid !in nodes) return
                emit(fromId, tid, asyncMode(call.inAsyncCtx, r.calleeIsAsync),
                    EdgeKind.INTERNAL, "call", f, call.line)
            }
            is CallResolution.DeclarativeClient -> {
                val tid = "ext:${r.simpleName}#${r.method}"
                addNode(externalNode(tid, r.calleeFqcn, r.method,
                    service = r.service ?: r.simpleName,
                    httpMethod = r.httpMethod, endpoint = r.path,
                    url = r.url, placeholder = r.urlPlaceholder, clientPackage = r.clientPackage))
                emit(fromId, tid, asyncMode(call.inAsyncCtx), EdgeKind.EXTERNAL, "call", f, call.line)
            }
            is CallResolution.ImperativeClient -> {
                val host = hostOf(r.url)
                val tid = if (r.url != null) "ext:${stripScheme(r.url)}" else "ext:${r.clientType}#${r.method}"
                addNode(externalNode(tid, r.clientType, r.method,
                    service = host ?: r.service ?: r.clientType,
                    httpMethod = r.httpMethod, endpoint = pathOf(r.url),
                    url = r.url, placeholder = r.urlPlaceholder, clientPackage = r.clientPackage))
                emit(fromId, tid, asyncMode(call.inAsyncCtx), EdgeKind.EXTERNAL, "call", f, call.line)
            }
            is CallResolution.Resource -> {
                addNode(resourceNode(r.nodeId, r.resourceType, r.label))
                emit(fromId, r.nodeId, asyncMode(call.inAsyncCtx), EdgeKind.RESOURCE, r.relation, f, call.line)
            }
            is CallResolution.RepositoryInherited -> {
                if (layerOfType[r.receiverFqcn] != Layer.REPOSITORY) return
                if (r.method !in Classify.REPOSITORY_INHERITED_METHODS) return
                val tid = nid(r.receiverFqcn, r.method)
                addNode(repoInheritedNode(r.receiverFqcn, r.method))
                emit(fromId, tid, asyncMode(call.inAsyncCtx), EdgeKind.INTERNAL, "call", f, call.line)
            }
            CallResolution.Unresolved -> { /* drop */ }
        }
    }

    private fun ensureProjectNode(fqcn: String, method: String) {
        val (t, f) = typeByFqcn[fqcn] ?: return
        val fn = t.functions.firstOrNull { it.name == method } ?: return
        addNode(makeNode(t, fn, f, layerOfType.getValue(fqcn)))
    }

    /**
     * A node for a method the receiver type owns by inheritance (supertype/fragment) or
     * synthesis (enum `valueOf`/`values`) — not declared on the type itself. Returns null
     * when the owner isn't a project type (caller then drops the edge).
     */
    private fun stubNode(fqcn: String, method: String): MethodNode? {
        val (_, f) = typeByFqcn[fqcn] ?: return null
        return MethodNode(
            id = nid(fqcn, method), fqcn = normalizeFqcn(fqcn), method = method,
            layer = layerOfType.getValue(fqcn), visibility = "public", isAsync = false,
            returnType = null, httpMethod = null, endpoint = null, externalService = null,
            externalUrl = null, file = f.path, line = null, project = f.project, module = f.module,
            urlPlaceholder = null, clientPackage = null,
        )
    }

    private fun externalNode(
        id: String, fqcn: String, method: String, service: String?,
        httpMethod: String?, endpoint: String?, url: String?, placeholder: String?, clientPackage: String?,
    ) = MethodNode(
        id = id, fqcn = fqcn, method = method, layer = Layer.EXTERNAL, visibility = "public",
        isAsync = false, returnType = null, httpMethod = httpMethod, endpoint = endpoint,
        externalService = service, externalUrl = url, file = null, line = null,
        project = null, module = null, urlPlaceholder = placeholder, clientPackage = clientPackage,
    )

    private fun repoInheritedNode(fqcn: String, method: String): MethodNode {
        val f = typeByFqcn[fqcn]?.second
        return MethodNode(
            id = nid(fqcn, method), fqcn = normalizeFqcn(fqcn), method = method, layer = Layer.REPOSITORY,
            visibility = "public", isAsync = false, returnType = null, httpMethod = null,
            endpoint = null, externalService = null, externalUrl = null, file = f?.path, line = null,
            project = f?.project, module = f?.module, urlPlaceholder = null, clientPackage = null,
        )
    }

    // ---- batch wiring ----

    private fun wireBatch(t: IrType, fn: IrFunction, f: IrFile, fromId: String) {
        val siblings = t.functions.associateBy { it.name }
        for ((relation, beanName) in fn.batchWiring) {
            val target = siblings[beanName] ?: continue
            val tid = nid(t.fqcn, target.name)
            if (tid !in nodes) addNode(makeNode(t, target, f, layerOfType.getValue(t.fqcn)))
            emit(fromId, tid, CallMode.ASYNC, EdgeKind.BATCH, relation, f, fn.line)
        }
    }

    // ---- infra resources (Kafka / Redis / DB) ----

    private fun resourceNode(id: String, rtype: String, label: String): MethodNode = MethodNode(
        id = id, fqcn = id, method = label, layer = Layer.RESOURCE, visibility = "public",
        isAsync = false, returnType = null, httpMethod = null, endpoint = null,
        externalService = null, externalUrl = null, file = null, line = null,
        project = null, module = null, urlPlaceholder = null, clientPackage = null,
        resourceType = rtype,
    )

    private fun wireKafka(fn: IrFunction, f: IrFile, fromId: String) {
        for (topic in fn.kafkaProduced) {
            val tid = "kafka:$topic"
            addNode(resourceNode(tid, "kafka-topic", topic))
            emit(fromId, tid, CallMode.ASYNC, EdgeKind.RESOURCE, "kafka:produce", f, fn.line)
        }
        for (topic in fn.kafkaConsumed) {
            val tid = "kafka:$topic"
            addNode(resourceNode(tid, "kafka-topic", topic))
            emit(tid, fromId, CallMode.ASYNC, EdgeKind.RESOURCE, "kafka:consume", f, fn.line)
        }
    }

    /** Each repository node gets an edge to its managed DB table resource node. */
    private fun wireDb() {
        for ((fqcn, pair) in typeByFqcn) {
            if (layerOfType[fqcn] != Layer.REPOSITORY) continue
            val (t, f) = pair
            val entity = t.repoEntity ?: continue
            val table = tableFor(entity)
            val tid = "db:table:$table"
            addNode(resourceNode(tid, "db-table", table))
            for (n in nodes.values.filter { it.fqcn == fqcn }) {
                emit(n.id, tid, CallMode.SYNC, EdgeKind.RESOURCE, "db:io", f, n.line)
            }
        }
    }

    private fun tableFor(entitySimple: String): String {
        val entity = typeByFqcn.values.firstOrNull { it.first.simpleName == entitySimple && it.first.isEntity }
        return entity?.first?.tableName ?: entitySimple.lowercase()
    }

    // ---- url helpers ----

    private fun stripScheme(url: String): String =
        url.substringAfter("://").ifEmpty { url }

    private fun hostOf(url: String?): String? {
        if (url == null || url.contains("\${")) return null
        val afterScheme = url.substringAfter("://", url)
        return afterScheme.substringBefore("/").ifEmpty { null }
    }

    private fun pathOf(url: String?): String? {
        if (url == null) return null
        val afterScheme = url.substringAfter("://", url)
        val idx = afterScheme.indexOf('/')
        return if (idx >= 0) afterScheme.substring(idx) else null
    }
}
