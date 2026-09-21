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
 * Boots a real web server on a random port and checks that the route Caddy receives
 * points at exactly that port.
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
        val body = StubCaddy.requests["PUT /config/apps/http/servers/srv0/routes/0"]?.single()
        assertTrue(body != null, "no route was inserted into Caddy")

        assertContains(body, """"@id":"local-proxy-https-order-service.localhost"""")
        assertContains(body, """"order-service.localhost"""")
        assertContains(body, """"dial":"127.0.0.1:$port"""")
    }

    @Test
    fun `removes the route on shutdown`() {
        registrar.destroy()
        assertTrue(
            StubCaddy.requests.keys.any {
                it == "DELETE /id/local-proxy-https-order-service.localhost"
            },
            "route should be deleted",
        )
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
                    respond(exchange, 200, """{"srv0":{"listen":[":443"]},"srv1":{"listen":[":80"]}}""")
                } else {
                    respond(exchange, 200, "")
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
