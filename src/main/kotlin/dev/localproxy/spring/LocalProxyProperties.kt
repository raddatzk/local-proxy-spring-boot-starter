package dev.localproxy.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * Configuration for the local Caddy route registration.
 *
 * Nothing here is required. With `spring.application.name=orders` the app becomes
 * reachable at `https://orders.localhost` as soon as it has booted.
 */
@ConfigurationProperties("local.proxy")
data class LocalProxyProperties(

    /** Set to false to disable registration without removing the dependency. */
    val enabled: Boolean = true,

    /**
     * Full hostname to register, e.g. `api.shop.localhost`.
     * Defaults to `<spring.application.name>.<tld>`.
     */
    val host: String? = null,

    /**
     * TLD used when [host] is not set. `localhost` resolves to 127.0.0.1 without any
     * hosts-file entry on current macOS, Linux and Windows. Use `test` only if you run
     * something that manages DNS for it (localias, dnsmasq).
     */
    val tld: String = "localhost",

    /**
     * Which of Caddy's listeners to register with. Both is legal — the app is then
     * reachable over http and https under the same hostname.
     */
    val schemes: Set<Scheme> = setOf(Scheme.HTTP, Scheme.HTTPS),

    /** Connect and read timeout for every call to the Caddy admin API. */
    val timeout: Duration = Duration.ofSeconds(2),

    val caddy: Caddy = Caddy(),
) {
    enum class Scheme(val listenPort: Int) {
        HTTP(80),
        HTTPS(443);

        val urlPrefix: String get() = name.lowercase() + "://"
    }

    data class Caddy(
        /** Caddy's admin endpoint. Localias, Portless and a plain `caddy run` all use this default. */
        val adminUrl: URI = URI.create("http://localhost:2019"),

        /**
         * Server keys in Caddy's config, per scheme, e.g. `https: srv0`.
         * Anything not listed here is discovered from the listener ports.
         */
        val servers: Map<Scheme, String> = emptyMap(),
    )
}