# Backend Application Guidelines

`application/` is the runnable WebFlux + R2DBC backend: ALL implementations live here — services, routers, security, plugin runtime, theme engine, migrations, and packaging. Everything is reactive on virtual threads — never block request threads.

## Structure

```
application/src/main/java/run/halo/app/
├── Application.java              # @SpringBootApplication — sole entry; scans run.halo.app
├── core/
│   ├── endpoint/{console,uc,theme}/  # API endpoints split by delivery surface
│   ├── reconciler/               # Reconciler implementations (PluginReconciler, etc.)
│   └── extension/                # Extension service implementations
├── extension/                    # ReactiveExtensionClientImpl, DefaultSchemeManager, IndexEngine
│   └── controller/               # DefaultControllerManager (auto-discovers Reconciler beans)
├── plugin/                       # HaloPluginManager (PF4J), per-plugin Spring ApplicationContext
├── security/
│   ├── authentication/           # SecurityConfigurer implementations (@Order)
│   ├── authorization/            # RequestInfoAuthorizationManager (K8s-style RBAC)
│   └── exception/                # Exception translation chain
├── theme/
│   ├── HaloViewResolver.java     # Per-theme ThymeleafReactiveViewResolver (cached)
│   ├── TemplateEngineManager.java # LRU cache (5) of per-theme HaloTemplateEngine
│   └── dialect/                  # HaloProcessorDialect (prefix "halo")
├── infra/config/                 # WebServerSecurityConfig, SchemeInitializer
└── content/                      # Content/post/page service implementations
```

Resources:
- `src/main/resources/application*.yaml` — environment configuration.
- `src/main/resources/db/migration/{h2,mariadb,mysql,postgresql}/` — per-dialect hand-written SQL migrations.
- `src/main/resources/extensions/` — 34 YAML seed definitions (role-templates, extension-definitions).
- `src/main/resources/templates/gateway_fragments/` — server-rendered auth gateway UI (login/signup/2FA/OAuth2).
- `libs/` — vendored patched Thymeleaf JARs (fork fixing #7289, loaded via `flatDir`).

## Key Subsystems

|     Subsystem     |                 Entry Point                  |                                                       Notes                                                        |
|-------------------|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| Extension runtime | `DefaultControllerManager`                   | `SmartLifecycle`; auto-discovers `Reconciler<Request>` beans, runs on virtual threads                              |
| Plugin system     | `HaloPluginManager`                          | PF4J `DefaultPluginManager`; CRD-driven lifecycle via `PluginReconciler` (`Plugin.spec.enabled`)                   |
| Security          | `WebServerSecurityConfig`                    | `@EnableWebFluxSecurity`; SPI via `SecurityConfigurer` beans applied in `@Order`                                   |
| RBAC              | `RequestInfoAuthorizationManager`            | K8s-style: `PolicyRule` matching (verbs, apiGroups, resources)                                                     |
| Theme engine      | `HaloViewResolver` + `TemplateEngineManager` | Per-theme cached Thymeleaf; runs on `boundedElastic` (blocking tolerated via `ReactiveUtils.BLOCKING_TIMEOUT` 10s) |

## Conventions

- **Never `.block()` in reactive request paths** — blocking sanctioned ONLY in reconcilers, listeners, and the blocking-scheduler template engine.
- Database migrations are hand-written per dialect — never auto-generate. Review with all 4 dialect dirs.
- After DTO, route, or schema changes, regenerate the UI client: `./gradlew generateOpenApiDocs && pnpm -C ui api-client:gen`.
- Tests: JUnit 5 with Spring Boot test support; `XxxTest` (unit) / `XxxIntegrationTest` (integration). H2 in MySQL-mode (`generate-unique-name: true`, `mode: always`); migration dirs are empty — schema via Spring SQL init + SchemeManager.
- `spring.threads.virtual.enabled: true` — virtual threads for all blocking-justified paths.

## Commands

- `./gradlew :application:test` — run backend tests (H2 MySQL-mode).
- `./gradlew :application:bootRun` — start the dev server (port 8090).
- `./gradlew :application:copyUiDist` — embed built UI into the JAR.
- `./gradlew generateOpenApiDocs --no-configuration-cache` — emit OpenAPI spec (boots app on port 8091).
- `./gradlew clean check --configuration-cache --configuration-cache-problems=warn` — exact CI test command.

## Boundaries

- Ask before adding database migrations, changing security configuration, or introducing new dependencies.
- Never `git add -A` — H2 test DB files and build outputs appear under `application/`. Stage specific files only.

