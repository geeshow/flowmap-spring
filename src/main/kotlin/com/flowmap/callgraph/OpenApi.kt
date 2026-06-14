package com.flowmap.callgraph

/**
 * Emits an OpenAPI 3.1 document from analyzed [IrFile]s. Pure — no Analysis API.
 *
 * Each `@RestController`/`@Controller` endpoint becomes a `paths` entry; DTO types
 * referenced by params/return become `components/schemas` (resolved recursively from
 * [IrTypeRef], with a cycle guard). `operationId` == the call-graph node id
 * (`<fqcn>#<method>`) so the API doc cross-links to the call graph / S2S edges.
 * The schema is derived statically from Kotlin types; REST Docs snippets (when
 * present) only add a summary and concrete request/response examples.
 *
 * Request-body heuristic (Spring is ambiguous without running it): a value parameter
 * is the body when it is `@RequestBody` or an unannotated complex type; `@PathVariable`
 * → path param, `@RequestParam`/unannotated simple type → query param, `@RequestHeader`
 * → header. Framework-injected types (servlet/security/paging) are dropped.
 */
object OpenApi {

    fun build(
        files: List<IrFile>,
        title: String,
        version: String = "1.0.0",
        enrich: Map<Pair<String, String>, RestDocs.ApiDoc> = emptyMap(),
    ): Map<String, Any?> = Gen(files, enrich).document(title, version)

    private val FRAMEWORK_PARAM_TYPES = setOf(
        "HttpServletRequest", "HttpServletResponse", "ServletRequest", "ServletResponse",
        "Authentication", "Principal", "Pageable", "Sort", "Model", "ModelMap",
        "BindingResult", "Errors", "HttpSession", "Locale", "TimeZone",
        "UriComponentsBuilder", "ServerWebExchange", "ServerHttpRequest", "ServerHttpResponse",
        "HttpHeaders", "InputStream", "OutputStream", "Writer", "Reader",
    )
    private val BODY_VERBS = setOf("POST", "PUT", "PATCH", "DELETE")
    private val ARRAY_TYPES = setOf(
        "List", "Set", "Collection", "Iterable", "MutableList", "MutableSet",
        "MutableCollection", "Array", "ArrayList", "HashSet", "LinkedHashSet",
    )
    private val MAP_TYPES = setOf("Map", "MutableMap", "HashMap", "LinkedHashMap", "SortedMap", "TreeMap")
    private val PARAM_ANNOTATIONS = setOf("PathVariable", "RequestParam", "RequestHeader", "RequestBody", "CookieValue", "ModelAttribute")
    private val PATH_VAR_RE = Regex("\\{([^}]+)\\}")

    private class Gen(
        val files: List<IrFile>,
        val enrich: Map<Pair<String, String>, RestDocs.ApiDoc>,
    ) {
        val typeByFqcn: Map<String, IrType> = files.flatMap { f -> f.types.map { it.fqcn to it } }.toMap()
        val schemas = LinkedHashMap<String, Any?>()        // component name -> schema
        val nameByFqcn = HashMap<String, String>()         // fqcn -> component name
        val usedNames = HashSet<String>()
        val usedOpIds = HashSet<String>()                  // operationId uniqueness (overloads)

        fun document(title: String, version: String): Map<String, Any?> {
            val paths = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
            val tags = LinkedHashSet<String>()
            for (f in files) for (t in f.types) {
                if (!isController(t)) continue
                val tag = f.project ?: t.simpleName
                for (fn in t.functions) {
                    val raw = fn.httpMethod ?: continue
                    val verb = effectiveVerb(raw, fn)
                    val path = compose(t.baseRequestPath, fn.path)
                    tags.add(tag)
                    val item = paths.getOrPut(path) { LinkedHashMap() }
                    item[verb.lowercase()] = operation(t, fn, verb, path, tag)
                }
            }
            return linkedMapOf(
                "openapi" to "3.1.0",
                "info" to linkedMapOf("title" to title, "version" to version),
                "tags" to tags.sorted().map { linkedMapOf("name" to it) },
                "paths" to paths,
                "components" to linkedMapOf("schemas" to schemas),
            )
        }

        private val STD_VERBS = setOf("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE")

        /**
         * Resolve a usable OpenAPI operation verb. `@RequestMapping` without a method yields
         * "ANY" (matches all verbs) — OpenAPI has no wildcard, so infer POST when the endpoint
         * takes a request body, else GET. Unknown verbs fall back to GET.
         */
        private fun effectiveVerb(raw: String, fn: IrFunction): String {
            val v = raw.uppercase()
            if (v in STD_VERBS) return v
            return if (hasBody(fn)) "POST" else "GET"
        }

        private fun hasBody(fn: IrFunction): Boolean = fn.parameters.any { p ->
            p.type.simpleName !in FRAMEWORK_PARAM_TYPES &&
                ("RequestBody" in p.annotationSimpleNames ||
                    (p.annotationSimpleNames.none { it in PARAM_ANNOTATIONS } && isComplex(p.type)))
        }

        // A type contributes paths when it serves HTTP — an explicit @RestController/
        // @Controller, OR (by behavior) any class with a server @*Mapping method (e.g. a
        // @Service that exposes endpoints). Outbound @FeignClient/@HttpExchange clients excluded.
        private fun isController(t: IrType): Boolean =
            !t.isFeign && !t.isHttpExchange &&
                (t.annotationSimpleNames.any { it == "RestController" || it == "Controller" } ||
                    t.functions.any { Classify.hasServerMapping(it.annotationSimpleNames) })

        private fun operation(t: IrType, fn: IrFunction, verb: String, path: String, tag: String): Map<String, Any?> {
            val op = LinkedHashMap<String, Any?>()
            // operationId == graph node id for cross-linking; suffix on collision (overloads)
            // so the document stays spec-valid (operationIds must be unique).
            val baseId = "${t.fqcn.removeSuffix(".Companion")}#${fn.name}"
            var opId = baseId
            var n = 2
            while (opId in usedOpIds) opId = "$baseId~${n++}"
            usedOpIds.add(opId)
            op["operationId"] = opId
            op["tags"] = listOf(tag)
            val doc = enrich[verb to RestDocs.normalize(path)] ?: enrich["ANY" to RestDocs.normalize(path)]
            // REST Docs description wins; else the Swagger @Operation description.
            (doc?.description ?: fn.apiDescription)?.let { op["summary"] = it }

            val params = ArrayList<Map<String, Any?>>()
            var body: IrParam? = null
            for (p in fn.parameters) {
                if (p.type.simpleName in FRAMEWORK_PARAM_TYPES) continue
                when {
                    "PathVariable" in p.annotationSimpleNames -> params.add(paramObj(p, "path", true))
                    "RequestParam" in p.annotationSimpleNames -> params.add(paramObj(p, "query", !p.type.nullable))
                    "RequestHeader" in p.annotationSimpleNames -> params.add(paramObj(p, "header", !p.type.nullable))
                    "RequestBody" in p.annotationSimpleNames -> body = p
                    isComplex(p.type) -> if (body == null) body = p
                    else -> params.add(paramObj(p, "query", !p.type.nullable))
                }
            }
            // OpenAPI requires every `{var}` in the path to have a declared path parameter.
            // Synthesize string path params for any not covered (e.g. @PathVariable name != param name,
            // or Spring regex vars `{id:\d+}`), so the document stays spec-valid.
            val declared = params.filter { it["in"] == "path" }.mapNotNull { it["name"] as? String }.toSet()
            for (tv in PATH_VAR_RE.findAll(path).map { it.groupValues[1].substringBefore(":") }) {
                if (tv !in declared) params.add(linkedMapOf("name" to tv, "in" to "path", "required" to true, "schema" to linkedMapOf("type" to "string")))
            }
            if (params.isNotEmpty()) op["parameters"] = params
            if (verb in BODY_VERBS && body != null) {
                op["requestBody"] = linkedMapOf(
                    "required" to !body.type.nullable,
                    "content" to linkedMapOf("application/json" to mediaType(schemaOf(body.type), doc?.requestExample)),
                )
            }

            val resp = LinkedHashMap<String, Any?>()
            resp["description"] = doc?.description ?: fn.apiDescription ?: "OK"
            val rt = fn.returnTypeRef
            if (rt != null && rt.simpleName !in setOf("Unit", "Void", "Nothing")) {
                resp["content"] = linkedMapOf("application/json" to mediaType(schemaOf(rt), doc?.responseExample))
            }
            op["responses"] = linkedMapOf("200" to resp)
            return op
        }

        private fun paramObj(p: IrParam, location: String, required: Boolean): Map<String, Any?> = linkedMapOf(
            "name" to p.name,
            "in" to location,
            "required" to required,
            "schema" to schemaOf(p.type),
        )

        private fun mediaType(schema: Map<String, Any?>, example: String?): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["schema"] = schema
            if (example != null) m["example"] = example
            return m
        }

        private fun isComplex(ref: IrTypeRef): Boolean = primitiveSchema(ref.simpleName) == null

        /** Map an [IrTypeRef] to an OpenAPI schema object, registering DTO components on demand. */
        private fun schemaOf(ref: IrTypeRef): Map<String, Any?> {
            primitiveSchema(ref.simpleName)?.let { return it }
            if (ref.simpleName in ARRAY_TYPES) {
                return linkedMapOf("type" to "array", "items" to (ref.args.firstOrNull()?.let { schemaOf(it) } ?: anySchema()))
            }
            if (ref.simpleName in MAP_TYPES) {
                val v = ref.args.getOrNull(1)?.let { schemaOf(it) } ?: (true as Any)
                return linkedMapOf("type" to "object", "additionalProperties" to v)
            }
            val fqcn = ref.fqcn
            if (fqcn != null && fqcn in typeByFqcn) {
                return linkedMapOf("\$ref" to "#/components/schemas/${registerComponent(fqcn)}")
            }
            return anySchema()
        }

        private fun registerComponent(fqcn: String): String {
            nameByFqcn[fqcn]?.let { return it }
            val t = typeByFqcn.getValue(fqcn)
            val name = uniqueName(t.simpleName)
            nameByFqcn[fqcn] = name
            usedNames.add(name)
            schemas[name] = LinkedHashMap<String, Any?>()    // placeholder breaks reference cycles
            schemas[name] = buildSchema(t)
            return name
        }

        private fun buildSchema(t: IrType): Map<String, Any?> {
            if (t.kind == "enum") {
                return linkedMapOf("type" to "string", "enum" to t.enumEntries)
            }
            val props = LinkedHashMap<String, Any?>()
            val required = ArrayList<String>()
            for (p in t.properties) {
                props[p.name] = schemaOf(p.type)
                if (!p.type.nullable) required.add(p.name)
            }
            val obj = LinkedHashMap<String, Any?>()
            obj["type"] = "object"
            if (props.isNotEmpty()) obj["properties"] = props
            if (required.isNotEmpty()) obj["required"] = required
            return obj
        }

        private fun uniqueName(simple: String): String {
            if (simple !in usedNames) return simple
            var i = 2
            while ("$simple$i" in usedNames) i++
            return "$simple$i"
        }

        private fun anySchema(): Map<String, Any?> = LinkedHashMap()  // {} = any (OpenAPI 3.1)

        private fun primitiveSchema(simple: String): Map<String, Any?>? = when (simple) {
            "String", "CharSequence", "Char" -> linkedMapOf("type" to "string")
            "Int", "Integer", "Short", "Byte" -> linkedMapOf("type" to "integer", "format" to "int32")
            "Long" -> linkedMapOf("type" to "integer", "format" to "int64")
            "BigInteger" -> linkedMapOf("type" to "integer")
            "Float" -> linkedMapOf("type" to "number", "format" to "float")
            "Double" -> linkedMapOf("type" to "number", "format" to "double")
            "BigDecimal" -> linkedMapOf("type" to "number")
            "Boolean" -> linkedMapOf("type" to "boolean")
            "UUID" -> linkedMapOf("type" to "string", "format" to "uuid")
            "LocalDate" -> linkedMapOf("type" to "string", "format" to "date")
            "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime", "Date" ->
                linkedMapOf("type" to "string", "format" to "date-time")
            "Any", "Object" -> LinkedHashMap()
            else -> null
        }

        /** Mirror of GraphBuilder.compose: join base + method path into a normalized "/a/b". */
        private fun compose(base: String?, path: String?): String {
            val segments = ("${base ?: ""}/${path ?: ""}").split("/").filter { it.isNotEmpty() }
            return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
        }
    }
}
