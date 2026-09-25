package me.raddatz.localproxy.spring

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
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
    @DefaultValue("false") val enabled: Boolean,

    /**
     * Full hostname to register, e.g. `api.shop.localhost`.
     * Defaults to `<spring.application.name>.<tld>`.
     */
    val host: String?,

    /**
     * TLD used when [host] is not set. `localhost` resolves to 127.0.0.1 without any
     * hosts-file entry on current macOS, Linux and Windows. Use `test` only if you run
     * something that manages DNS for it, e.g. dnsmasq.
     */
    @DefaultValue("localhost") val tld: String,

    /**
     * Which of Caddy's listeners to register with. Both by default — the app is then
     * reachable over http and https under the same hostname.
     */
    val schemes: Set<Scheme> = setOf(Scheme.HTTP, Scheme.HTTPS),

    /** Connect and read timeout for every call to the Caddy admin API. */
    val timeout: Duration = Duration.ofSeconds(2),

    @DefaultValue val caddy: Caddy,
) {
    enum class Scheme(
        /** Assumed listen port when Caddy's config does not name one. */
        val defaultPort: Int,
        /** Port a browser assumes for this scheme and therefore leaves out of a URL. */
        internal val implicitUrlPort: Int,
        internal val caddyPortKey: String,
    ) {
        HTTP(8080, 80, "http_port"),
        HTTPS(8443, 443, "https_port");

        val urlPrefix: String get() = name.lowercase() + "://"
    }

    data class Caddy(
        /** Caddy's admin endpoint. This is Caddy's own default; a plain `caddy run` needs no change. */
        @DefaultValue("http://localhost:2019") val adminUrl: URI,

        /**
         * Server keys in Caddy's config, per scheme, e.g. `https: srv0`.
         * Anything not listed here is discovered from the listener ports.
         */
        val servers: Map<Scheme, String> = emptyMap(),

        /**
         * Ports Caddy listens on, per scheme. When unset, read from Caddy's own
         * `http_port` / `https_port` settings, falling back to 8080 / 8443.
         */
        val ports: Map<Scheme, Int> = emptyMap(),
    )
}
