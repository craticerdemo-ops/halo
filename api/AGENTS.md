# API Library Guidelines

`api/` is the contract-only library: the extension model, domain extensions, plugin SPI, and security abstractions consumed by `application/` and by every plugin. No implementations live here — only interfaces, annotations, POJOs, and enums. Changes here affect downstream compatibility for all plugins.

## Structure

```
api/src/main/java/run/halo/app/
├── extension/          # Core extension model (Kubernetes-style custom resources)
│   ├── Extension.java, GVK.java, GroupVersionKind.java
│   ├── SchemeManager.java, ReactiveExtensionClient.java, ExtensionClient.java
│   ├── controller/     # Reconciler<Request>, ControllerBuilder, ControllerManager
│   ├── index/          # IndexEngine, ValueIndexSpec, Queries, Condition (2.22+ API)
│   ├── config/         # ExtensionConfiguration
│   └── store/          # ExtensionStore (JSON-persisted state)
├── core/               # Domain service contracts (content, user, attachment, etc.)
├── plugin/             # Plugin SPI: extension points, resources, settings
├── security/           # Security abstractions: AuthenticationProvider, etc.
├── theme/              # Theme contract types
└── migration/          # Migration-related contracts
```

## Key Abstractions

| Symbol | Role |
|--------|------|
| `Extension` / `@GVK` | Base interface + annotation for Kubernetes-style custom resources (group/version/kind). Extensions are POJOs, NOT Spring beans. |
| `SchemeManager` | Registers extension types + index specs. Impl: `DefaultSchemeManager` (in `application/`). |
| `ReactiveExtensionClient` | Reactive CRUD + query for extensions. Impl: `ReactiveExtensionClientImpl`. Use this in request paths. |
| `ExtensionClient` | Blocking twin of the reactive client. **Reconcilers only** — never in request paths. |
| `Reconciler<Request>` | Level-triggered reconcile loop. `setupWith(ControllerBuilder)` declares watched GVKs. Auto-discovered by `DefaultControllerManager`. |
| `IndexEngine` / `ValueIndexSpec` | In-memory index over extension fields (2.22+). Prefer over deprecated `IndexSpec`/`QueryFactory`/`IndexedQueryEngine`. |

## Conventions

- Java 21, 4-space indentation, `palantirJavaFormat` via Spotless.
- **Compatibility surface**: prefer additive changes. Flag any breaking signature or contract change for review before merging.
- Extension state is split: `spec` (desired) vs `status` (observed). Status-only writes skip watcher fan-out.
- Extension definitions and schemas live in `application/src/main/resources/extensions/` (34 YAML seed files); when you change them, update `application/` and `ui/` together.
- Tests: JUnit 5, `XxxTest` (unit) / `XxxIntegrationTest` (integration).
- `api platform(project(':platform:application'))` — the API module consumes the application BOM for dependency constraints.

## Commands

- `./gradlew :api:test` — run API library tests.
- `./gradlew spotlessApply` — format Java and Markdown.

## Boundaries

- **Public API changes require explicit approval** — treat them as affecting every plugin built against Halo.
- No implementation classes in `api/` — move concrete logic to `application/`.
- New shared dependencies require approval before merging.

