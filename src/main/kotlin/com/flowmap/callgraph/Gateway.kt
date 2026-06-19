package com.flowmap.callgraph

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Spring Cloud Gateway route table parser (pure). Reads a routes YAML — typically the
 * resolved `spring.cloud.gateway.routes` exported from a Config Server, since real
 * gateways externalize routes — and turns each route into a [Route] carrying the
 * frontend-facing prefix, the target service, and the path transform applied by its
 * filters (StripPrefix / PrefixPath / RewritePath). [CrossRun] uses these to add a
 * GATEWAY node per route and `gateway` edges to the backend service's endpoints.
 *
 * Accepted YAML shapes (routes located at spring.cloud.gateway.routes, or a top-level
 * `routes:`, or the document root being a list):
 *   - id: user
 *     uri: lb://tera-cloud-user
 *     predicates: [ "Path=/api/user/(asterisks)" ]   # or { name: Path, args: { pattern: ... } }
 *     filters:    [ "StripPrefix=2", "RewritePath=/api/user/(regex), /(repl)" ]
 */
object Gateway {

    data class Route(
        val routeId: String,
        val targetService: String?,   // from lb://<service>, or host for http(s)://
        val uri: String?,
        val publicPrefix: String,     // frontend-facing literal prefix, e.g. /api/user
        val backendPrefix: String,    // path prefix seen by the backend after filters ("" = root)
        val methods: List<String>,    // Method predicate, empty = all
        val filters: String,          // human summary, e.g. "StripPrefix=2"
    )

    /** A named gateway and its routes (one per Spring Cloud Gateway instance). */
    data class Source(val name: String, val routes: List<Route>)

    fun load(path: String?, gatewayName: String): List<Route> {
        val f = path?.let { File(it) } ?: return emptyList()
        if (!f.isFile) return emptyList()
        val root = try { Yaml().load<Any?>(f.readText()) } catch (_: Exception) { return emptyList() }
        val list = locateRoutes(root) ?: return emptyList()
        return list.mapIndexedNotNull { i, raw -> (raw as? Map<*, *>)?.let { parseRoute(it, i) } }
    }

    /**
     * Auto-discover a project's gateway routes by scanning its resource YAMLs for a
     * `spring.cloud.gateway.routes` list (STRICT — a bare top-level `routes:` or a
     * root list is NOT accepted here, to avoid mis-reading non-gateway YAMLs during
     * a blind scan). Covers gateways that keep routes locally in `application*.yml`
     * AND externalized gateways whose Config-Server routes were exported into the
     * project's resources. Routes are merged across files, deduped by id.
     */
    fun discover(projectDir: File): List<Route> {
        if (!projectDir.isDirectory) return emptyList()
        val routes = LinkedHashMap<String, Route>()
        projectDir.walkTopDown()
            .onEnter { it.name !in SCAN_SKIP_DIRS }
            .filter { it.isFile && (it.extension == "yml" || it.extension == "yaml") }
            .forEach { f ->
                val root = try { Yaml().load<Any?>(f.readText()) } catch (_: Exception) { return@forEach }
                val list = locateRoutes(root, strict = true) ?: return@forEach
                list.mapIndexedNotNull { i, raw -> (raw as? Map<*, *>)?.let { parseRoute(it, i) } }
                    .forEach { routes.putIfAbsent(it.routeId, it) }
            }
        return routes.values.toList()
    }

    private val SCAN_SKIP_DIRS = setOf("build", "out", ".git", ".gradle", ".kotlin", "node_modules", "target")

    @Suppress("UNCHECKED_CAST")
    private fun locateRoutes(root: Any?, strict: Boolean = false): List<*>? {
        var node: Any? = root
        for (k in listOf("spring", "cloud", "gateway", "routes")) {
            node = (node as? Map<*, *>)?.get(k) ?: break
            if (node is List<*>) return node
        }
        if (strict) return null
        if (root is List<*>) return root
        (root as? Map<*, *>)?.get("routes")?.let { if (it is List<*>) return it }
        return null
    }

    private fun parseRoute(m: Map<*, *>, idx: Int): Route {
        val id = (m["id"] ?: "route-$idx").toString()
        val uri = m["uri"]?.toString()
        val predicates = stringifyList(m["predicates"])
        val filters = stringifyList(m["filters"])

        val pathPred = predicates.firstNotNullOfOrNull { argOf(it, "Path") }
        val methods = argOf(predicates, "Method")?.split(",")?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val publicFromPredicate = literalPrefix(pathPred ?: "/")

        var strip = 0
        var prefixPath = ""
        var rewriteFrom: String? = null
        var rewriteTo: String? = null
        for (fl in filters) {
            val (name, arg) = splitArg(fl)
            when (name) {
                "StripPrefix" -> strip = arg?.trim()?.toIntOrNull() ?: 0
                "PrefixPath" -> prefixPath = arg?.trim().orEmpty()
                "RewritePath" -> arg?.split(",", limit = 2)?.let { if (it.size == 2) { rewriteFrom = it[0].trim(); rewriteTo = it[1].trim() } }
            }
        }

        val publicPrefix: String
        val backendPrefix: String
        if (rewriteFrom != null && rewriteTo != null) {
            publicPrefix = clean(literalPrefix(rewriteFrom!!))
            // Filters apply in order, and a route may BOTH `RewritePath` and `PrefixPath`
            // (the common Spring Cloud Gateway shape: `RewritePath=/pension/?(?<seg>.*),
            // /${seg}` strips the public prefix, then `PrefixPath=/public` prepends the
            // backend prefix → backend sees `/public/...`). Folding only the RewritePath
            // target dropped PrefixPath, leaving backendPrefix="" so every controller of
            // the target service got wired (instead of only the `/public/...` ones).
            backendPrefix = clean(prefixPath + "/" + literalPrefix(rewriteTo!!))
        } else {
            publicPrefix = clean(publicFromPredicate)
            val segs = publicPrefix.split("/").filter { it.isNotEmpty() }.drop(strip)
            backendPrefix = clean(prefixPath + "/" + segs.joinToString("/"))
        }

        return Route(
            routeId = id,
            targetService = serviceOf(uri),
            uri = uri,
            publicPrefix = publicPrefix.ifEmpty { "/" },
            backendPrefix = if (backendPrefix == "/") "" else backendPrefix,
            methods = methods,
            filters = filters.joinToString("; "),
        )
    }

    /** "lb://tera-cloud-user" -> "tera-cloud-user"; "http://host:8080/x" -> "host". */
    private fun serviceOf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        val afterScheme = uri.substringAfter("://", uri)
        return afterScheme.substringBefore("/").substringBefore(":").ifEmpty { null }
    }

    /** Each predicate/filter may be a "Name=args" string or a {name, args} / {Name: args} map. */
    private fun stringifyList(node: Any?): List<String> = when (node) {
        is List<*> -> node.mapNotNull { stringifyEntry(it) }
        else -> emptyList()
    }

    private fun stringifyEntry(e: Any?): String? = when (e) {
        is String -> e
        is Map<*, *> -> {
            val name = e["name"]?.toString()
            if (name != null) {
                val args = (e["args"] as? Map<*, *>)?.values?.joinToString(",") { it.toString() }
                if (args.isNullOrEmpty()) name else "$name=$args"
            } else e.entries.firstOrNull()?.let { "${it.key}=${it.value}" }
        }
        else -> null
    }

    /** Value of a "Name=arg" entry if its name matches [name] (first comma-segment for Path). */
    private fun argOf(entry: String, name: String): String? {
        val (n, a) = splitArg(entry)
        return if (n == name) a?.split(",")?.firstOrNull()?.trim() else null
    }

    private fun argOf(entries: List<String>, name: String): String? =
        entries.firstNotNullOfOrNull { val (n, a) = splitArg(it); if (n == name) a else null }

    private fun splitArg(entry: String): Pair<String, String?> {
        val i = entry.indexOf('=')
        return if (i < 0) entry.trim() to null else entry.substring(0, i).trim() to entry.substring(i + 1)
    }

    /** Literal path prefix up to the first regex/glob/placeholder metachar. */
    private fun literalPrefix(p: String): String {
        val cut = p.indexOfFirst { it in charArrayOf('(', '{', '$', '*', '?', '[', ')') }
        return (if (cut < 0) p else p.substring(0, cut))
    }

    private fun clean(p: String): String {
        val segs = p.substringBefore("?").split("/").filter { it.isNotEmpty() }
        return if (segs.isEmpty()) "" else "/" + segs.joinToString("/")
    }
}
