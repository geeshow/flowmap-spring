package com.flowmap.callgraph

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Armeria exposes HTTP endpoints with `com.linecorp.armeria.server.annotation.Get`/`Post`,
 * whose jar is NOT on the analysis classpath. So K1 resolves `@Get` to a null fqName and the
 * resolved-descriptor annotation set silently drops "Get" — leaving the service unclassified
 * (CONTROLLER: 0) and any server-to-server call to its route dangling as an external API.
 *
 * The PSI fallback in [AnalysisSession] reads the annotation short name (and the mapping verb
 * + path, const refs included) straight from source, independent of classpath resolution.
 */
class ArmeriaEndpointTest {

    private fun write(root: File, rel: String, body: String) {
        val f = File(root, rel); f.parentFile.mkdirs(); f.writeText(body.trimIndent())
    }

    @Test fun `Armeria @Get on a @Service (annotation off-classpath) is a CONTROLLER endpoint`() {
        val root = Files.createTempDirectory("armeria").toFile()
        // NOTE: com.linecorp.armeria is intentionally NOT a test dependency, so `@Get` is an
        // unresolved annotation type here — exactly the real analysis condition.
        write(root, "proj/api/src/main/kotlin/com/x/KycHttpService.kt", """
            package com.x
            import org.springframework.stereotype.Service
            import com.linecorp.armeria.server.annotation.Get
            import com.linecorp.armeria.server.annotation.Post

            @Service
            class KycHttpService {
                companion object {
                    const val KYC_CDD_PATH = "/v1/kyc/cdd"
                }

                @Get(KYC_CDD_PATH)
                fun getKycCdd(): String = "cdd"

                @Post("/v1/kyc/sync")
                fun syncKyc(): String = "ok"
            }
        """)

        val ir = AnalysisSession().analyze(root.path, "proj", null, emptyMap())
        val graph = GraphBuilder(ir).build()

        val cdd = graph.nodes.single { it.id == "com.x.KycHttpService#getKycCdd" }
        assertEquals(Layer.CONTROLLER, cdd.layer)        // was SERVICE (annotation dropped) before the PSI fallback
        assertEquals("GET", cdd.httpMethod)
        assertEquals("/v1/kyc/cdd", cdd.endpoint)         // companion-const path resolves via binding context
        assertEquals(EntryPointKind.HTTP, cdd.entryPoint)

        val sync = graph.nodes.single { it.id == "com.x.KycHttpService#syncKyc" }
        assertEquals("POST", sync.httpMethod)
        assertEquals("/v1/kyc/sync", sync.endpoint)
    }
}
