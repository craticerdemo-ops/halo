# Repository Guidelines

Halo is a full-stack monorepo: Java 21 / Spring Boot **4.1.1** WebFlux + R2DBC backend and a Vue 3.5 + TypeScript + TailwindCSS frontend. Work from the repo root and load **every relevant module guide** for the areas you touch.

**Generated:** 2026-08-31 | **Commit:** `58fbb339d` | **Branch:** `main`

## Modules

|       Module        |                           Path                            |
|---------------------|-----------------------------------------------------------|
| API library         | `api/` (extension model, contracts, security)             |
| Backend application | `application/` (services, routers, migrations, packaging) |
| Frontend            | `ui/` (console, user center, workspace packages)          |
| Application BOM     | `platform/application/` (shared dependency constraints)   |
| Plugin BOM          | `platform/plugin/` (plugin-facing constraints)            |

## Structure

```
halo/
├── api/                  # Contract-only: extension model (GVK/Scheme/Reconciler), domain extensions, plugin SPI, security abstractions
├── application/          # Runnable backend: ALL impls + PF4J plugin runtime, Thymeleaf theme engine, per-dialect migrations
│   ├── libs/             # Vendored patched Thymeleaf JARs (fork fixing #7289, loaded via flatDir)
│   └── src/main/resources/
│       ├── db/migration/{h2,mariadb,mysql,postgresql}/  # Per-dialect hand-written SQL migrations
│       ├── extensions/                                 # 34 YAML seed definitions (role-templates, extension-definitions)
│       └── templates/gateway_fragments/                # Server-rendered auth gateway UI (login/signup/2FA/OAuth2)
├── ui/                   # Two Vue apps (console-src + uc-src) sharing src/ + 6 pnpm workspace packages
│   └── packages/         # api-client (GENERATED), components, editor, shared, console-shared (deprecated), ui-plugin-bundler-kit
├── platform/application/ # BOM constraining backend deps (anchors on SpringBootPlugin.BOM_COORDINATES)
├── platform/plugin/      # BOM constraining plugin-facing deps (plugins compile against this + api/)
├── buildSrc/             # Groovy: halo.publish convention plugin + UploadBundleTask (Sonatype Central Portal)
├── api-docs/openapi/     # Generated OpenAPI v3 specs (5 groups: aggregated, public, console, uc, extension)
├── openspec/             # Spec-driven dev: 19 feature specs + changes/ (proposals required for API changes)
└── gradle/libs.versions.toml  # Version catalog (Spring Boot 4.1.1, lucene 10.5.0, resilience4j 2.4.0, pf4j 3.15.0)
```

## Where to Look

|                 Task                  |                                                      Location                                                       |                                       Notes                                        |
|---------------------------------------|---------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| Add a new extension (custom resource) | `api/.../extension/` (define `@GVK` POJO) + `application/.../infra/SchemeInitializer.java` (register + index specs) | Extension = data resource, NOT a Spring bean                                       |
| Add a reconciler                      | `application/.../core/reconciler/` — `@Component implements Reconciler<Request>`                                    | Auto-discovered by `DefaultControllerManager`; runs on virtual threads             |
| Add an API endpoint                   | `application/.../core/endpoint/{console,uc,theme}/` — split by delivery surface                                     | Mirrors OpenAPI groups: `apis_console`, `apis_uc`, `apis_public`, `apis_extension` |
| Add an auth mechanism                 | `application/.../security/authentication/` — `@Component implements SecurityConfigurer`                             | Applied in `@Order` by `WebServerSecurityConfig`                                   |
| Add a theme directive                 | `application/.../theme/dialect/` — Thymeleaf processor in `HaloProcessorDialect` (prefix `halo`)                    | Engine runs on `boundedElastic` (blocking tolerated)                               |
| Add a UI module                       | `ui/console-src/modules/` or `ui/uc-src/modules/` — `module.ts` exporting `PluginModule`                            | Auto-loaded via `import.meta.glob`                                                 |
| Change plugin lifecycle               | `application/.../core/reconciler/PluginReconciler.java`                                                             | CRD-driven: `Plugin.spec.enabled` toggles start/stop                               |
| Regenerate API client                 | `./gradlew generateOpenApiDocs && pnpm -C ui api-client:gen`                                                        | Wipes `ui/packages/api-client/src/`; `entry/` is hand-maintained                   |

## Code Map

|              Symbol               |              Type               |                          Location                           |                                        Role                                         |
|-----------------------------------|---------------------------------|-------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `Application`                     | `@SpringBootApplication`        | `application/.../Application.java:18`                       | Sole backend entry; scans `run.halo.app`, excludes `IntegrationAutoConfiguration`   |
| `console-src/main.ts`             | Vue app entry                   | `ui/console-src/main.ts`                                    | Console app; `@console` alias, base `/console/`                                     |
| `uc-src/main.ts`                  | Vue app entry                   | `ui/uc-src/main.ts`                                         | User center app; `@uc` alias, base `/uc/`                                           |
| `Extension` / `@GVK`              | Interface / Annotation          | `api/.../extension/Extension.java`, `GVK.java`              | Core abstraction: Kubernetes-style custom resource (group/version/kind)             |
| `SchemeManager`                   | Interface                       | `api/.../extension/SchemeManager.java`                      | Registers extension types + index specs; impl `DefaultSchemeManager`                |
| `ReactiveExtensionClient`         | Interface                       | `api/.../extension/ReactiveExtensionClient.java`            | Reactive CRUD+query for extensions; impl `ReactiveExtensionClientImpl`              |
| `ExtensionClient`                 | Interface                       | `api/.../extension/ExtensionClient.java`                    | Blocking twin — reconcilers only, never request paths                               |
| `Reconciler<R>`                   | Interface                       | `api/.../extension/controller/Reconciler.java`              | Level-triggered reconcile loop; `setupWith(ControllerBuilder)` declares watched GVK |
| `DefaultControllerManager`        | `SmartLifecycle`                | `application/.../extension/controller/`                     | Auto-discovers `Reconciler<Request>` beans, starts controllers                      |
| `HaloPluginManager`               | `DefaultPluginManager`          | `application/.../plugin/HaloPluginManager.java`             | PF4J plugin manager; per-plugin Spring `ApplicationContext`                         |
| `PluginReconciler`                | `Reconciler<Request>`           | `application/.../core/reconciler/PluginReconciler.java`     | CRD-driven plugin lifecycle (load/start/stop/unload/delete)                         |
| `WebServerSecurityConfig`         | `@Configuration`                | `application/.../infra/config/WebServerSecurityConfig.java` | `@EnableWebFluxSecurity`; SPI pattern via `SecurityConfigurer` beans                |
| `RequestInfoAuthorizationManager` | `ReactiveAuthorizationManager`  | `application/.../security/authorization/`                   | Kubernetes-style RBAC: `PolicyRule` matching (verbs, apiGroups, resources)          |
| `HaloViewResolver`                | `ThymeleafReactiveViewResolver` | `application/.../theme/HaloViewResolver.java`               | Per-theme cached Thymeleaf engine; finders as static vars                           |
| `TemplateEngineManager`           | `@Component`                    | `application/.../theme/TemplateEngineManager.java`          | LRU cache (5) of per-theme `HaloTemplateEngine`                                     |

## Commands

```bash
# Backend
./gradlew build                                              # full build + tests
./gradlew :application:bootRun                               # dev server (port 8090)
./gradlew :application:test                                  # backend tests (JUnit 5, H2 MySQL-mode)
./gradlew spotlessApply                                      # format Java/MD/JSON/properties (palantirJavaFormat)
./gradlew clean check --configuration-cache --configuration-cache-problems=warn  # exact CI test command

# Frontend (pnpm 11.17 / vp = vite-plus CLI)
pnpm -C ui dev                                               # dev server (vite-plus, port 3000)
pnpm -C ui build                                             # production build
pnpm -C ui typecheck                                         # vue-tsc typecheck
pnpm -C ui lint                                              # ESLint (--max-warnings=0, fails on any warning)
pnpm -C ui test:unit                                         # Vitest (jsdom, *.spec.ts)

# Code generation
./gradlew generateOpenApiDocs --no-configuration-cache       # generates api-docs/openapi/v3_0/ (boots app on port 8091)
pnpm -C ui api-client:gen                                    # regenerates ui/packages/api-client/src/ (wipes src/ first)
```

## Conventions (deviations from standard only)

- **Spring Boot 4.1.1** (not 3.x) — requires Spring milestone repo (`settings.gradle`); confirmed by `resilience4j-spring-boot4`.
- **Pure Java** (no Kotlin); Java 21 toolchain; `spring.threads.virtual.enabled: true`.
- **Spotless**: `palantirJavaFormat("2.90.0").formatJavadoc(true)` for Java, `dbeaver()` for SQL, `prettier()` for templates/static HTML/CSS/JS, `gson().sortByKeys()` for JSON, `flexmark` for markdown.
- **Frontend**: `vp` (vite-plus / `@voidzero-dev/vite-plus-core@0.3.0`) is the CLI, not raw pnpm/vite. Uses Rolldown (`rolldownOptions`), TypeScript ~6.0.3 + `@typescript/native-preview`. `vue-router` pinned to `5.1.0` (exact, not 4.x). ESLint `--max-warnings=0`.
- **Two-platform BOM model**: `platform/application` (backend) + `platform/plugin` (plugin-facing, chains application BOM + constrains `:api`). Consumers: `api platform(project(':platform:application'))`.
- **Package name vs directory mismatch**: `ui/packages/shared/` → `@halo-dev/ui-shared`; `ui/packages/editor/` → `@halo-dev/richtext-editor`.
- **Extension model**: Extensions are `@GVK`-annotated POJOs (not Spring beans), persisted as JSON in the `extensions` table, queried via in-memory `IndexEngine`. State split: `spec` (desired) vs `status` (observed). Status-only writes skip watcher fan-out.
- **Tests**: JUnit 5, `XxxTest` (unit) / `XxxIntegrationTest` (integration). Backend tests use H2 (`generate-unique-name: true`, `mode: always`); migration dirs are empty — schema via Spring SQL init + SchemeManager. Frontend: `*.spec.ts` in `__tests__/` next to source, imports from `vite-plus/test` (not `vitest`).

## Anti-Patterns (this project)

- **Never `.block()` in reactive request paths** — blocking is sanctioned ONLY in reconcilers, listeners, and the blocking-scheduler template engine via `ReactiveUtils.BLOCKING_TIMEOUT` (10s). See `application/AGENTS.md` → Conventions.
- **Never hand-edit `ui/packages/api-client/src/`** — it is 100% generated (wiped + regenerated). The `entry/` directory IS hand-maintained (constructs `coreApiClient`/`consoleApiClient`/etc.). See `ui/AGENTS.md` → Boundaries.
- **Never hard-code versions** — use `gradle/libs.versions.toml`, `gradle.properties`, or `ui/package.json`.
- **Never `git add -A`** — H2 test DB files and build outputs appear under `application/`. Stage specific files only.
- **Never commit secrets or introduce blocking I/O into reactive flows.**
- **Never auto-generate database migrations** — hand-write per-dialect SQL in `db/migration/{h2,mariadb,mysql,postgresql}/`. See `application/AGENTS.md` → Conventions.
- **Prefer 2.22+ APIs** — `ValueIndexSpec`/`Queries`/`Condition`/`IndexEngine`/`listAllNames` over deprecated `IndexSpec`/`QueryFactory`/`IndexedQueryEngine` (all `@Deprecated(forRemoval=true)`). Prefer `listBy`/`listAll` over deprecated paged `list`.
- **Ask before**: public API changes, new dependencies, database migrations, security configuration, CI workflow changes.

## Notes

- **CodeGraph index is broken**: `.codegraph` symlink resolves to an index scoped to `/home` (not `/home/halo`), with 161,589 unresolved references. `codegraph callers/callees` return empty. Run `codegraph init /home/halo` to re-index before relying on it.
- **`vp` vs `pnpm`**: AGENTS.md says `pnpm -C ui` but CI and package.json scripts use `vp` (vite-plus). Both work locally; `vp` is canonical.
- **Bundled plugins not in source**: `downloadPluginPresets` task fetches 8 plugin JARs at build time (includes non-OSS `appstore.jar` from halo.run). Build fails offline.
- **Vendored Thymeleaf**: `application/libs/` holds forked JARs loaded via `flatDir`, not Maven Central.
- **UI embedded in backend JAR**: `copyUiDist` inlines `ui/build/dist` into `application`'s resources.
- **Docker CDS pre-warm**: Dockerfile boots the app once during build to generate `application.jsa` (Class Data Sharing archive).
- **OpenSpec required**: functional/API changes must have a proposal at `openspec/` before implementation.
- **CLAUDE.md files**: are `@AGENTS.md` includes (auto-mirror); editing AGENTS.md updates both.

