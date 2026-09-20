# Releasing

Artifacts go to **Maven Central** (`me.raddatz:local-proxy-spring-boot-starter`) and are mirrored
to **GitHub Packages**. Both are published by `.github/workflows/release.yml`, which is started by
hand and tags the released commit afterwards.

## One-time setup

### 1. Claim the `me.raddatz` namespace

On [central.sonatype.com](https://central.sonatype.com) → *Namespaces* → *Add Namespace*, enter
`me.raddatz`. The portal shows a verification key. Add it as a TXT record on the apex of
`raddatz.me`:

```
raddatz.me.  IN  TXT  "<verification-key>"
```

Then hit *Verify Namespace*. Once verified, every `me.raddatz.*` group is yours — this only has to
be done once, for all future libraries.

### 2. Portal token

*Account* → *Generate User Token*. It returns a username/password pair — these are the
`MAVEN_CENTRAL_*` secrets below, not your login.

### 3. Signing key

Central rejects unsigned artifacts.

```bash
gpg --full-generate-key                      # RSA 4096, no expiry is fine
gpg --list-secret-keys --keyid-format=long   # note the key id
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID>    # this block is the SIGNING_KEY secret
```

The key must be on a public keyserver, otherwise validation fails.

### 4. Repository secrets

*Settings* → *Secrets and variables* → *Actions*:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | portal token username |
| `MAVEN_CENTRAL_PASSWORD` | portal token password |
| `SIGNING_KEY` | armored private key from step 3, including the `-----BEGIN/END-----` lines |
| `SIGNING_KEY_PASSWORD` | passphrase of that key |

`GITHUB_TOKEN` is provided by Actions; the workflow already requests `packages: write`.

## Cutting a release

*Actions* → *Release* → *Run workflow*. Nothing to prepare, nothing to bump.

The version is `<major>.<commits on master>` — the major comes from `majorVersion` in
`gradle.properties`, the rest from `git rev-list --count HEAD`. Every new state of master is a new,
higher version on its own, and the number is the same no matter who releases it. Bump
`majorVersion` when you want the next release to be `1.x`; that is the only version edit there is.

Both registries are served by one Gradle run — Central first, because a release there cannot be
taken back while a failed GitHub Packages upload can simply be repeated. After that a separate job
tags the released commit `v<version>`. Only that job holds a token that can write to the
repository — the job holding the signing key and the Central token does not. The tag is created
through the API, so it starts no further workflow.

Two runs on the same commit would produce the same version, which Central refuses and cannot undo.
The workflow checks for the tag up front and stops before uploading anything.

The Central deployment is released automatically (`publishAndReleaseToMavenCentral`), because the
manual dispatch is already the deliberate step. To get a look at the staged deployment first, swap
the task in `release.yml` for `publishToMavenCentral` and press *Publish* on
[central.sonatype.com/publishing](https://central.sonatype.com/publishing) yourself.

It takes a few minutes before the artifact is on Maven Central, and up to a few hours before
search.maven.org lists it.

## Local checks

```bash
./gradlew publishToMavenLocal    # coordinates, POM, sources/javadoc jar
```

Local builds are `<major>.0-SNAPSHOT`; pass `-PreleaseVersion=1.2` to try a concrete one.

Signing is skipped locally unless `signingInMemoryKey` or `signing.keyId` is set, so this works
without a key. To sign locally, put them in `~/.gradle/gradle.properties`.

## Consuming from GitHub Packages

GitHub Packages requires authentication even for public packages:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/raddatzk/local-proxy-spring-boot-starter")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()   // PAT with read:packages
        }
    }
}
```

Maven Central needs none of that, so prefer it unless you have a reason not to.
