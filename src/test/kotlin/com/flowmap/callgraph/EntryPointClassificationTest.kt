package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Entry points (reachability roots) are classified by the framework trigger a method
 * carries: HTTP @*Mapping, @KafkaListener, @Scheduled, @EventListener, MQ listeners,
 * lifecycle runners, Spring Batch beans. A method reached only via internal calls has
 * no entry-point kind (null).
 */
class EntryPointClassificationTest {

    private fun fn(
        name: String, anns: Set<String> = emptySet(), verb: String? = null, path: String? = null,
        kafkaConsumed: List<String> = emptyList(), isBean: Boolean = false, returnType: String? = null,
    ) = IrFunction(
        name = name, visibility = "public", isSuspend = false, annotationSimpleNames = anns,
        returnTypeSimple = returnType, httpMethod = verb, path = path, isBean = isBean,
        line = 1, calls = emptyList(), batchWiring = emptyList(), kafkaConsumed = kafkaConsumed,
    )

    private fun type(
        fqcn: String, anns: Set<String> = emptySet(), supers: Set<String> = emptySet(),
        funcs: List<IrFunction> = emptyList(),
    ) = IrType(
        fqcn = fqcn, simpleName = fqcn.substringAfterLast('.'), packageName = fqcn.substringBeforeLast('.', ""),
        kind = "class", annotationSimpleNames = anns, supertypeSimpleNames = supers,
        baseRequestPath = null, isFeign = false, isHttpExchange = false, functions = funcs, file = "X.kt", line = 1,
    )

    private fun nodeOf(t: IrType, method: String): MethodNode =
        GraphBuilder(listOf(IrFile("X.kt", "proj", "mod", "kotlin", listOf(t)))).build()
            .nodes.single { it.id == "${t.fqcn}#$method" }

    @Test fun `controller @GetMapping is an HTTP entry point`() {
        val n = nodeOf(type("c.UserController", setOf("RestController"),
            funcs = listOf(fn("get", setOf("GetMapping"), "GET", "/u"))), "get")
        assertEquals(EntryPointKind.HTTP, n.entryPoint)
    }

    @Test fun `@Service serving HTTP is also an HTTP entry point`() {
        val n = nodeOf(type("c.OrderService", setOf("Service"),
            funcs = listOf(fn("list", setOf("PostMapping"), "POST", "/o"))), "list")
        assertEquals(EntryPointKind.HTTP, n.entryPoint)
    }

    @Test fun `@KafkaListener is a KAFKA entry point`() {
        val n = nodeOf(type("c.Consumer", setOf("Component"),
            funcs = listOf(fn("onMsg", setOf("KafkaListener"), kafkaConsumed = listOf("order.created")))), "onMsg")
        assertEquals(EntryPointKind.KAFKA, n.entryPoint)
    }

    @Test fun `@Scheduled is a SCHEDULED entry point`() {
        val n = nodeOf(type("c.Job", setOf("Component"), funcs = listOf(fn("tick", setOf("Scheduled")))), "tick")
        assertEquals(EntryPointKind.SCHEDULED, n.entryPoint)
    }

    @Test fun `@EventListener is an EVENT entry point`() {
        val n = nodeOf(type("c.Listener", setOf("Component"), funcs = listOf(fn("on", setOf("EventListener")))), "on")
        assertEquals(EntryPointKind.EVENT, n.entryPoint)
    }

    @Test fun `@RabbitListener is a RABBIT entry point`() {
        val n = nodeOf(type("c.R", setOf("Component"), funcs = listOf(fn("on", setOf("RabbitListener")))), "on")
        assertEquals(EntryPointKind.RABBIT, n.entryPoint)
    }

    @Test fun `CommandLineRunner run is a RUNNER entry point`() {
        val n = nodeOf(type("c.Boot", setOf("Component"), supers = setOf("CommandLineRunner"),
            funcs = listOf(fn("run"))), "run")
        assertEquals(EntryPointKind.RUNNER, n.entryPoint)
    }

    @Test fun `a batch Job bean is a BATCH entry point`() {
        val n = nodeOf(type("c.BatchConfig", setOf("Configuration"),
            funcs = listOf(fn("importJob", isBean = true, returnType = "Job"))), "importJob")
        assertEquals(EntryPointKind.BATCH, n.entryPoint)
    }

    @Test fun `a plain service method is not an entry point`() {
        val n = nodeOf(type("c.PlainService", setOf("Service"), funcs = listOf(fn("compute"))), "compute")
        assertNull(n.entryPoint)
    }
}
