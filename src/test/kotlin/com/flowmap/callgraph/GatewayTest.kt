package com.flowmap.callgraph

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gateway route parsing — focuses on the real Spring Cloud Gateway shape where a route
 * BOTH rewrites away its public prefix (RewritePath strips `/pension`) AND prepends a
 * backend prefix (PrefixPath adds `/public`).
 *
 * Front calls `/pension/v1/isa/conversions/available-time`; the backend controller serves
 * `/public/v1/isa/conversions/available-time`. So publicPrefix must be `/pension` and
 * backendPrefix must be `/public`.
 */
class GatewayTest {

    private val tmp = File.createTempFile("gw-routes", ".yml")

    @AfterTest
    fun cleanup() { tmp.delete() }

    private fun route(yaml: String): Gateway.Route {
        tmp.writeText(yaml)
        return Gateway.load(tmp.path, "sec-gw").single()
    }

    @Test
    fun `RewritePath strip + PrefixPath prepend yields public-prefix front and public-prefix back`() {
        val r = route(
            """
            spring:
              cloud:
                gateway:
                  routes:
                    - id: pension
                      uri: https://dev-happy-ending-api.kakaopayinvest.com
                      predicates:
                        - Path=/pension/**
                      filters:
                        - name: AuthenticationRouter
                        - RewritePath=/pension/?(?<segment>.*), /${'$'}{segment}
                        - PrefixPath=/public
            """.trimIndent(),
        )
        assertEquals("/pension", r.publicPrefix)
        // regression: PrefixPath used to be dropped, leaving backendPrefix="" (over-wiring)
        assertEquals("/public", r.backendPrefix)
        assertEquals("dev-happy-ending-api.kakaopayinvest.com", r.targetService)
    }

    @Test
    fun `RewritePath without PrefixPath still resolves to root backend`() {
        val r = route(
            """
            spring:
              cloud:
                gateway:
                  routes:
                    - id: user
                      uri: lb://tera-cloud-user
                      predicates:
                        - Path=/api/user/**
                      filters:
                        - RewritePath=/api/user/?(?<segment>.*), /${'$'}{segment}
            """.trimIndent(),
        )
        assertEquals("/api/user", r.publicPrefix)
        assertEquals("", r.backendPrefix)
        assertEquals("tera-cloud-user", r.targetService)
    }
}
