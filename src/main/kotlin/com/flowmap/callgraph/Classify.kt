package com.flowmap.callgraph

/**
 * Signature tables ported from the Python tool's classify.py.
 * Edit these to match your stack (in-house HTTP/gRPC client packages, etc.).
 */
object Classify {

    val LAYER_ANNOTATIONS: Map<String, Layer> = mapOf(
        "RestController" to Layer.CONTROLLER,
        "Controller" to Layer.CONTROLLER,
        "Service" to Layer.SERVICE,
        "Repository" to Layer.REPOSITORY,
        "Component" to Layer.COMPONENT,
        "Configuration" to Layer.CONFIG,
    )

    val REPOSITORY_BASE_TYPES: Set<String> = setOf(
        "Repository", "CrudRepository", "PagingAndSortingRepository",
        "ListCrudRepository", "JpaRepository", "ReactiveCrudRepository",
        "CoroutineCrudRepository",
    )

    val REPOSITORY_INHERITED_METHODS: Set<String> = setOf(
        "save", "saveAll", "saveAndFlush", "findById", "findAll", "findAllById",
        "getById", "getReferenceById", "existsById", "count", "delete", "deleteById",
        "deleteAll", "deleteAllById", "flush",
    )

    val EXTERNAL_SIMPLE_TYPES: Set<String> = setOf(
        "RestTemplate", "WebClient", "RestClient", "JdbcTemplate",
        "NamedParameterJdbcTemplate", "KafkaTemplate", "WebTestClient",
    )

    /**
     * Methods that carry the request URL in a fluent HTTP-client chain
     * (WebClient/RestClient: `client.get().uri(url)...`). Their immediate receiver is
     * a builder/spec type, so the call is matched by walking the chain to its root client.
     */
    val FLUENT_URL_METHODS: Set<String> = setOf("uri")

    val EXTERNAL_PREFIXES: List<String> = listOf(
        "org.springframework.web.client.RestTemplate",
        "org.springframework.web.reactive.function.client.WebClient",
        "org.springframework.web.client.RestClient",
        "org.springframework.jdbc.core.JdbcTemplate",
        "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
        "org.springframework.kafka.core.KafkaTemplate",
        "feign.",
        "org.springframework.cloud.openfeign.",
    )

    // ---- infra resources (Kafka / Redis / DB) ----

    /** Kafka producer: a `send`/`sendDefault` call on one of these receiver types. */
    val KAFKA_TEMPLATE_TYPES: Set<String> = setOf("KafkaTemplate", "ReplyingKafkaTemplate")
    val KAFKA_SEND_METHODS: Set<String> = setOf("send", "sendDefault", "sendOffsetsToTransaction")

    /** Kafka consumer: method annotated with one of these (topics taken from `@KafkaListener`). */
    val KAFKA_LISTENER_ANNOTATIONS: Set<String> = setOf("KafkaListener", "KafkaHandler", "RetryableTopic")

    /** Redis: a call on one of these receiver types -> shared `redis` resource node. */
    val REDIS_TEMPLATE_TYPES: Set<String> = setOf(
        "RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate", "ReactiveStringRedisTemplate",
    )

    /** JdbcTemplate-family -> DB resource node (not a 3rd-party HTTP call). */
    val JDBC_TEMPLATE_TYPES: Set<String> = setOf("JdbcTemplate", "NamedParameterJdbcTemplate")

    /** JPA entity marker. */
    val ENTITY_ANNOTATIONS: Set<String> = setOf("Entity")

    /** Spring Data base interfaces whose first type argument is the managed entity. */
    val REPOSITORY_GENERIC_BASES: Set<String> = setOf(
        "JpaRepository", "CrudRepository", "PagingAndSortingRepository", "ListCrudRepository",
        "ReactiveCrudRepository", "CoroutineCrudRepository", "Repository",
    )

    val FEIGN_CLIENT_ANNOTATIONS: Set<String> = setOf("FeignClient")

    /** Spring 6 declarative HTTP interface marker (class- or method-level). */
    val HTTP_EXCHANGE_ANNOTATIONS: Set<String> = setOf("HttpExchange")

    /** Method-level @*Exchange annotations -> HTTP verb. */
    val EXCHANGE_VERBS: Map<String, String> = mapOf(
        "GetExchange" to "GET", "PostExchange" to "POST", "PutExchange" to "PUT",
        "DeleteExchange" to "DELETE", "PatchExchange" to "PATCH",
        "HttpExchange" to "ANY",
    )

    /** Method-level @*Mapping annotations -> HTTP verb. */
    val MAPPING_VERBS: Map<String, String> = mapOf(
        "GetMapping" to "GET", "PostMapping" to "POST", "PutMapping" to "PUT",
        "DeleteMapping" to "DELETE", "PatchMapping" to "PATCH",
        "RequestMapping" to "ANY",
    )

    /**
     * A method DECLARES a server HTTP endpoint when it carries a Spring server
     * @*Mapping annotation (GetMapping/PostMapping/…/RequestMapping). Used to classify
     * a class as a controller by BEHAVIOR — a class that serves endpoints is a
     * controller even when stereotyped @Service/@Component. (Outbound @*Exchange
     * client methods are deliberately excluded — those are calls, not endpoints.)
     */
    fun hasServerMapping(annotationSimpleNames: Set<String>): Boolean =
        annotationSimpleNames.any { it in MAPPING_VERBS }

    val ASYNC_ANNOTATIONS: Set<String> = setOf("Async", "Scheduled")

    val ASYNC_BUILDERS: Set<String> = setOf(
        "launch", "async", "withContext", "runBlocking", "supervisorScope",
        "coroutineScope", "flow", "produce",
        "supplyAsync", "runAsync", "thenApplyAsync", "thenAcceptAsync", "thenRunAsync",
    )

    val ASYNC_RETURN_TYPES: Set<String> = setOf(
        "Mono", "Flux", "CompletableFuture", "Future", "Deferred", "Flow", "Publisher",
    )

    val BATCH_ENABLE_ANNOTATIONS: Set<String> = setOf("EnableBatchProcessing")

    val BATCH_COMPONENT_SUPERTYPES: Set<String> = setOf(
        "Tasklet", "ItemReader", "ItemProcessor", "ItemWriter",
        "ItemStreamReader", "ItemStreamWriter", "StepExecutionListener",
        "JobExecutionListener", "ChunkListener",
    )

    val BATCH_RETURN_TYPES: Set<String> = setOf(
        "Job", "Step", "Flow", "Tasklet", "ItemReader", "ItemProcessor", "ItemWriter",
    )

    /** Builder method -> batch relation tag. */
    val BATCH_WIRING_METHODS: Map<String, String> = mapOf(
        "start" to "batch:step",
        "next" to "batch:step",
        "flow" to "batch:step",
        "tasklet" to "batch:tasklet",
        "reader" to "batch:reader",
        "processor" to "batch:processor",
        "writer" to "batch:writer",
        "listener" to "batch:listener",
    )

    fun isExternalByPrefix(fqcn: String?): Boolean =
        fqcn != null && EXTERNAL_PREFIXES.any { fqcn.startsWith(it) }
}
