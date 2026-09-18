# Quicker Endpoints

Jump to the Spring controller method that serves a URL.

Paste a real URL out of a production log — `/api/v1/users/42/orders` — and it resolves against the
split template `@RequestMapping("/api/v1/users")` + `@GetMapping("/{id}/orders")`, which plain text
search can never find because the full path exists nowhere in the source.

Measured on a 666-subproject Spring monorepo: **5,727 endpoints**, scan ~6 s.

## Using it

**⌥⇧⌘H** on macOS, **Ctrl+Alt+Shift+H** elsewhere. Also `Navigate | Endpoint...`, and as an
`Endpoints` tab in Search Everywhere (Double Shift).

The search field accepts:

| Input | Example |
| --- | --- |
| A concrete URL from a log | `/api/v1/users/42/orders` — `42` matches `{id}` |
| A full URL | `http://host:8080/api/v1/users/42?trace=1` — scheme, host and query are stripped |
| A path template | `/api/v1/users/{id}` |
| A fragment of either | `users/{id}/orders`, `v1/users` |
| An HTTP verb prefix | `GET users` — filters, does not merely re-rank |
| Camel-hump on names | `usr ord` → `UserOrdersController.getOrders` |

`Enter` navigates, `Shift+Enter` opens in a split.

The model is built when a project opens, in the background once indexing has finished, so the first
search is instant. Results are served while a rebuild runs rather than blocking.

The model is also kept on disk between sessions, so on the next start search works immediately from
the previous results while a fresh scan replaces them behind it. Cached results are never treated as
truth — nothing can detect edits made while the IDE was closed — so the status line says when they
are from cache, and navigation re-resolves the target by class and method name rather than by stored
position.

**Settings → Tools → Quicker Endpoints**:

| Setting | Default | Effect |
| --- | --- | --- |
| Scan endpoints when a project opens | on | Off moves the scan to the first search — worth doing if you keep many projects open and rarely search most of them. |
| Keep the index on disk between sessions | on | Off means every session starts with an empty model and scans from scratch. |
| Rebuild the index when files change | **off** | There is no incremental update yet, so any edit invalidates the whole model and the next search would pay for a full rescan. While off, results go out of date as you edit — use **Rebuild Now**, or reopen the project. |

**Rebuild Now** rescans every open project, for after turning off automatic rebuilds or when
something looks out of date.

## What it understands

Beyond literal paths, which are the minority in practice:

- **Constant references** — `@RequestMapping(Api.BASE_PATH)`, Kotlin `companion object` constants
  reached through a static import, file-level `const val`, Java static imports. In the monorepo this
  was built against, ~91% of class-level paths are constant references rather than literals.
- **Concatenation** — `@PostMapping(APP_PATH + UPLOAD_PATH + "/start")`.
- **The interface/implementation split** — the path and verb usually live on an API interface in a
  *different Gradle module*, implemented by the `@RestController`; `override` methods carry no
  annotation of their own.
- **Custom meta-annotations** — any annotation transitively meta-annotated with a Spring mapping
  annotation, honouring `@AliasFor`. This is real Spring semantics, so a project's own annotation
  works without the plugin knowing its name.
- **`@HttpExchange`** — read server-side since Spring Framework 6.1.
- **Unresolved dependencies** — on a fresh clone or an unfinished Gradle import the Spring classes do
  not resolve; rather than reporting nothing, it falls back to matching annotations by simple name.

Excluded: `@FeignClient` and other outbound clients, and anything outside a `src/` directory
(compiler output under `bin/`, `build/`, `out/` often duplicates the sources).

## Building

Requires JDK 21. Everything else the Gradle wrapper fetches.

```bash
./gradlew buildPlugin     # -> build/distributions/quicker-<version>.zip
./gradlew test            # 57 tests, no IDE needed for most
./gradlew runIde          # sandbox IDE with the plugin loaded
./gradlew verifyPlugin    # binary compatibility against the supported IDE range
```

Resolving the IntelliJ Platform needs access to JetBrains' repositories. On a network that requires
a proxy, set it per machine — `GRADLE_OPTS="-Dhttps.proxyHost=… -Dhttps.proxyPort=…"` or
`~/.gradle/gradle.properties` — rather than in this repository.

## Installing

**Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart.

Built against IntelliJ Platform 2025.2 with `since-build=252` and no upper bound, so it loads in
2025.2 and anything newer, Community or Ultimate. It deliberately depends only on the Java plugin —
no Ultimate-only API — so a Community user gets the same behaviour.

## Distributing to a team

Sharing the zip works but nobody ever gets an update. A **custom plugin repository** costs one XML
file and gives colleagues the normal update flow:

1. Publish `quicker-<version>.zip` somewhere reachable — GitLab release asset, a raw Nexus
   repository, any static host.
2. Publish an `updatePlugins.xml` next to it:

   ```xml
   <plugins>
     <plugin id="com.xmitya.quicker.endpoints" url="https://.../quicker-0.1.0.zip" version="0.1.0">
       <idea-version since-build="252"/>
     </plugin>
   </plugins>
   ```

   `./gradlew generateUpdatePluginsXml -PpluginBaseUrl=https://...` writes it to
   `build/distributions/`.
3. Colleagues add that URL once under **Settings → Plugins → ⚙ → Manage Plugin Repositories…**.

From then on a new version is picked up like any other plugin update: bump `pluginVersion` in
`gradle.properties`, rebuild, replace both files.

For a wider release, JetBrains Marketplace takes the same zip. That path additionally wants a
`<vendor>` with a real URL, a changelog, and a clean `./gradlew verifyPlugin` run against every IDE
version in the supported range.
