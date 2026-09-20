package me.raddatz.localproxy.spring

import me.raddatz.localproxy.spring.LocalProxyProperties.Scheme
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registers the running web server with a local Caddy instance once its port is known,
 * and removes the routes again on shutdown.
 *
 * Every failure is logged at WARN and swallowed: a missing proxy must never stop an
 * application from starting.
 */
class LocalProxyRegistrar(
    private val properties: LocalProxyProperties,
    private val client: CaddyAdminClient,
    private val applicationName: String?,
) : ApplicationListener<WebServerInitializedEvent>, DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)

    private val registeredRouteIds = CopyOnWriteArrayList<String>()

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        // Actuator on a separate port fires this event too — only the main server counts.
        if (event.applicationContext.serverNamespace != null) return

        val host = resolveHost() ?: run {
            log.warn("local-proxy: no hostname. Set 'spring.application.name' or 'local.proxy.host'.")
            return
        }
        val port = event.webServer.port
        if (port <= 0) {
            log.warn("local-proxy: web server reported port {}, skipping registration.", port)
            return
        }
        if (properties.schemes.isEmpty()) {
            log.warn("local-proxy: no schemes configured, skipping registration.")
            return
        }

        properties.schemes.forEach { scheme -> register(scheme, host, port) }
    }

    private fun register(scheme: Scheme, host: String, port: Int) {
        val routeId = routeId(scheme, host)
        try {
            val serverKey = properties.caddy.servers[scheme]
                ?: client.findServer(scheme.listenPort)
                ?: run {
                    log.warn(
                        "local-proxy: Caddy at {} has no server listening on :{}. " +
                            "App is reachable at http://localhost:{}",
                        properties.caddy.adminUrl, scheme.listenPort, port,
                    )
                    return
                }


            client.upsertRoute(serverKey, routeId, host, port)
            registeredRouteIds += routeId
            log.info("local-proxy: {}{} -> 127.0.0.1:{}", scheme.urlPrefix, host, port)
        } catch (ex: Exception) {
            log.warn(
                "local-proxy: could not register {}{} with Caddy at {} ({}). " +
                    "App is reachable at http://localhost:{}",
                scheme.urlPrefix, host, properties.caddy.adminUrl, ex.message, port,
            )
        }
    }

    override fun destroy() {
        val routeIds = registeredRouteIds.toList()
        registeredRouteIds.clear()
        routeIds.forEach { routeId ->
            runCatching { client.deleteRoute(routeId) }
                .onFailure { log.debug("local-proxy: could not remove route {}", routeId, it) }
        }
    }

    private fun routeId(scheme: Scheme, host: String) =
        "local-proxy-${scheme.name.lowercase()}-$host"

    private fun resolveHost(): String? {
        properties.host?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
        val name = applicationName?.let(::slugify)?.takeIf { it.isNotEmpty() } ?: return null
        return "$name.${properties.tld}"
    }

    /** `Order Service` -> `order-service`, so it survives as a DNS label. */
    private fun slugify(value: String): String = value
        .lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")
}