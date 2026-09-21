# local-proxy-spring-boot-starter

[![Maven Central](https://img.shields.io/maven-central/v/me.raddatz/local-proxy-spring-boot-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/me.raddatz/local-proxy-spring-boot-starter)
[![CI](https://github.com/raddatzk/local-proxy-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/raddatzk/local-proxy-spring-boot-starter/actions/workflows/ci.yml)

Stop looking up which of your services took port 8080 today.

Add the dependency, start the app, and it is reachable at `https://<app-name>.localhost`.
The port is whatever the OS handed out — you never see it and never type it.

```
2026-09-20 11:42:07  INFO  m.r.l.s.LocalProxyRegistrar : local-proxy: https://order-service.localhost -> 127.0.0.1:54312
```

## How it works

1. `server.port=0` lets the kernel pick a free port. The application binds it immediately,
   so unlike "find a free port, then start", there is no window for another process to take it.
2. On `WebServerInitializedEvent` the real port is known. The starter inserts a
   `reverse_proxy` route at the front of the server's route list via Caddy's admin API,
   matching the hostname and dialling `127.0.0.1:<port>`.
3. The route carries a stable `@id`, so a restart replaces it instead of piling up duplicates,
   and shutdown removes it again.

Any Caddy works: a plain `caddy run`, [localias](https://github.com/peterldowns/localias),
or whatever else is already listening on 443.

## Setup

```kotlin
// build.gradle.kts of your service
dependencies {
    developmentOnly("me.raddatz:local-proxy-spring-boot-starter:<version>")
}
```

Released to Maven Central, so `mavenCentral()` is all the repository configuration you need.
Versions are `<major>.<commits on master>`, so they only ever count upwards — the current one is in
the badge above, and every one of them is on the
[releases page](https://github.com/raddatzk/local-proxy-spring-boot-starter/releases). The same
artifact is mirrored to GitHub Packages; see [RELEASING.md](RELEASING.md) to consume it from there.

`developmentOnly` keeps it off the runtime classpath of the built jar — the starter can never
run in production, regardless of configuration.

```yaml
# application-local.yml
server:
  port: 0
spring:
  application:
    name: order-service
```

That is the whole configuration. `.localhost` resolves to 127.0.0.1 without a hosts entry
on current macOS, Linux and Windows.

## Configuration

| Property | Default | |
|---|---|---|
| `local.proxy.enabled` | `true` | |
| `local.proxy.host` | *(derived)* | Full hostname, e.g. `api.shop.localhost` |
| `local.proxy.tld` | `localhost` | Used when `host` is unset |
| `local.proxy.timeout` | `2s` | Per call to the admin API |
| `local.proxy.caddy.admin-url` | `http://localhost:2019` | |
| `local.proxy.schemes` | `https` | `https`, `http`, or both |
| `local.proxy.caddy.servers` | *(auto)* | Server key per scheme, e.g. `https: srv0`. Discovered via listener port |
| `local.proxy.caddy.ports` | *(auto)* | Listen port per scheme. Read from Caddy's `http_port` / `https_port` |

The hostname defaults to `<spring.application.name>.<tld>`, slugified:
`Order Service` becomes `order-service.localhost`.

Subdomains work by setting `host` directly — `api.shop.localhost` and `docs.shop.localhost`
can be two separate services.

## http instead of https

```yaml
local:
  proxy:
    schemes: http          # or: [http, https]
```

The route then goes into the server listening on :80 and the log prints `http://...`.

No `automatic_https` surgery is needed either way:

- **`schemes: http`** — Caddy skips automatic HTTPS entirely for a server that listens only
  on the HTTP port, so no redirect is ever generated for your hostname.
- **`schemes: [http, https]`** — the hostname now lives in the :443 server too and does
  qualify, so Caddy adds a redirect on :80. Redirect routes are inserted *after* routes that
  carry a host matcher, and ours is a host-matched `terminal` route at index 0, so it still wins.

Note that port 80 is privileged just like 443, so this saves you no `sudo` — only the
certificate.

Under `.localhost` you keep secure-context APIs (service workers, `crypto.subtle`, clipboard)
even over plain http, because browsers treat `*.localhost` as trustworthy. Under a custom TLD
like `.test` you lose them, and `Secure` cookies stop working. That is the main reason the
default is https.

## Running Caddy

The starter attaches routes to an **existing** server, so Caddy needs at least one site
block per port. A Caddyfile with only global options creates no servers at all.
[`examples/Caddyfile`](examples/Caddyfile) is a working starting point:

```bash
brew install caddy
caddy run --config examples/Caddyfile
caddy trust                              # once, adds the local CA to the system store
```

`caddy reload` replaces the whole config and drops every route services registered —
they reappear on the next service restart. `caddy run --resume` restores the last
autosaved config, routes included.

Firefox keeps its own certificate store: install `nss` and re-run `caddy trust`, or set
`security.enterprise_roots.enabled` to `true`.

## Caddy on other ports

Set the ports in Caddy, not in the app — the starter reads them back:

```
{
    http_port  8080
    https_port 8443
    local_certs
}
```

The log then prints `https://order-service.localhost:8443`. Above 1024 Caddy needs no
`sudo`, which is the main reason to do this.

Set **both** values, even if you only use one. Caddy decides whether a server is plain HTTP
by comparing its listen port against `http_port`. A server on :8080 with `http_port` left at
80 counts as an HTTPS server, and Caddy will try to speak TLS on it.

## Failure behaviour

Registration is a convenience, not a dependency. If Caddy is down, misconfigured, or has no
HTTP server yet, the starter logs a WARN with the plain `http://localhost:<port>` URL and the
application starts normally. Nothing throws.

## Caveats

- **Actuator on a separate port** is ignored on purpose; only the main server is registered.
  Register the management port yourself if you want it.
- **TLS for `.localhost`** needs Caddy's internal CA. `localias` sets `local_certs` globally,
  which covers routes added later. With a hand-written Caddyfile, add `tls internal` or a
  matching automation policy, otherwise Caddy will try ACME and fail for the new hostname.
- **A custom TLD** (`.test`) needs DNS: localias or dnsmasq. `.localhost` needs nothing.
- **Service-to-service calls** still go to `localhost:<random>` unless the caller resolves
  through the proxy too. If your services talk to each other a lot, Docker Compose with
  Traefik labels and real service names is the better fit.
- **kill -9** leaves the route behind. The next start replaces it (same `@id`), so it
  self-heals — it just points at a dead port in between.

## Build

`./gradlew build` — Kotlin 2.2, Spring Boot 3.5, JDK 21 toolchain.

Group and major version live in `gradle.properties`; releases are numbered
`<major>.<commits on master>` by the release workflow. See [RELEASING.md](RELEASING.md).
