package me.raddatz.localproxy.spring

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Boots a real web server on a random port and checks that the routes Caddy receives
 * point at exactly that port — one per scheme, both by default.
 *
 * The stub reports `http_port` / `https_port` as 8080 / 8443 and names its servers the way
 * a real Caddy does with `examples/Caddyfile`: `srv0` is the HTTP one, `srv1` the HTTPS one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.application.name=Order Service"],
)
class LocalProxyRegistrarIT {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var registrar: LocalProxyRegistrar

    @Test
    fun `registers the running port with Caddy`() {
        val body = StubCaddy.requests["PUT /config/apps/http/servers/srv1/routes/0"]?.single()
        assertTrue(body != null, "no https route was inserted into Caddy")

        assertContains(body, """"@id":"local-proxy-https-order-service.localhost"""")
        assertContains(body, """"order-service.localhost"""")
        assertContains(body, """"dial":"127.0.0.1:$port"""")
    }

    @Test
    fun `registers both schemes by default`() {
        val body = StubCaddy.requests["PUT /config/apps/http/servers/srv0/routes/0"]?.single()
        assertTrue(body != null, "no http route was inserted into Caddy")

        assertContains(body, """"@id":"local-proxy-http-order-service.localhost"""")
        assertContains(body, """"dial":"127.0.0.1:$port"""")
    }

    @Test
    fun `reads the listen ports from Caddy`() {
        // Both are asked for, and the servers found are the ones on those ports.
        assertTrue("GET /config/apps/http/https_port" in StubCaddy.requests.keys)
        assertTrue("GET /config/apps/http/http_port" in StubCaddy.requests.keys)
    }

    @Test
    fun `removes the routes on shutdown`() {
        registrar.destroy()
        listOf("https", "http").forEach { scheme ->
            assertTrue(
                "DELETE /id/local-proxy-$scheme-order-service.localhost" in StubCaddy.requests.keys,
                "$scheme route should be deleted",
            )
        }
    }

    @SpringBootApplication
    class TestApp

    companion object StubCaddy {
        val requests = ConcurrentHashMap<String, MutableList<String>>()
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/config/apps/http/servers/") { exchange ->
                record(exchange)
                if (exchange.requestMethod == "GET") {
                    respond(exchange, 200, """{"srv0":{"listen":[":8080"]},"srv1":{"listen":[":8443"]}}""")
                } else {
                    respond(exchange, 200, "")
                }
            }
            // Caddy exposes these only when they are set explicitly; 404 otherwise.
            server.createContext("/config/apps/http/") { exchange ->
                record(exchange)
                when (exchange.requestURI.path.substringAfterLast('/')) {
                    "http_port" -> respond(exchange, 200, "8080")
                    "https_port" -> respond(exchange, 200, "8443")
                    else -> respond(exchange, 404, "")
                }
            }
            server.createContext("/id/") { exchange ->
                record(exchange)
                // Nothing is known yet, so PATCH misses (404, like real Caddy) and the
                // client falls back to inserting the route via PUT.
                val status = if (exchange.requestMethod == "PATCH") 404 else 200
                respond(exchange, status, "")
            }
            server.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun caddyUrl(registry: DynamicPropertyRegistry) {
            registry.add("local.proxy.caddy.admin-url") { "http://127.0.0.1:${server.address.port}" }
        }

        private fun record(exchange: HttpExchange) {
            val key = "${exchange.requestMethod} ${exchange.requestURI.path}"
            val body = exchange.requestBody.readBytes().decodeToString()
            requests.computeIfAbsent(key) { mutableListOf() }.add(body)
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }
}
