# Tasks

## 1. Capture contract and content rules

- [x] 1.1 Add the versioned archive payload DTOs (source, article, comments, assets, capture) with required-field, size, and entry-count validation in a new `zhihu` package; verify with unit tests for valid, missing-field, and oversized payloads.
- [x] 1.2 Add source URL validation restricted to HTTPS `zhihu.com`, `www.zhihu.com`, and `zhuanlan.zhihu.com`, rejecting credentials, other hosts, and other schemes; verify with parameterized unit tests for each accepted and rejected case.
- [x] 1.3 Define archive label and annotation keys (source url/type/id, captured-at, content hash, author, published-at, like/comment counts, raw payload attachment, asset failures, video state) and a canonical-payload SHA-256 helper; verify the hash is stable across map ordering with unit tests.
- [x] 1.4 Extend the Jsoup allowlist for archive HTML (add `figure`, `figcaption`, `table` family, `img` attributes, `span[data-tex]`; strip `script`, `iframe`, `object`, event handlers, `style`, unapproved classes) and add unit tests for each dangerous and preserved element.
- [x] 1.5 Implement defensive comment normalization: top 10 roots by like count with stable tie-breaking, all replies up to 50 per root, reply-to flattening, truncation flags, and preserved author/timestamp/like/IP/badge/collapsed fields; verify with unit tests for over-cap, under-cap, ties, and reply-to-reply cases.

## 2. Asset localization pipeline

- [x] 2.1 Add a configurable Zhihu media host allowlist and a secure downloader using `HttpSecurityUtils.secureHttpClient()` with `Referer: https://www.zhihu.com/`, redirects disabled, private-address blocking, and a 10 MB cap; verify with a mock HTTP server covering success, 302, private host, oversized body, and disallowed host.
- [x] 2.2 Stream downloaded media into attachment storage with derived filename and detected media type, reusing `AttachmentService.upload(...)`; verify a stored attachment is returned with a permalink in an integration test.
- [x] 2.3 Convert recoverable equations from `span[data-tex]` and `equation?tex=` sources into text math, and render videos as `figure.zhihu-archive-video[data-video-id]` with localized poster and external link; verify with unit tests on representative HTML fixtures.
- [x] 2.4 Render placeholders with the original URL for failed or disallowed assets and record the failure count in annotations; verify that a snapshot still succeeds when one of several assets fails.

## 3. Snapshot API and rendering

- [x] 3.1 Add the console endpoint group for archives with `POST /zhihu-archives`, `GET /zhihu-archives`, `POST /zhihu-archives/{name}/transfer-video`, and `POST /zhihu-archives/index/rebuild`, including permission checks and OpenAPI annotations; verify route registration with endpoint tests and unauthenticated/forbidden cases.
- [x] 3.2 Create each snapshot as a `SinglePage` with the archive label, provenance annotations, `robots=noindex` html meta, and the raw payload stored as a JSON attachment; verify with an integration test that asserts the SinglePage, label, annotations, visibility, and attachment.
- [x] 3.2a Default snapshot visibility to `PUBLIC`, accept an optional `visible` of `PUBLIC` or `PRIVATE` on the create request, and sync visibility on resubmission; verify with tests for the default, explicit private, unsupported value, and promotion paths.
- [x] 3.2b List only `PUBLIC` snapshots in the generated index; verify with a test that a `PRIVATE` snapshot is absent from the rebuilt index.
- [x] 3.3 Implement content-hash idempotency: a duplicate payload for the same source returns the existing snapshot instead of creating another; verify with an integration test that submits the same payload twice and counts snapshots.
- [x] 3.4 Compose the snapshot HTML (banner with frozen metrics and hash, sanitized article, comment tree with truncation notes, footer disclaimer, scoped `<style>`); verify with golden-HTML assertions covering escaping, comment nesting, and the absence of live counters.
- [x] 3.5 Implement archive index regeneration by querying labeled `SinglePage`s newest-first, escaping all fields, and writing the fixed-slug index page under a single-flight lock; verify with an integration test that creates two snapshots and asserts both entries in order.
- [x] 3.6 Implement video transfer as an idempotent head-content rewrite guarded by the stored video annotation, with a configurable size cap; verify success, repeat-transfer, over-cap, and download-failure cases with tests.
- [x] 3.7 Regenerate OpenAPI docs and the API client, then verify the generated client compiles and exposes the new operations.
- [x] 3.8 Add `DELETE /zhihu-archives/{name}` with archive-label validation, permanent `SinglePage` deletion, finalizer-cleanup polling, and index rebuild; verify success, missing/non-archive rejection, and exclusion of deleted/terminating pages from the list with endpoint/service tests.

> Verification note: 3.2/3.3/3.5 are covered by mocked-client unit tests in `ZhihuArchiveServiceImplTest` (snapshot metadata, duplicate-payload idempotency, index ordering) rather than a full `@SpringBootTest`; real-context behavior is covered by the Group 6 end-to-end and security passes. 3.7 used `SPRING_SQL_INIT_PLATFORM=h2 ./gradlew generateOpenApiDocs --no-configuration-cache` because the local `application.yaml` overrides `spring.sql.init.platform` to `postgresql` while the `doc` profile runs on H2 memory. `create` now also triggers `rebuildIndex` after a new snapshot is created (best-effort: a rebuild failure is logged and does not fail the capture, since the snapshot is already durable and the console rebuild action remains the repair path); covered by `shouldRebuildIndexAfterCreatingSnapshot`.

## 4. Console management module

- [x] 4.1 Scaffold the console module with route, menu entry, and `zh-CN`/`en` locale keys; verify `pnpm -C ui typecheck` and `pnpm -C ui lint` pass.
- [x] 4.2 Build the archive list view showing title, author, source type, capture time, and visibility, with a link to each snapshot page; verify with component unit tests for populated and empty states.
- [x] 4.3 Add the transfer-video and rebuild-index actions with progress, success, and error feedback; verify with unit tests that mock success and failure responses.
- [x] 4.5 Add the delete action with a permanent-delete confirmation, progress, success/error feedback, permission guard, and list refresh; verify with component tests for success and failure.
- [ ] 4.4 Verify the module at narrow and wide viewports, including empty state and long titles; record screenshots as evidence.

## 5. Capture userscript

- [x] 5.1 Create the userscript with settings for Halo base URL and PAT (stored only in userscript storage), supported-URL detection for article/answer/pin/video, and a visible status panel; verify URL detection with fixture-driven tests.
- [x] 5.2 Implement article capture for each supported type, producing the versioned payload with title, author, publish time, like/comment counts, and sanitized-able HTML; verify against saved fixtures for each type.
- [x] 5.3 Implement comment capture using `order_by=score`, top 10 roots by like count, all replies capped at 50 per root, reply-to flattening, and preserved author/timestamp/like/IP/badge fields; verify with fixtures for pagination, truncation, and reply-to-reply.
- [x] 5.4 Implement submission with `GM_xmlhttpRequest` and explicit handling for login-wall, risk-control, oversized-payload, and network failures, aborting instead of sending partial data; verify with mocked responses and one manual submission.
- [x] 5.5 Document installation, dedicated PAT creation with minimal permissions, supported URL types, privacy/terms constraints, and payload-schema version; verify the documented steps work from a clean browser profile.
- [x] 5.6 Add a bounded comment fallback: when `comment_v5` returns an empty `data` array despite a non-zero comment count, retry the legacy `/api/v4` root list and `api.zhihu.com/comments/<rootId>/child_comments`; abort with `COMMENTS_UNAVAILABLE` when both channels are empty, and surface fallback/reply warnings in the panel. Verify with legacy fixtures (47 fixture-driven cases), a real legacy root-comment response (123 reported comments, 10 roots), and documented permanent-delete/re-capture behavior in the console archive list.

- [x] 5.7 Replace the signature-gated `www.zhihu.com/api/v4/articles/<id>` read with the page's `js-initialData` entity, then `zhuanlan.zhihu.com/api/articles/<id>`, then the rendered DOM, reporting fallbacks as warnings; verify with fixture-driven tests for the SSR shape, the DOM reader, and the page-state → API → DOM selection chain.
- [x] 5.8 Retry a network-blocked Zhihu request once through `GM_xmlhttpRequest`, and submit with an empty comment list plus a warning when the comment APIs stay unreachable (risk control still aborts); verify with stubbed transport and comment-channel tests.

> Verification note: Group 4 passed `corepack pnpm -C ui typecheck`, `corepack pnpm -C ui lint` and 6 scoped component tests. Group 5 passed 47 fixture-driven `node --test` cases, including legacy root/child mapping and fallback triggers; the fallback was also replayed against a real legacy root-comment response. Not executed here (require a real Halo instance, a real Zhihu account, and a clean browser profile): 4.4 screenshots, the one manual submission in 5.4, the clean-profile install check in 5.5, and the live re-capture in 5.6 after deleting an existing snapshot. The userscript intentionally does not fetch video comments because the documented `comment_v5` resources cover articles/answers/pins only; video comment totals are preserved with an empty root list.

> Delete verification note: `ZhihuArchiveServiceImplTest` (30 tests), `ZhihuArchiveEndpointTest` (16 tests), and the scoped `ZhihuArchiveList` suite (8 tests) pass; `corepack pnpm -C ui lint` and `typecheck` pass. Regenerating the OpenAPI spec required `SPRING_SQL_INIT_PLATFORM=h2`, and the generated client's `dist` had to be rebuilt before typecheck.

## 6. Integration verification

- [ ] 6.1 Run an end-to-end manual capture on a real Zhihu article: confirm snapshot page, localized images, top-10 comments, index entry, and the selected visibility (public by default, private when chosen); record request/response evidence and screenshots.
- [x] 6.2 Run backend verification: `./gradlew spotlessCheck` and `./gradlew :application:test --tests '*zhihu*'` with zero failures.
- [x] 6.3 Run frontend verification: `pnpm -C ui lint`, `pnpm -C ui typecheck`, and `pnpm -C ui test:unit` with zero warnings and failures.
- [ ] 6.4 Perform a security pass: attempt script/iframe injection, a non-Zhihu asset host, an oversized asset, a non-Zhihu source URL, an anonymous request, and an under-permissioned PAT; confirm each is rejected and no snapshot is created.

> Group 6 verification note: backend passed `:application:spotlessJavaCheck` plus 131 tests across `run.halo.app.zhihu.*` and `ZhihuArchiveEndpointTest` (0 failures/errors/skips); the root `spotlessCheck` still fails on pre-existing/generated markdown and OpenSpec skill files outside this change, so it was not applied globally. Frontend passed `corepack pnpm -C ui lint`, `typecheck`, and the full `test:unit` run (8 files/71 tests plus 18 files/161 tests); the generated host runtime snapshot `ui/packages/ui-plugin-bundler-kit/src/runtime-snapshots/halo-2.26.0.json` was regenerated with `snapshot:generate` to record the six new `@halo-dev/api-client` exports. Still not executed: 6.1 (real Zhihu article capture with request/response evidence) and 6.4's anonymous/under-permissioned-PAT checks, which need a live Halo instance; injection, non-Zhihu host, oversized asset, and non-Zhihu source URL are covered by unit tests.

## Workflow follow-up

- Ask the project owner to approve the public API addition before implementation starts.
- Archive the change after review and verification are complete.
