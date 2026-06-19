package com.flowmap.callgraph

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Manifest emits graph-less project folders so the web app can load their siblings:
 *   - impact-only (wallga monorepo PR impact)
 *   - gateway-only (YAML config repo whose route table feeds the front→backend join)
 * A folder with NEITHER (e.g. only an openapi sibling) is not a project.
 */
class ManifestTest {
    private val dir: File = Files.createTempDirectory("manifest-test").toFile()
    private val mapper = ObjectMapper()

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun pfile(base: String, file: String, body: String) =
        File(dir, "projects/$base").also { it.mkdirs() }.let { File(it, file).writeText(body) }

    private fun projects(): List<Map<*, *>> {
        Manifest.write(dir, "_manifest.json")
        val root = mapper.readTree(File(dir, "_manifest.json").readText())
        return root["projects"].map { mapper.convertValue(it, Map::class.java) }
    }

    @Test
    fun `gateway-only repo (no graph) becomes a project with its gateway route table linked`() {
        pfile("apigateway-config-repo", "apigateway-config-repo.gateway.json",
            """{"command":"gateway","name":"apigateway-config-repo","routeCount":1,"routes":[{"routeId":"pension","publicPrefix":"/pension","backendPrefix":"/public"}]}""")
        val gw = projects().single { it["name"] == "apigateway-config-repo" }
        assertNull(gw["graph"])                                                   // no code graph
        assertEquals("projects/apigateway-config-repo/apigateway-config-repo.gateway.json", gw["gateway"])
    }

    @Test
    fun `impact-only repo still linked, and also carries gateway when both siblings exist`() {
        pfile("wallga", "wallga.impact.json", """{"pulls":[]}""")
        pfile("wallga", "wallga.gateway.json", """{"command":"gateway","name":"wallga","routeCount":0,"routes":[]}""")
        val w = projects().single { it["name"] == "wallga" }
        assertNull(w["graph"])
        assertEquals("projects/wallga/wallga.impact.json", w["impact"])
        assertEquals("projects/wallga/wallga.gateway.json", w["gateway"])         // regression: was hard-coded null
    }

    @Test
    fun `graph-less folder with neither impact nor gateway is not a project`() {
        pfile("docs-only", "docs-only.openapi.json", """{"openapi":"3.0.0"}""")
        assertTrue(projects().none { it["name"] == "docs-only" })
    }
}
