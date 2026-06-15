package com.flowmap.callgraph

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check that `.java` sources are analyzed (not just resolved as symbols):
 * a Java controller + service in a multi-module layout produce nodes, a controller->
 * service internal edge (resolved by the field's declared type), HTTP endpoints, and
 * an HTTP entry-point classification — with per-module provenance.
 */
class JavaAnalysisTest {

    private fun write(root: File, rel: String, body: String) {
        val f = File(root, rel); f.parentFile.mkdirs(); f.writeText(body.trimIndent())
    }

    @Test fun `java controller and service are analyzed with an internal edge`() {
        val root = java.nio.file.Files.createTempDirectory("javatest").toFile()
        val api = "proj/web-api/src/main/java/com/x"
        val core = "proj/core/src/main/java/com/x"

        write(root, "$api/UserController.java", """
            package com.x;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/users")
            public class UserController {
              private final UserService userService;
              public UserController(UserService userService) { this.userService = userService; }
              @GetMapping("/{id}")
              public String get(@PathVariable long id) { return userService.find(id); }
            }
        """)
        write(root, "$core/UserService.java", """
            package com.x;
            import org.springframework.stereotype.Service;
            @Service
            public class UserService {
              public String find(long id) { return "u" + id; }
            }
        """)

        val ir = AnalysisSession().analyze(root.path, "proj", null, emptyMap())
        val graph = GraphBuilder(ir).build()

        // language tagged + per-module provenance
        assertTrue(ir.all { it.language == "java" })
        assertEquals(setOf("web-api", "core"), ir.mapNotNull { it.module }.toSet())

        val get = graph.nodes.single { it.id == "com.x.UserController#get" }
        assertEquals(Layer.CONTROLLER, get.layer)
        assertEquals("GET", get.httpMethod)
        assertEquals("/users/{id}", get.endpoint)
        assertEquals(EntryPointKind.HTTP, get.entryPoint)
        assertEquals("web-api", get.module)

        val find = graph.nodes.find { it.id == "com.x.UserService#find" }
        assertNotNull(find, "service method should be a node")
        assertEquals(Layer.SERVICE, find.layer)
        assertEquals("core", find.module)

        // controller -> service edge, resolved via the `userService` field's declared type
        assertTrue(
            graph.edges.any { it.source == "com.x.UserController#get" && it.target == "com.x.UserService#find" },
            "expected a controller->service internal edge",
        )
    }
}
