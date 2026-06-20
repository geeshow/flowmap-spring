package com.flowmap.callgraph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S2S endpoint matching in [CrossRun.combine]: an EXTERNAL call retargets onto a provider
 * controller endpoint (exact path, or suffix path when a base-path/context-path/gateway prefix
 * differs and a service signal corroborates), otherwise stays EXTERNAL.
 */
class CrossRunS2STest {

    private fun controller(project: String, path: String, verb: String? = "GET", id: String = "$project.Ctl#h") =
        MethodNode(
            id = id, fqcn = "$project.Ctl", method = "h", layer = Layer.CONTROLLER,
            visibility = "public", isAsync = false, returnType = null, httpMethod = verb, endpoint = path,
            externalService = null, externalUrl = null, file = null, line = null,
            project = project, module = null, urlPlaceholder = null, clientPackage = null,
        )

    private fun external(
        project: String, path: String, verb: String? = "GET",
        service: String? = null, url: String? = null, s2s: String? = null, id: String = "ext:$service#call",
    ) = MethodNode(
        id = id, fqcn = "ext", method = "call", layer = Layer.EXTERNAL,
        visibility = "public", isAsync = false, returnType = null, httpMethod = verb, endpoint = path,
        externalService = service, externalUrl = url, file = null, line = null,
        project = project, module = null, urlPlaceholder = null, clientPackage = null, s2sService = s2s,
    )

    private fun caller(project: String, id: String = "$project.Svc#m") = MethodNode(
        id = id, fqcn = "$project.Svc", method = "m", layer = Layer.SERVICE,
        visibility = "public", isAsync = false, returnType = null, httpMethod = null, endpoint = null,
        externalService = null, externalUrl = null, file = null, line = null,
        project = project, module = null, urlPlaceholder = null, clientPackage = null,
    )

    private fun edge(s: String, t: String) =
        CallEdge(s, t, CallMode.SYNC, EdgeKind.EXTERNAL, "call", null, 1)

    /** combine a caller-project graph (svc + external stub + edge) with a provider-project graph. */
    private fun run(callerNode: MethodNode, ext: MethodNode, provider: MethodNode): CallGraph {
        val g1 = CallGraph(listOf(callerNode, ext), listOf(edge(callerNode.id, ext.id)))
        val g2 = CallGraph(listOf(provider), emptyList())
        return CrossRun.combine(listOf(g1, g2))
    }

    @Test fun `exact path match retargets to provider as s2s`() {
        val out = run(caller("a"), external("a", "/v1/users"), controller("b", "/v1/users"))
        val e = out.edges.single()
        assertEquals("b.Ctl#h", e.target)
        assertEquals(EdgeKind.S2S, e.kind)
        assertTrue(out.nodes.none { it.layer == Layer.EXTERNAL })   // stub dropped
    }

    @Test fun `context-path prefix difference matches via suffix when s2sService corroborates`() {
        // caller resolved url => /api/v1/user/info ; provider endpoint => /v1/user/info
        val out = run(
            caller("a"),
            external("a", "/api/v1/user/info", s2s = "b"),
            controller("b", "/v1/user/info"),
        )
        val e = out.edges.single()
        assertEquals("b.Ctl#h", e.target)
        assertEquals(EdgeKind.S2S, e.kind)
    }

    @Test fun `unique suffix match with no signal still links (host config names no owner)`() {
        // tera-funding-stage host doesn't alias 'tera-cloud-user', but only one provider
        // exposes a path ending /v2/rankings/stocks → unambiguous, so link it.
        val out = run(
            caller("a"),
            external("a", "/v2/rankings/stocks"),   // no s2s, no hint
            controller("b", "/api/v2/rankings/stocks"),
        )
        val e = out.edges.single()
        assertEquals("b.Ctl#h", e.target)
        assertEquals(EdgeKind.S2S, e.kind)
    }

    @Test fun `ambiguous suffix match with no signal stays external`() {
        // two different projects both end with the call's trailing segments → ambiguous, don't guess.
        val g1 = CallGraph(
            listOf(caller("a"), external("a", "/x/y/v1/user/info", id = "ext:x#call")),
            listOf(edge("a.Svc#m", "ext:x#call")),
        )
        val g2 = CallGraph(listOf(controller("b", "/v1/user/info", id = "b.Ctl#h")), emptyList())
        val g3 = CallGraph(listOf(controller("c", "/y/v1/user/info", id = "c.Ctl#h")), emptyList())
        val out = CrossRun.combine(listOf(g1, g2, g3))
        assertEquals(EdgeKind.EXTERNAL, out.edges.single().kind)
    }

    @Test fun `single shared trailing segment is too weak to suffix-match`() {
        val out = run(
            caller("a"),
            external("a", "/api/v1/user/info", s2s = "b"),
            controller("b", "/info"),   // only 1 shared segment
        )
        assertEquals(EdgeKind.EXTERNAL, out.edges.single().kind)
    }

    @Test fun `feign-name hint corroborates suffix match`() {
        val out = run(
            caller("a"),
            external("a", "/api/orders/{}", service = "order-service"),
            controller("order-service", "/orders/{}"),
        )
        val e = out.edges.single()
        assertEquals("order-service.Ctl#h", e.target)
        assertEquals(EdgeKind.S2S, e.kind)
    }

    @Test fun `verb mismatch is not matched`() {
        val out = run(
            caller("a"),
            external("a", "/v1/users", verb = "POST", s2s = "b"),
            controller("b", "/v1/users", verb = "GET"),
        )
        assertEquals(EdgeKind.EXTERNAL, out.edges.single().kind)
    }

    @Test fun `s2sService wins tie among exact matches in different projects`() {
        // two providers expose the same path; the yml-resolved target should win.
        val g1 = CallGraph(
            listOf(caller("a"), external("a", "/v1/x", s2s = "c", id = "ext:x#call")),
            listOf(edge("a.Svc#m", "ext:x#call")),
        )
        val g2 = CallGraph(listOf(controller("b", "/v1/x", id = "b.Ctl#h")), emptyList())
        val g3 = CallGraph(listOf(controller("c", "/v1/x", id = "c.Ctl#h")), emptyList())
        val out = CrossRun.combine(listOf(g1, g2, g3))
        assertEquals("c.Ctl#h", out.edges.single { it.kind == EdgeKind.S2S }.target)
    }

    @Test fun `same deployable unit is not an s2s target`() {
        // provider in the SAME project as caller must not be retargeted.
        val out = run(caller("a"), external("a", "/v1/users", s2s = "a"), controller("a", "/v1/users"))
        assertEquals(EdgeKind.EXTERNAL, out.edges.single().kind)
    }
}
