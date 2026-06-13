package com.flowmap.callgraph

/**
 * Intermediate representation produced by the (Analysis-API-backed) [Resolver] and
 * consumed by the pure [GraphBuilder].
 *
 * Everything that needs the live analysis session — call resolution, constant
 * evaluation, external-URL resolution — is already done here, so [GraphBuilder]
 * is free of Analysis API types. Swapping K2 for a K1 backend only requires a
 * different [Resolver] implementation that fills these structures.
 */

data class IrProject(
    val project: String?,
    val files: List<IrFile>,
)

data class IrFile(
    val path: String,                 // repo-relative
    val project: String?,
    val module: String?,
    val language: String,             // "kotlin" | "java"
    val types: List<IrType>,
)

data class IrType(
    val fqcn: String,
    val simpleName: String,
    val packageName: String,
    val kind: String,                 // "class" | "interface" | "object"
    val annotationSimpleNames: Set<String>,
    val supertypeSimpleNames: Set<String>,
    val baseRequestPath: String?,     // class-level @RequestMapping / @HttpExchange base
    val isFeign: Boolean,
    val isHttpExchange: Boolean,
    val functions: List<IrFunction>,
    val file: String?,
    val line: Int?,
    val isEntity: Boolean = false,        // @Entity
    val tableName: String? = null,        // @Table(name=...) or derived
    val repoEntity: String? = null,       // entity simple name from Repository<Entity, Id>
    /** Declared properties (ctor val/var params + body properties) — DTO schema source. */
    val properties: List<IrProperty> = emptyList(),
    /** Enum entry names when [kind] == "enum"; empty otherwise. */
    val enumEntries: List<String> = emptyList(),
)

/** A reference to a type, resolved to fqcn when possible. Analysis-API-free. */
data class IrTypeRef(
    val simpleName: String,
    val fqcn: String?,                // null for type parameters / unresolved
    val nullable: Boolean = false,
    val args: List<IrTypeRef> = emptyList(),   // generic type arguments
)

/** A declared property of a DTO/type (for request/response schema). */
data class IrProperty(
    val name: String,
    val type: IrTypeRef,
)

/** A function value parameter (for request payload / parameter docs). */
data class IrParam(
    val name: String,
    val type: IrTypeRef,
    val annotationSimpleNames: Set<String>,   // @PathVariable / @RequestParam / @RequestBody / ...
)

data class IrFunction(
    val name: String,
    val visibility: String,
    val isSuspend: Boolean,
    val annotationSimpleNames: Set<String>,
    val returnTypeSimple: String?,
    val httpMethod: String?,          // method-level @*Mapping / @*Exchange verb
    val path: String?,                // method-level path
    /** Swagger/OpenAPI endpoint description from @Operation/@ApiOperation; null if absent. */
    val apiDescription: String? = null,
    val isBean: Boolean,
    /** Fully resolved return type (for response schema); null when unresolvable. */
    val returnTypeRef: IrTypeRef? = null,
    /** Value parameters (for request payload / parameter docs). */
    val parameters: List<IrParam> = emptyList(),
    val line: Int?,
    val calls: List<IrCall>,
    /** Spring Batch builder wiring found in the body: (relation, beanName argument). */
    val batchWiring: List<Pair<String, String>>,
    /** Kafka topics this function sends to (KafkaTemplate.send("topic", ...)). */
    val kafkaProduced: List<String> = emptyList(),
    /** Kafka topics this function consumes (@KafkaListener(topics=[...])). */
    val kafkaConsumed: List<String> = emptyList(),
)

/** A resolved call site. [resolution] carries everything GraphBuilder needs. */
data class IrCall(
    val line: Int?,
    val inAsyncCtx: Boolean,
    val resolution: CallResolution,
)

sealed interface CallResolution {
    /** Call resolved to a project-local function. */
    data class Internal(
        val calleeFqcn: String,
        val calleeMethod: String,
        val calleeIsAsync: Boolean,
    ) : CallResolution

    /** Feign / @HttpExchange declarative client call (URL statically known/derivable). */
    data class DeclarativeClient(
        val calleeFqcn: String,
        val simpleName: String,
        val method: String,
        val service: String?,
        val clientPackage: String?,
        val httpMethod: String?,
        val path: String?,
        val url: String?,             // resolved base + path, or placeholder + path
        val urlPlaceholder: String?,
    ) : CallResolution

    /** Imperative client (RestTemplate / WebClient / RestClient / ...). */
    data class ImperativeClient(
        val clientType: String,       // simple type, e.g. "RestTemplate"
        val clientPackage: String?,
        val method: String,           // e.g. "exchange"
        val service: String?,         // enclosing client class simple name
        val httpMethod: String?,
        val url: String?,             // resolved literal, or base + "{uri}", or null
        val urlPlaceholder: String?,
    ) : CallResolution

    /**
     * Unresolved member on a project type whose layer is REPOSITORY — GraphBuilder
     * decides whether it is a Spring Data inherited method (save/findById/...).
     */
    data class RepositoryInherited(
        val receiverFqcn: String,
        val method: String,
    ) : CallResolution

    /** Imperative infra resource usage (Redis / JdbcTemplate) -> shared RESOURCE node. */
    data class Resource(
        val nodeId: String,       // "redis" | "db:jdbc"
        val resourceType: String, // "redis" | "db-table"
        val label: String,        // node method/label, e.g. "Redis" | "JDBC"
        val relation: String,     // "redis:io" | "db:io"
    ) : CallResolution

    /** Out of scope (library call we don't model). Dropped. */
    data object Unresolved : CallResolution
}

/**
 * The single seam behind which all Analysis API usage lives. The K2 standalone
 * implementation is [com.flowmap.callgraph.AnalysisSession]; a K1
 * (kotlin-compiler-embeddable) implementation could be dropped in unchanged.
 */
interface Resolver {
    /** Analyze a repo root, returning the fully-resolved IR per project. */
    fun analyze(
        repoRoot: String,
        projectFilter: String?,
        profile: String?,
        extraProps: Map<String, String>,
    ): List<IrFile>
}
