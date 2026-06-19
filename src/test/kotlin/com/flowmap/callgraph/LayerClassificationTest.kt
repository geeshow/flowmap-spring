package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A class is classified by BEHAVIOR: one that serves HTTP endpoints (@*Mapping
 * methods) is a CONTROLLER even when stereotyped @Service/@Component. Outbound
 * @FeignClient/@HttpExchange clients stay EXTERNAL.
 */
class LayerClassificationTest {

    private fun fn(name: String, anns: Set<String> = emptySet(), verb: String? = null, path: String? = null) =
        IrFunction(
            name = name, visibility = "public", isSuspend = false, annotationSimpleNames = anns,
            returnTypeSimple = null, httpMethod = verb, path = path, isBean = false,
            line = 1, calls = emptyList(), batchWiring = emptyList(),
        )

    private fun type(
        fqcn: String, anns: Set<String> = emptySet(), funcs: List<IrFunction> = emptyList(),
        isFeign: Boolean = false, isHttpExchange: Boolean = false, base: String? = null,
    ) = IrType(
        fqcn = fqcn, simpleName = fqcn.substringAfterLast('.'), packageName = fqcn.substringBeforeLast('.', ""),
        kind = "class", annotationSimpleNames = anns, supertypeSimpleNames = emptySet(),
        baseRequestPath = base, isFeign = isFeign, isHttpExchange = isHttpExchange, functions = funcs,
        file = "X.kt", line = 1,
    )

    private fun graphOf(vararg types: IrType): CallGraph =
        GraphBuilder(listOf(IrFile("X.kt", "proj", null, "kotlin", types.toList()))).build()

    @Test fun `@Service with @GetMapping is a CONTROLLER with the endpoint`() {
        val g = graphOf(type("com.x.OrderService", setOf("Service"), base = "/api",
            funcs = listOf(fn("list", setOf("GetMapping"), "GET", "/orders"))))
        val n = g.nodes.single { it.id == "com.x.OrderService#list" }
        assertEquals(Layer.CONTROLLER, n.layer)       // reclassified by behavior, not stereotype
        assertEquals("GET", n.httpMethod)
        assertEquals("/api/orders", n.endpoint)        // class base + method path composed
    }

    @Test fun `@Service with Armeria @Get is a CONTROLLER with the endpoint (S2S provider)`() {
        // alien exposes endpoints via Armeria (com.linecorp.armeria.server.annotation.Get),
        // not Spring @GetMapping. It must still be a CONTROLLER so a declarative-client
        // (@GetExchange) S2S call can join to it instead of dangling as an external API.
        val g = graphOf(type("com.x.KycHttpService", setOf("Service"),
            funcs = listOf(fn("getKycCdd", setOf("Get"), "GET", "/v1/kyc/cdd"))))
        val n = g.nodes.single { it.id == "com.x.KycHttpService#getKycCdd" }
        assertEquals(Layer.CONTROLLER, n.layer)
        assertEquals("GET", n.httpMethod)
        assertEquals("/v1/kyc/cdd", n.endpoint)
    }

    @Test fun `@Service without any mapping stays SERVICE`() {
        val g = graphOf(type("com.x.PlainService", setOf("Service"), funcs = listOf(fn("compute"))))
        val n = g.nodes.single { it.id == "com.x.PlainService#compute" }
        assertEquals(Layer.SERVICE, n.layer)
        assertEquals(null, n.httpMethod)
    }

    @Test fun `non-endpoint methods of a serving @Service are also CONTROLLER-layer`() {
        val g = graphOf(type("com.x.MixedService", setOf("Service"), funcs = listOf(
            fn("handle", setOf("PostMapping"), "POST", "/do"),
            fn("helper"),    // no mapping, but the whole class is a controller now
        )))
        assertEquals(Layer.CONTROLLER, g.nodes.single { it.id == "com.x.MixedService#handle" }.layer)
        assertEquals(Layer.CONTROLLER, g.nodes.single { it.id == "com.x.MixedService#helper" }.layer)
    }

    @Test fun `@FeignClient with @GetExchange is NOT reclassified (stays EXTERNAL, untracked)`() {
        val g = graphOf(type("com.x.PayClient", setOf("FeignClient"), isFeign = true,
            funcs = listOf(fn("pay", setOf("GetExchange"), "GET", "/pay"))))
        assertTrue(g.nodes.none { it.fqcn == "com.x.PayClient" })   // EXTERNAL layer is not tracked -> no node
    }

    @Test fun `@RestController is unchanged`() {
        val g = graphOf(type("com.x.UserController", setOf("RestController"),
            funcs = listOf(fn("get", setOf("GetMapping"), "GET", "/users"))))
        assertEquals(Layer.CONTROLLER, g.nodes.single { it.id == "com.x.UserController#get" }.layer)
    }
}
