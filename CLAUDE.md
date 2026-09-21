# local-proxy-spring-boot-starter

Spring Boot starter that registers the running app with a local Caddy reverse proxy on
startup, so every service is reachable under a stable hostname
(`https://<spring.application.name>.localhost`) regardless of which port it got.

Kotlin 2.2, Spring Boot 3.5, JDK 21 toolchain. No runtime dependencies beyond what a Spring Boot web
app already has — the HTTP client is `java.net.http`.

## Commands

```bash
./gradlew build                                 # compile + tests
caddy run --config examples/Caddyfile           # local Caddy for manual testing
curl -s localhost:2019/config/apps/http/servers/ | jq 'map_values(.listen)'
```

## Structure

| File | Role |
|---|---|
| `LocalProxyProperties` | `@ConfigurationProperties("local.proxy")`, Kotlin data class with defaults |
| `CaddyAdminClient` | Thin client for Caddy's admin API: `listenPort`, `findServer`, `upsertRoute`, `deleteRoute` |
| `LocalProxyRegistrar` | Listens for `WebServerInitializedEvent`, registers one route per scheme, deletes them in `destroy()` |
| `LocalProxyAutoConfiguration` | Wires both beans; `@ConditionalOnWebApplication`, `@ConditionalOnClass(ObjectMapper)`, `local.proxy.enabled` |
| `META-INF/spring/...AutoConfiguration.imports` | Registers the auto-configuration |
| `META-INF/additional-spring-configuration-metadata.json` | IDE completion; keep in sync with the properties class by hand |
| `examples/Caddyfile` | Minimal working Caddy config, verified against a real Caddy |

## Flow

1. App runs with `server.port=0` — the kernel assigns a free port and Tomcat binds it
   immediately, so there is no race between picking and binding.
2. `WebServerInitializedEvent` → real port known. Events with a non-null `serverNamespace`
   (e.g. actuator on a management port) are ignored.
3. For each configured scheme: port = `local.proxy.caddy.ports[scheme]` ?: Caddy's
   `http_port`/`https_port` ?: 8080/8443. Server = `local.proxy.caddy.servers[scheme]` ?: the
   server whose `listen` ends in `:<port>`. No match → WARN and skip, never guess.
4. `PATCH /id/<routeId>` to update an existing route; on non-2xx, `PUT .../routes/0` to insert.
5. `destroy()` → `DELETE /id/<routeId>` for every registered route.

Route id: `local-proxy-<scheme>-<host>`. Stable across restarts, so re-registration replaces
instead of duplicating, and a `kill -9` self-heals on the next start.

## Invariants — do not break these

- **Every failure is a WARN, never an exception.** A missing or broken proxy must not stop
  the app from starting. The WARN always includes the plain `http://localhost:<port>` URL.
- **Insert with PUT, never POST.** Verified against Caddy: `POST .../routes/0` ignores the
  index and appends; `PUT .../routes/0` inserts at index 0. With POST our route lands behind
  the `*.localhost` catch-all (which is `terminal`) and never matches.
- **Do not touch server-level settings** (`automatic_https`, `skip` lists, listeners). Many
  services share one Caddy server; changing shared config from one service is out of scope,
  and read-modify-write on arrays races when services start concurrently.
- **Hostnames are slugified** (`Order Service` → `order-service`) to stay valid DNS labels.
- Intended to be consumed via Gradle `developmentOnly`, which is why `enabled` defaults to
  `true` (same model as DevTools).

## Verified Caddy behaviour

Checked against Caddy 2.6.2 via its admin API:

- `PATCH /id/<unknown>` → 404. `PATCH /id/<known>` → 200, replaces in place.
- `DELETE /id/<id>` removes the route.
- Server keys (`srv0`, `srv1`) are **not** ordered by port. With `examples/Caddyfile`,
  `srv0` is HTTP :8080 and `srv1` is HTTPS :8443.
- `http_port` / `https_port` are omitted from the config when left at 80 / 443. Since
  `Scheme.defaultPort` assumes 8080 / 8443, a Caddy on the privileged ports reads back as
  "no server found" — `local.proxy.caddy.ports` has to name 80 / 443 explicitly.
- A server that listens only on `http_port` gets no automatic HTTPS at all — no certs, no
  redirects. That is why `http_port` must match the real HTTP listener; otherwise Caddy
  treats it as an HTTPS server and speaks TLS on it.
- Automatic http→https redirect routes are inserted after host-matched routes and before a
  catch-all. Our route is host-matched, `terminal`, at index 0 — it wins.
- A Caddyfile with only global options creates no servers. At least one site block per port
  is required, or `findServer` returns null.
- `caddy reload` replaces the whole config and drops all registered routes.

## Known gaps

- Only a stubbed integration test (`LocalProxyRegistrarIT`). The stub does not model route
  order, which is how the POST/PUT bug slipped past it. A Testcontainers test against a real
  Caddy image would close that gap.
