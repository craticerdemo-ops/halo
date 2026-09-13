# Frontend Guidelines

`ui/` is the Vue 3.5 + TypeScript ~6.0 + TailwindCSS frontend: two Vue apps (console + user center) sharing `src/` and 6 pnpm workspace packages. Built with `vp` (vite-plus), not raw pnpm/vite.

## Structure

```
ui/
├── console-src/          # Console application (base /console/, alias @console)
│   ├── main.ts           # Vue app entry
│   ├── modules/          # Auto-loaded via import.meta.glob (module.ts → PluginModule)
│   └── ...
├── uc-src/               # User center application (base /uc/, alias @uc)
│   ├── main.ts           # Vue app entry
│   └── modules/          # Auto-loaded via import.meta.glob
├── src/                  # Shared code (alias @) — NOT an app entry
│   ├── vite/             # Build-time Vite helpers
│   ├── locales/          # i18n strings
│   ├── assets/           # Shared assets
│   └── ...
└── packages/             # pnpm workspace packages
    ├── api-client/       # @halo-dev/api-client — GENERATED (never hand-edit src/)
    │   ├── src/          # 100% generated (wiped + regenerated)
    │   └── entry/        # Hand-maintained (coreApiClient, consoleApiClient, etc.)
    ├── components/       # @halo-dev/components
    ├── editor/           # @halo-dev/richtext-editor (dir≠name)
    ├── shared/           # @halo-dev/ui-shared (dir≠name)
    ├── console-shared/   # @halo-dev/console-shared (deprecated)
    └── ui-plugin-bundler-kit/  # @halo-dev/ui-plugin-bundler-kit
```

## Module System

- UI modules: `console-src/modules/` or `uc-src/modules/` — each exports a `PluginModule` from `module.ts`.
- Auto-loaded at runtime via `import.meta.glob` — no manual registration needed.
- Follow existing component and composable patterns; match surrounding code.

## Conventions

- **`vp` (vite-plus) is canonical**, not raw pnpm/vite. `@voidzero-dev/vite-plus-core@0.3.0`. Uses Rolldown (`rolldownOptions`).
- TypeScript ~6.0.3 + `@typescript/native-preview`.
- `vue-router` pinned to `5.1.0` (exact, not 4.x).
- ESLint `--max-warnings=0` — fails on any warning.
- **Never hand-edit `ui/packages/api-client/src/`** — it is 100% generated (wiped + regenerated). The `entry/` directory IS hand-maintained.
- Keep dependency versions in `ui/package.json`; never hard-code versions.
- Package name ≠ directory name: `shared/` → `@halo-dev/ui-shared`; `editor/` → `@halo-dev/richtext-editor`.
- Payload or UX changes usually ship together with the backend change that defines the contract.
- Unit tests: `*.spec.ts` in `__tests__/` next to source; imports from `vite-plus/test` (not `vitest`).

## Commands

Run from the repo root:

```bash
pnpm -C ui install          # install dependencies
pnpm -C ui dev              # dev server (vite-plus, port 3000)
pnpm -C ui build            # production build
pnpm -C ui typecheck        # vue-tsc type checking
pnpm -C ui lint             # ESLint (--max-warnings=0)
pnpm -C ui format           # Prettier formatting
pnpm -C ui format:check     # Prettier check
pnpm -C ui test:unit        # Vitest (jsdom, *.spec.ts)
pnpm -C ui api-client:gen   # regenerate ui/packages/api-client/src/ (wipes src/ first)
```

## Boundaries

- New npm dependencies require approval.
- Never hand-edit `ui/packages/api-client/src/` — regenerate after backend contract changes (`./gradlew generateOpenApiDocs && pnpm -C ui api-client:gen`).
