package dev.localproxy.spring

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal client for Caddy's admin API — just enough to keep one reverse-proxy route
 * in sync with a running application.
 */
class CaddyAdminClient(
    private val adminUrl: URI,
    private val timeout: Duration,
    private val mapper: ObjectMapper = ObjectMapper(),
) {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** True if the admin API answers at all. */
    fun isReachable(): Boolean = runCatching { send("GET", "/config/", null).statusCode() < 500 }
        .getOrDefault(false)

    /**
     * Key of the server listening on [listenPort], e.g. 443 or 80.
     * Returns null when Caddy has no matching server — we do not guess, because posting an
     * https route into the :80 server would silently produce a URL that does not work.
     */
    fun findServer(listenPort: Int): String? {
        val body = send("GET", "/config/apps/http/servers/", null).body()
        if (body.isBlank() || body == "null") return null

        val servers = mapper.readTree(body)
        if (!servers.isObject || servers.isEmpty) return null

        servers.fields().forEach { (key, server) ->
            if (server.path("listen").any { it.asText().endsWith(":$listenPort") }) return key
        }
        return null
    }


    /**
     * Creates the route, or replaces it if a route with this `@id` already exists.
     * Existing routes are never duplicated, so restarts are safe.
     */
    fun upsertRoute(serverKey: String, routeId: String, host: String, port: Int) {
        val route = Route(
            id = routeId,
            match = listOf(Match(host = listOf(host))),
            handle = listOf(Handler(upstreams = listOf(Upstream(dial = "127.0.0.1:$port")))),
        )
        val json = mapper.writeValueAsString(route)

        val patch = send("PATCH", idPath(routeId), json)
        if (patch.statusCode() in 200..299) return

        // No route with that id yet — insert it in front of everything else.
        val post = send("POST", "/config/apps/http/servers/$serverKey/routes/0", json)
        check(post.statusCode() in 200..299) {
            "Caddy rejected the route (HTTP ${post.statusCode()}): ${post.body().take(300)}"
        }
    }

    /** Removes the route again. Missing routes are not an error. */
    fun deleteRoute(routeId: String) {
        send("DELETE", idPath(routeId), null)
    }

    private fun idPath(routeId: String) =
        "/id/" + URLEncoder.encode(routeId, StandardCharsets.UTF_8)

    private fun send(method: String, path: String, body: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(adminUrl.resolve(path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .method(
                method,
                body?.let { HttpRequest.BodyPublishers.ofString(it) }
                    ?: HttpRequest.BodyPublishers.noBody(),
            )
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    internal data class Route(
        @get:JsonProperty("@id") val id: String,
        val match: List<Match>,
        val handle: List<Handler>,
        val terminal: Boolean = true,
    )

    internal data class Match(val host: List<String>)

    internal data class Handler(
        val handler: String = "reverse_proxy",
        val upstreams: List<Upstream>,
    )

    internal data class Upstream(val dial: String)
}