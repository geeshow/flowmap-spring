package com.flowmap.callgraph

/**
 * Output data model + node-link JSON serialization.
 *
 * This mirrors the Python tool's contract exactly (camelCase keys, null inclusion,
 * first-seen ordering) and adds two ADDITIVE keys: `urlPlaceholder` and `clientPackage`.
 * No Analysis API types appear here — this layer is pure and independently testable.
 */

enum class Layer {
    CONTROLLER, SERVICE, REPOSITORY, COMPONENT, CONFIG, BATCH, EXTERNAL, RESOURCE, OTHER
}

enum class EdgeKind(val json: String) {
    INTERNAL("internal"), EXTERNAL("external"), BATCH("batch"), S2S("s2s"), RESOURCE("resource")
}

enum class CallMode(val json: String) {
    SYNC("sync"), ASYNC("async")
}

data class MethodNode(
    val id: String,                  // "<fqcn>#<method>"; external: "ext:<service>#<method>"
    val fqcn: String,
    val method: String,
    val layer: Layer,
    val visibility: String,
    val isAsync: Boolean,
    val returnType: String?,
    val httpMethod: String?,         // controller endpoint or external client verb
    val endpoint: String?,           // controller full path, or external path
    val externalService: String?,    // Feign name / client type / @HttpExchange service
    val externalUrl: String?,        // resolved base + path (or raw placeholder when unresolved)
    val file: String?,               // repo-relative path
    val line: Int?,
    val project: String?,
    val module: String?,
    val urlPlaceholder: String?,     // ADDITIVE: raw "${...}" before yml resolution
    val clientPackage: String?,      // ADDITIVE: package of the external client class/interface
    val resourceType: String? = null, // RESOURCE node kind: "kafka-topic" | "redis" | "db-table"
) {
    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "id" to id,
        "fqcn" to fqcn,
        "method" to method,
        "layer" to layer.name,
        "visibility" to visibility,
        "async" to isAsync,
        "returnType" to returnType,
        "httpMethod" to httpMethod,
        "endpoint" to endpoint,
        "externalService" to externalService,
        "externalUrl" to externalUrl,
        "resourceType" to resourceType,
        "urlPlaceholder" to urlPlaceholder,
        "clientPackage" to clientPackage,
        "file" to file,
        "line" to line,
        "project" to project,
        "module" to module,
    )
}

data class CallEdge(
    val source: String,
    val target: String,
    val mode: CallMode,
    val kind: EdgeKind,
    val relation: String,            // "call" | "batch:step" | "batch:reader" | ...
    val callSiteFile: String?,
    val callSiteLine: Int?,
) {
    /** Dedup key — identical to the Python tool's CallEdge.key(). */
    fun key(): List<Any?> = listOf(source, target, relation, callSiteLine)

    fun toJson(): LinkedHashMap<String, Any?> = linkedMapOf(
        "source" to source,
        "target" to target,
        "mode" to mode.json,
        "kind" to kind.json,
        "relation" to relation,
        "callSiteFile" to callSiteFile,
        "callSiteLine" to callSiteLine,
    )
}

data class CallGraph(
    val nodes: List<MethodNode>,
    val edges: List<CallEdge>,
) {
    fun toNodeLink(meta: Map<String, Any?> = emptyMap()): LinkedHashMap<String, Any?> = linkedMapOf(
        "directed" to true,
        "multigraph" to true,
        "meta" to meta,
        "nodes" to nodes.map { it.toJson() },
        "edges" to edges.map { it.toJson() },
    )
}
