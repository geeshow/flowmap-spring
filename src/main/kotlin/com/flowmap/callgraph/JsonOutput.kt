package com.flowmap.callgraph

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

/** Node-link JSON writer + reader. Writes maps directly so the key order and
 *  null inclusion exactly match the Python tool's contract. */
object JsonOutput {
    private val mapper: ObjectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    fun write(graph: CallGraph, meta: Map<String, Any?>): String =
        mapper.writeValueAsString(graph.toNodeLink(meta))

    /** Pretty-print an arbitrary value (used for the OpenAPI document). */
    fun writeValue(value: Any?): String = mapper.writeValueAsString(value)

    /** True if [text] parses as a node-link call graph (has a `nodes` array). */
    fun isGraph(text: String): Boolean = try {
        mapper.readTree(text)?.get("nodes")?.isArray == true
    } catch (_: Exception) { false }

    /** Like [read] but returns null for non-graph JSON (e.g. an OpenAPI doc). */
    fun readOrNull(text: String): CallGraph? = if (isGraph(text)) read(text) else null

    /** Load a prebuilt graph.json back into a CallGraph (for search/stats --graph). */
    fun read(text: String): CallGraph {
        val root = mapper.readTree(text)
        val nodes = root["nodes"].map { n ->
            MethodNode(
                id = n.str("id")!!,
                fqcn = n.str("fqcn")!!,
                method = n.str("method")!!,
                layer = Layer.valueOf(n.str("layer")!!),
                visibility = n.str("visibility") ?: "public",
                isAsync = n["async"]?.asBoolean(false) ?: false,
                returnType = n.str("returnType"),
                httpMethod = n.str("httpMethod"),
                endpoint = n.str("endpoint"),
                externalService = n.str("externalService"),
                externalUrl = n.str("externalUrl"),
                file = n.str("file"),
                line = n.int("line"),
                project = n.str("project"),
                module = n.str("module"),
                urlPlaceholder = n.str("urlPlaceholder"),
                clientPackage = n.str("clientPackage"),
                resourceType = n.str("resourceType"),
                description = n.str("description"),
                entryPoint = n.str("entryPoint")?.let { runCatching { EntryPointKind.valueOf(it) }.getOrNull() },
            )
        }
        val edges = root["edges"].map { e ->
            CallEdge(
                source = e.str("source")!!,
                target = e.str("target")!!,
                mode = if (e.str("mode") == "async") CallMode.ASYNC else CallMode.SYNC,
                kind = when (e.str("kind")) {
                    "external" -> EdgeKind.EXTERNAL
                    "batch" -> EdgeKind.BATCH
                    "s2s" -> EdgeKind.S2S
                    "resource" -> EdgeKind.RESOURCE
                    "gateway" -> EdgeKind.GATEWAY
                    else -> EdgeKind.INTERNAL
                },
                relation = e.str("relation") ?: "call",
                callSiteFile = e.str("callSiteFile"),
                callSiteLine = e.int("callSiteLine"),
            )
        }
        return CallGraph(nodes, edges)
    }

    private fun JsonNode.str(field: String): String? =
        this[field]?.takeIf { !it.isNull }?.asText()

    private fun JsonNode.int(field: String): Int? =
        this[field]?.takeIf { !it.isNull }?.asInt()
}
