# Design

## Context

See `proposal.md` for motivation and `specs/zhihu-archive/spec.md` for behavior. Constraints established during exploration:

- Halo stores post/page content as opaque `raw` + rendered `content`; the backend has no Markdown renderer and no server-side sanitizer for page content (`ContentRequest`, `AbstractContentService`). Only comments are validated with Jsoup.
- `SinglePage` already provides content snapshots, publish flow, permalinks, labels, annotations, and `htmlMetas`.
- Zhihu blocks server-side access: article APIs require `x-zse-96`, HTML pages sit behind a `__zse_ck` JS challenge, and comments/answers return `40353 need_login`. Datacenter IPs are throttled (`40362`) within tens of requests.
- Halo has Personal Access Tokens (`PersonalAccessToken`, `PatAuthenticationManager`) and an SSRF-safe HTTP client (`HttpSecurityUtils.secureHttpClient()`).
- Existing attachment upload-from-URL (`AttachmentService.uploadFromUrl`) is reusable in principle but does not send a `Referer` header and caps responses at 10 MB.

## Goals / Non-Goals

**Goals:**

- Capture entirely in the user's browser session; the server never contacts Zhihu pages or APIs.
- Produce a self-contained, theme-independent archive page with provenance and frozen metrics.
- Keep the storage model simple enough to ship without a database migration or a new extension type.
- Make asset failures non-fatal and visible.

**Non-Goals:**

- Structured querying, diffing, or re-rendering of individual comments after capture.
- Native Halo comment integration.
- Automatic re-capture or deletion detection.
- Pixel-perfect visual fidelity to Zhihu; the archive is semantic HTML rendered by the active theme.

## Decisions

### D1: Store snapshots as `SinglePage` + label + annotations

Use an ordinary `SinglePage` for each snapshot, labeled `content.halo.run/zhihu-archive=true`, with provenance in annotations and the raw payload stored as an Attachment.

- **Why**: reuses content snapshots, publish flow, permalink generation, visibility, `htmlMetas`, and theme rendering with zero schema work. Any theme can display it.
- **Alternatives**:
  - *New `ZhihuArchive` GVK + reconciler*: better structured querying and re-rendering, but requires a public API change, migration, and a render pipeline. Deferred until a real need appears.
  - *`Post` instead of `SinglePage`*: puts archives into the article timeline and feeds, which is wrong for an archive.
- **Consequences**: comments live only as rendered HTML plus the raw JSON attachment; there is no per-comment query. The label is the only list index.

### D2: Capture via userscript using a Personal Access Token

Ship a userscript that runs on supported Zhihu pages, collects the payload with same-origin `fetch`, and posts it to Halo with `GM_xmlhttpRequest` and a PAT.

- **Why**: the page context supplies the logged-in cookies, the browser fingerprint, and Zhihu's own request-signing wrapper, so no signature port or cookie storage is needed. `GM_xmlhttpRequest` bypasses CORS without weakening Halo's CORS policy.
- **Alternatives**:
  - *Server-side fetch with user cookies*: rejected. Requires porting `x-zse-96`, minting `__zse_ck`, residential IPs, and stores session tickets; breaks monthly and violates Zhihu's terms.
  - *Chrome extension*: equivalent capability with host permissions, but adds store distribution and review. The payload contract is identical, so an extension can reuse it.
  - *Copy/paste JSON into the console*: viable fallback, but poor UX for comment capture.
- **Consequences**: the userscript is versioned separately from Halo and must report payload-schema mismatches clearly.

### D3: Render to semantic HTML with a scoped stylesheet

Generate article and comment markup as semantic HTML inside a `div.zhihu-archive`, with a small `<style>` block scoped to that class for the banner, comment tree, and placeholders.

- **Why**: Halo stores page content as HTML, and the theme applies its own typography; scoped styles keep the archive readable without theme changes.
- **Alternatives**:
  - *Iframe snapshot of the original DOM*: highest fidelity, but breaks search, accessibility, responsive layout, and theming.
  - *Theme template + finder*: cleaner separation, but requires every theme to add a template or a plugin to inject one.
- **Consequences**: a theme with aggressive CSS resets may still alter appearance; the scoped class limits collisions.

### D4: Content pipeline on the server

Accept HTML, sanitize it with a Jsoup allowlist, then localize assets before composing the page.

- **Sanitization allowlist**: reuse the comment allowlist (`Safelist.relaxed()` plus `s`, `code[class]`, `a[target]`, `preserveRelativeLinks`) and extend it with `figure`, `figcaption`, `table` elements, `img[src|alt|width|height]`, and `span[data-tex]`. Strip `style`, `class` (except allowlisted archive classes), event handlers, `iframe`, `script`, and `object`.
- **Why**: the server must not trust the capture client, and page content is not sanitized anywhere else in Halo.
- **Alternatives**: rely on the userscript to sanitize (rejected: server cannot trust the client); DOMPurify on render (rejected: content is stored and rendered by themes without a JS pipeline).

### D5: Asset localization with a dedicated download path

Download media server-side with `HttpSecurityUtils.secureHttpClient()`, an explicit `Referer: https://www.zhihu.com/`, redirects disabled, private-address blocking, a 10 MB per-asset cap, and a host allowlist (`*.zhimg.com` plus the Zhihu static hosts). Stream the response into `AttachmentService.upload(...)`.

- **Why**: `uploadFromUrl` cannot set `Referer` and is not host-restricted. Media is served from Zhihu's CDN, not the protected API, so a server-side CDN fetch is acceptable and avoids CORS limits in the page.
- **Alternatives**:
  - *Download in the browser and upload bytes*: avoids server egress but is limited by CORS on the CDN and increases userscript complexity.
  - *Leave remote URLs*: rejected; hotlinking breaks and defeats the archival purpose.
- **Consequences**: add an overload or a small internal downloader; do not widen `uploadFromUrl` for all callers without the same allowlist and header controls.
- **Equations**: prefer `span[data-tex]` / `img[src*="equation?tex="]` TeX recovery and render as text; only fall back to a localized equation image when no TeX source exists.
- **Avatars**: localize and fall back to an initial-letter placeholder.
- **Videos**: keep the remote link and localize the poster during capture; never download video bytes during capture.

### D6: Video transfer as an explicit, idempotent rewrite

Render each video as `<figure class="zhihu-archive-video" data-video-id="...">` containing the poster and an external link. A transfer endpoint downloads the video with the same secure client (larger configurable cap), stores it as an Attachment, and rewrites that figure in the page's head content, recording the attachment permalink in an annotation.

- **Why**: video files are large and often unwanted; opt-in keeps capture fast. Annotation-based idempotency prevents duplicate downloads.
- **Alternatives**: download during capture (rejected: slow, large); keep only links forever (rejected by the requirement).
- **Consequences**: rewriting page content creates a normal `SinglePage` snapshot revision; the archive is no longer byte-identical to the capture, but provenance metadata and the raw payload attachment remain unchanged.

### D7: Comment selection happens in the capture client

The userscript requests root comments with `order_by=score`, takes the first page, and selects the top 10 by `like_count`; it then requests all replies for those roots and sends at most 50 per root. The server enforces the same caps defensively.

If `comment_v5/<resource>/<id>/root_comment` returns `200` with `paging.totals > 0` but an empty `data` array (observed for some accounts), the client retries the legacy `/api/v4/<resource>/<id>/comments?order=normal&limit=20&status=open` list, and replies fall back to `api.zhihu.com/comments/<rootId>/child_comments` (no `order` parameter; the legacy endpoint returns empty when `order` is present). If both channels return no roots while the source reports comments, the capture aborts with `COMMENTS_UNAVAILABLE` instead of submitting an empty comment section.

- **Why**: fetching every root comment to compute a true global top 10 can mean thousands of requests and triggers risk control. `score` order plus a like-count sort on the first page is a good approximation and is transparent. The empty-`data` response is indistinguishable from "no comments" unless it is cross-checked against the reported count, and silently archiving 0 comments destroys the evidentiary value of the snapshot.
- **Alternatives**: full pagination (rejected: rate limits, latency); server-side selection (impossible without the data); treating an empty page as final (rejected after a real 123-comment answer produced an empty snapshot).
- **Consequences**: the archive is documented as "top 10 among the loaded candidate set", not a guaranteed global ranking. The fallback adds at most 3 root requests and one child request per selected legacy root; risk-control and network errors are never retried.

### D8: Archive index as a regenerated `SinglePage`

Maintain a `SinglePage` at a fixed slug whose HTML is regenerated by querying snapshots by label, newest capture first. Rebuild on create, on archive deletion, and on demand from the console.

- **Why**: no theme template or plugin required; works with any theme.
- **Alternatives**: a dynamic theme route with a finder (better for pagination/search, but requires theme or plugin work); a console-only list (does not satisfy the public archive list requirement).
- **Consequences**: deletion through the archive console rebuilds the index automatically; deletion through normal `SinglePage` management still requires the manual rebuild action. Concurrent builds are serialized with an in-process single-flight lock.

### D9: Visibility and metadata defaults

Default to `PUBLIC` visibility with an opt-in `PRIVATE` value on the create request, add `<meta name="robots" content="noindex">` via `htmlMetas`, and keep the archive out of feeds/sitemaps by virtue of `SinglePage` semantics.

- **Why**: the archive index is meant to be a public reading surface, so new snapshots should be reachable without an extra publishing step. Snapshots still contain third-party personal data (including IP location), so `PRIVATE` remains available per capture and is the recommended choice for sensitive content. Noindex keeps republished content out of search engines in both modes.
- **Consequences**: the public index lists only `PUBLIC` snapshots; `PRIVATE` snapshots stay discoverable through the console list. Resubmitting the same source with a different `visible` updates the existing snapshot's visibility and rebuilds the index, so switching modes does not require manual resource edits.

### D10: No new persistent schema

Use annotations, labels, and Attachments only.

- **Why**: avoids migrations across four dialects and keeps rollback trivial.
- **Consequences**: annotation values must stay small (identifiers, hashes, counts, attachment names); the raw payload and any large text go to Attachments. Implementation must verify the annotation size limit and reject payloads that exceed configured JSON limits.

### D11: Console module for management

Add a console module listing labeled `SinglePage`s with actions: open, transfer video, delete, rebuild index.

- **Why**: the owner needs a discoverable place to trigger the video transfer, permanently delete a bad snapshot for re-capture, and rebuild the index; the normal `SinglePage` list mixes archives with ordinary pages and has no archive-specific actions.
- **Alternatives**: inline buttons on the public page (mixes rendering with privileged actions, and private-page access is not a stable proxy for API authorization); row extensions on the existing list (theme/plugin coupling).
- **Consequences**: new OpenAPI client generation (including `DELETE`), i18n keys, and a small amount of UI code. Delete is guarded by a destructive confirmation and the manage permission.

### D12: Permanent delete for re-capture

Deleting an archive through the console permanently removes the labeled `SinglePage` and waits for Halo's finalizers to clean up its snapshot versions, comments, and counters before rebuilding the index. The API rejects names that are missing or not labeled as archives.

- **Why**: capture is idempotent by source and `create()` does not replace the content of an existing page, so a soft-deleted or surviving page would make a corrected re-capture silently return the old content. Permanent deletion is the only reliable reset.
- **Alternatives**: rewrite the existing page in place (rejected: breaks snapshot immutability and provenance); soft delete only (rejected: the page still blocks re-capture and leaves orphaned versions); edit the database directly (rejected: bypasses finalizers and leaves orphaned snapshots).
- **Consequences**: the action is irreversible and requires explicit confirmation; the endpoint polls for up to 5 seconds for the finalizer to finish; the index rebuild is best-effort so a failed rebuild does not report the already-completed delete as failed. Deleting through normal `SinglePage` management remains possible but does not rebuild the index automatically.

## Risks / Trade-offs

- **Zhihu DOM/API drift** → isolate all Zhihu-specific reads in one userscript module, pin the payload schema version, add fixture-based tests, and fail loudly with the offending step. This risk cannot be eliminated; the userscript is the maintenance boundary.
- **Terms-of-service and copyright exposure** → no server-side scraping, capture only content the user can already see, visibility chosen per capture (public by default, private opt-in), attribution and source link always rendered, noindex meta, explicit documentation that republishing comments requires each author's permission.
- **Personal data in snapshots (IP location, author identity)** → private opt-in, noindex, a console warning on creation, and documentation. A future render-time toggle can hide fields without deleting them.
- **Large pages and many assets** → cap payload size, asset count, per-asset size, and article HTML size; store raw payload as an Attachment; record asset failures instead of failing the snapshot.
- **SinglePage content rewriting for video transfer** → operate on head content under a lock, verify the target figure exists, and keep the raw payload untouched so the original capture remains verifiable.
- **Index rebuild races** → single-flight lock plus idempotent rebuild; last completed build wins.
- **PAT leakage** → store the token only in the userscript manager, recommend a dedicated token with minimal permissions, never log it, and document rotation.
- **Attachment storage growth** → document retention; videos are opt-in; expose stored sizes in the console result.
- **Sanitizer gaps** → use an explicit allowlist, unit-test dangerous inputs, and never allow arbitrary `style`/`class`/`iframe`.

## Migration Plan

1. Ship endpoints and console module behind the normal release; no schema migration.
2. Existing `SinglePage`s are unaffected; only pages with the archive label appear in the new console module and index.
3. Rollback: disable or remove the endpoints/UI. Created snapshots remain valid, readable `SinglePage`s; the label and annotations are inert. Optionally delete the generated index page.

## Open Questions

- Exact Zhihu media host set (the `*.zhimg.com` family plus static hosts) must be confirmed against captured payloads during implementation; the allowlist lives in configuration and can be widened without a spec change.
- Video size cap default is a configuration value; pick it during implementation based on available storage.
