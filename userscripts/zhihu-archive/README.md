# Halo Zhihu Archive Userscript

A single-file Tampermonkey userscript that captures a Zhihu page in your own logged-in
browser and submits a versioned archive payload to Halo. It has no build step, no bundler,
no npm dependencies, and no server-side scraping.

- Script: [`zhihu-archive.user.js`](./zhihu-archive.user.js)
- Payload schema version: **1** (`schemaVersion: 1`)
- Client version: **1.0.7** (independent from the Halo server version)

## Supported URL types

| Page type |                            URL pattern                             |                     Zhihu API used                     |                Comments                 |
|-----------|--------------------------------------------------------------------|--------------------------------------------------------|-----------------------------------------|
| Article   | `https://zhuanlan.zhihu.com/p/<id>`                                | `js-initialData` → `/api/articles/<id>` → rendered DOM | `comment_v5/articles/<id>/root_comment` |
| Answer    | `https://www.zhihu.com/question/<qid>/answer/<aid>` (id = `<aid>`) | `/api/v4/answers/<aid>`                                | `comment_v5/answers/<aid>/root_comment` |
| Pin       | `https://www.zhihu.com/pin/<id>`                                   | `/api/v4/pins/<id>`                                    | `comment_v5/pins/<id>/root_comment`     |
| Video     | `https://www.zhihu.com/zvideo/<id>`                                | `/api/v4/zvideos/<id>`                                 | Not fetched (see limitations)           |

Any other page (including HTTP, other hosts, profile pages, and question pages without an
answer id) shows a disabled `Capture & send` button and an explanation.

Root comments are requested from `comment_v5/<resource>/<id>/root_comment` first. If that
endpoint returns `200` with an empty `data` array while the source still reports a non-zero
`comment_count` (observed for some accounts: `paging.totals: 123`, `data: []`), the script
retries the same page against the legacy `/api/v4/<resource>/<id>/comments` list
(`order=normal&limit=20&status=open`) before giving up. If both APIs return no root
comments, the capture aborts with `COMMENTS_UNAVAILABLE` instead of submitting a snapshot
that claims `0` comments. If a comment request instead fails at the network layer, the
script retries that single URL once through `GM_xmlhttpRequest` (which bypasses CORS); if the
comment APIs stay unreachable, it submits the capture with an empty comment list and reports
the failure as a warning, so the article itself is still archived. Child comments use the
same pattern
(`comment_v5/comment/<rootId>/child_comment` → `api.zhihu.com/comments/<rootId>/child_comments`).

The answer detail request appends an explicit `?include=` list (`content,excerpt,...`),
because Zhihu omits `content` and `excerpt` from `/api/v4/answers/<id>` unless they are
requested. Article and pin detail responses already carry their content.

Zhihu gates `www.zhihu.com/api/v4/articles/<id>` behind an `x-zse-96` request signature, so
it always answers `403` code `10003` in a browser page even when signed in. The userscript
therefore reads an article from the page itself, in this order: the SSR entity in
`script#js-initialData` (`initialState.entities.articles[<id>]`), then the column API
`https://zhuanlan.zhihu.com/api/articles/<id>`, then the rendered article DOM
(`.Post-RichText`, `.Post-Title`, `.AuthorInfo-name`, `.ContentItem-time`). A fallback is
reported as a warning in the panel; if all three sources are empty, capture aborts with the
original error. Answers, pins, and videos still use their `www.zhihu.com/api/v4/...`
endpoints.

## Installation

1. Install a userscript manager that supports `GM_xmlhttpRequest`, `GM_getValue`, and
   `GM_setValue` (for example Tampermonkey).
2. Create a new userscript and paste the full contents of `zhihu-archive.user.js`; save it.
   The metadata block already includes the four supported `@match` rules, `@connect *`,
   and `@run-at document-idle`.
3. Open a supported Zhihu page while signed in. A panel appears in the top-right corner.
4. Open **Settings** in the panel and enter:
   - Halo base URL, for example `https://blog.example.com` (no trailing path)
   - A Personal Access Token (masked, `type="password"`)
   - Attachment policy name used by Halo for the raw payload and localized media
   - Snapshot visibility: `PUBLIC` (default, listed on the public index page) or `PRIVATE`
     (owner and administrators only)
5. Click **Save**. Values are stored only through `GM_setValue`; the PAT is never written
   to the DOM, `console`, or the page's network log by this script.

The PAT is kept in userscript storage in plain text by the userscript manager. Use a
dedicated token, not an account password, and rotate it if the browser profile is shared.

## Dedicated PAT with minimal permission

Create a dedicated Personal Access Token in Halo for this userscript. Grant only the
permission required to create archives:

```text
system:zhihu-archives:manage
```

Do not reuse an administrator token and do not grant unrelated scopes. The script sends
the token only to the configured Halo origin, in the `Authorization: Bearer <PAT>` header,
when posting to:

```text
POST <haloBaseUrl>/apis/api.console.halo.run/v1alpha1/zhihu-archives
Content-Type: application/json

{ "payload": { ... }, "policyName": "<attachment policy name>", "visible": "PUBLIC" }
```

The server checks the token, validates the payload, sanitizes the HTML again, localizes
allowed assets, creates a `noindex` snapshot whose visibility comes from `visible`
(`PUBLIC` when the field is omitted), and returns a `SinglePage`. The panel shows a link
built from `metadata.name` and `status.permalink`. Re-sending the same source with a
different `visible` updates the existing snapshot and rebuilds the index. The snapshot
**content is not replaced**: if an archive was captured with the wrong/empty comment set,
delete it from the **Zhihu archives** console list and capture again. Deletion is
permanent and also rebuilds the index.

## Deleting and re-capturing

The **Zhihu archives** console list has a **Delete** action for users with
`system:zhihu-archives:manage`. Deleting is permanent: Halo removes the `SinglePage`, its
snapshot versions, comments, and counters, waits for cleanup to finish, and rebuilds the
archive index. The action asks for confirmation and cannot be undone.

Use it when a snapshot was created with wrong, empty, or incomplete comments. After the
delete completes, run **Capture & send** again on the same Zhihu page; Halo creates a new
snapshot instead of reusing the old one. The same operation is available as:

```text
DELETE <haloBaseUrl>/apis/api.console.halo.run/v1alpha1/zhihu-archives/{metadata.name}
Authorization: Bearer <PAT>
```

If the console button is unavailable, an administrator can delete the page from
**Pages** → **Recycle Bin** and then delete it permanently there; rebuild the archive
index afterwards from the **Zhihu archives** page.

## What is captured

- Title, author display name and profile URL, original publish time, like count, comment
  count, and the article HTML (or pin segments, or video poster/source link).
- `script`, `style`, `iframe`, `object`, `embed`, `form`, `link`, `meta`, and `base`
  elements are removed, inline event handlers and `javascript:` URLs are stripped, and
  lazy-load `data-original` image URLs are promoted to `src`. Halo sanitizes the HTML again
  server-side with its Jsoup allowlist.
- Up to **3 pages** of root comments are requested with `order_by=score`; if that API
  returns no entries while the page reports comments, up to 3 legacy pages of 20 are read
  instead. The archive keeps the **top 10 by `like_count`** with a stable ascending-`id`
  tie-break.
- Child comments are fetched from `comment_v5/comment/<rootId>/child_comment` (following
  `paging.next`), falling back to `api.zhihu.com/comments/<rootId>/child_comments` with
  `limit=20&offset=N` and **no** `order` parameter (the legacy endpoint returns an empty
  list when `order` is present). Replies are flattened so reply-to-reply entries stay at
  one level and capped at **50 replies per selected root**. `replyToAuthor` is recorded when
  Zhihu supplies it (including `reply_to_author.member` in the legacy shape), or derived
  from the parent reply for nested entries.
- Author name/profile URL, timestamp, like count, IP location, badge/identity text,
  collapsed state, and reply-to attribution are preserved verbatim. IP location is
  personal data and is intentionally kept because the archive is evidentiary; snapshots
  are `noindex`, and visibility defaults to `PUBLIC` (send `visible: "PRIVATE"` to keep a
  snapshot owner-only).
- Video capture keeps only the poster URL and source video URL. It never downloads video
  bytes. (Halo can transfer the video on demand from the console.)

## Payload contract (schema version 1)

The userscript emits exactly the fields of the backend DTOs in
`application/src/main/java/run/halo/app/zhihu/`:

```jsonc
{
  "schemaVersion": 1,
  "source": { "url": "https://...", "type": "ARTICLE|ANSWER|PIN|VIDEO", "id": "..." },
  "article": {
    "title": "...",
    "author": { "name": "...", "profileUrl": "https://..." },
    "publishedAt": "2026-01-01T00:00:00Z",
    "likeCount": 0,
    "commentCount": 0,
    "html": "<p>...</p>"
  },
  "comments": {
    "total": 0,
    "roots": [
      {
        "id": "...",
        "author": { "name": "...", "profileUrl": "https://..." },
        "contentHtml": "<p>...</p>",
        "likeCount": 0,
        "createdAt": "2026-01-01T00:00:00Z",
        "ipLocation": "IP 属地：...",
        "badge": "...",
        "collapsed": false,
        "replyToAuthor": null,
        "replies": []
      }
    ]
  },
  "capture": { "capturedAt": "2026-01-01T00:00:00Z", "clientVersion": "1.0.7" },
  "assets": { "items": [{ "url": "https://...", "type": "IMAGE|AVATAR|EQUATION|POSTER|VIDEO" }] }
}
```

Limits copied from `ZhihuArchiveLimits.java` are enforced before submission:

|      Field       |                   Limit                    |
|------------------|--------------------------------------------|
| Article HTML     | 2,000,000 characters                       |
| Comment HTML     | 20,000 characters                          |
| Title            | 500 characters                             |
| Author name      | 200 characters                             |
| URL              | 2,048 characters                           |
| Assets           | 1,000 entries                              |
| Root comments    | 200 (the client selects 10)                |
| Replies per root | 500 (the client sends 50)                  |
| Policy name      | 253 characters                             |
| Request body     | 10 MiB (`spring.codec.max-in-memory-size`) |

Titles, author names, comment ids, IP location, badges, and reply-to labels are truncated
with a warning where truncation is safe. Oversized article HTML, oversized body, or an
unknown schema version abort the submission. Warnings are attached to the payload as a
non-enumerable `warnings` property, so they never enter the wire JSON. There is no silent
adaptation of unknown shapes.

## Privacy and terms

- Capture happens only in your own authenticated browser session. The userscript does
  **not** send Zhihu cookies or session tickets to Halo; only the normalized payload is
  posted.
- Capture only content that is visible to you. Do not use the userscript to bypass a login
  wall, paywall, risk control, or access restriction.
- Snapshots are `noindex` `SinglePage` resources and are `PUBLIC` by default, so they are
  listed on the public index page. Choose `PRIVATE` visibility for content you do not want
  to publish, and do not make an archive containing third-party personal data (including IP
  location) public unless you have a lawful basis.
- Republishing comments requires the comment author's permission and must comply with
  Zhihu's terms and applicable copyright/privacy law. The archive index and any public
  snapshot are your responsibility.
- This is not a crawler. It performs a bounded number of requests for one page and stops;
  it does not retry automatically after failures.

## Limitations and deviations

- **Video comments are not fetched.** The documented `comment_v5` root endpoints cover
  `articles`, `answers`, and `pins`; the userscript does not invent a `zvideos` endpoint.
  A video payload therefore has `comments.roots: []` and preserves the reported comment
  count in `comments.total`.
- **Legacy comment fallback is bounded.** The legacy list caps `limit` at 20, accepts only
  `order=normal|reverse` (sending `order=by_vote` is rejected), and its child endpoint
  returns an empty list when any `order` parameter is present. The script therefore reads
  at most 3 legacy root pages and uses numeric offsets for legacy child pages. Legacy root
  entries do not carry a child-comment count, so replies for selected legacy roots are
  always probed. The legacy root list for articles may `404`; the script then tries the
  `api.zhihu.com` host once. Risk control (`40362`) is never retried; a network-level failure is tried once more through `GM_xmlhttpRequest` against the same URL, and HTTP-level errors are not retried.
- **Article signature.** `www.zhihu.com/api/v4/articles/<id>` always answers `403` code
  `10003` because it expects an `x-zse-96` signature that a page-context `fetch` does not
  add. The userscript does not mint signatures; it captures the article from the page's
  `js-initialData`, then the column API, then the rendered DOM. The article body therefore
  comes from what the signed-in page already displays, and the comment APIs remain
  unaffected. If a page is rendered without a readable body (login wall, risk control, or
  an SSR interstitial), the capture aborts and reports the failing step.
- **No truncation fields exist in the wire DTO.** `ZhihuComment` has no truncation flag
  fields. Truncation is reported in the panel/`selectComments` metadata and enforced by
  the client caps; the server re-applies its own caps defensively.
- **Extra grant.** The script declares `@grant unsafeWindow` (in addition to the required
  grants) so same-origin capture can use the page's `fetch`; it is not used for any
  privileged access.
- **Userscript versioning is separate from Halo.** A payload/schema mismatch is reported
  as `SCHEMA_MISMATCH` rather than silently adapted.
- **Manual steps not performed here.** The legacy v4 fallback was validated against a real
  legacy root-comment response (123 reported comments, 10 roots selected) and fabricated
  child-comment fixtures. The end-to-end Halo submission, clean-browser installation, and
  live re-capture after deleting an existing snapshot were not performed.

## Troubleshooting

### Comments show 0 while Zhihu shows many

Some accounts receive `200` from `comment_v5/<resource>/<id>/root_comment` with
`paging.totals: 123` and `data: []`. Client **1.0.5+** detects this (empty `data` plus a
non-zero `comment_count`), retries the legacy `/api/v4/<resource>/<id>/comments` list, and
shows `COMMENTS_UNAVAILABLE` if both channels are empty. If an older snapshot was already
created with 0 comments, use **Delete** in the **Zhihu archives** console list (or delete
the `SinglePage` from **Pages** → **Recycle Bin** and permanently remove it there) and
capture again — Halo does not replace the content of an existing snapshot.

### Comment APIs unreachable (NETWORK_ERROR at `comments:...`)

The body was captured, but Zhihu's comment endpoints were blocked at the network layer
(CORS, a redirect to a login/WAF page, or an offline connection). The script retries the
failing URL once through `GM_xmlhttpRequest`; if that also fails it submits the snapshot
with an empty comment list and lists the failing channel under **Warnings**
(`comment_v5: NETWORK_ERROR`, `legacy v4: NETWORK_ERROR`). Delete and re-capture later if
you need the comments. Risk control (`40362`) still aborts.

### Login wall / 40353

Sign in to Zhihu in the same tab, reload the page, then retry. A logged-out tab returns
`403` code `40353`. The script aborts without submitting partial data.

### Risk control / 40362

Zhihu rate-limits after repeated probing. Stop clicking, wait, and retry later. The script
does not retry automatically and does not loop over requests.

### Missing signature / 10003

`/api/v4/articles/<id>` is signature-gated. Version 1.0.6+ no longer depends on it: it reads
the SSR entity from `script#js-initialData`, then `/api/articles/<id>`, then the rendered
DOM. A successful fallback appears under **Warnings** in the panel. If the error is still
shown, all three sources were empty — reload the article tab while signed in, confirm the
body is visible, and retry. The reported step is `article:api`.

### Oversized payload

The panel shows `LIMIT_EXCEEDED` and the exact step/limit. Reduce the article size (very
large posts can exceed 2,000,000 HTML characters), reduce comments, or split the archive.
The script refuses instead of truncating the article body or sending partial data.

### Halo PAT errors

- `PAT_UNAUTHORIZED` (401): the token is invalid or expired. Create a new PAT.
- `PAT_FORBIDDEN` (403): the token lacks `system:zhihu-archives:manage`.
- `PAYLOAD_INVALID` (400/422): payload validation failed, often because the Halo server
  expects a different schema version.
- `ENDPOINT_NOT_FOUND` (404) whose response contains
  `Group/zhihu-archive was not found`: the running Halo jar predates the attachment-group
  seed (`extensions/zhihu-archive-group.yaml`). Redeploy/restart the server so the seed is
  applied; no userscript change is needed.

### PAT rotation

Create a new token with the same minimal permission, open the panel **Settings**, replace
the PAT, and click **Save**. Revoke the old token in Halo. The userscript stores only the
latest value via `GM_setValue`; it keeps no copy in the page.

## Exact manual verification checklist

These steps require a real Halo instance, a real Zhihu account, and a clean browser
profile. They were **not** executed in this environment.

1. Create a fresh browser profile and install only Tampermonkey.
2. In Halo, create a dedicated PAT with only `system:zhihu-archives:manage`; copy it once.
3. Install `zhihu-archive.user.js`. Confirm the Tampermonkey menu shows
   **Halo Zhihu Archive 1.0.7**.
4. Open an article (`/p/`), answer (`/question/.../answer/...`), pin (`/pin/`), and video
   (`/zvideo/`) while signed in. For each, confirm the panel detects the correct type and
   enables **Capture & send**.
5. Open an unsupported page (profile or question root). Confirm the button is disabled and
   an explanation is shown.
6. Save the Halo base URL, PAT, policy name, and visibility. Reopen the page and confirm the values
   persist. Inspect the PAT input type in DevTools and confirm it is `password`.
7. Click **Capture & send** on one page. Confirm the step text advances through source,
   comments, payload, and submit, and that no request retries after a failure.
8. Confirm the success panel links to the new snapshot via `metadata.name`/`status.permalink`.
9. Open the snapshot in Halo. Confirm: the visibility selected in Settings (`PUBLIC` by
   default), `noindex`, title, author,
   publish time, frozen like/comment counts, article HTML with images, at most 10 root
   comments, at most 50 replies per root, and preserved IP location/badge/collapsed state.
10. Submit the same capture twice and confirm the backend returns the existing snapshot
    (content-hash idempotency) rather than a duplicate.
11. Negative checks:
    - Wrong PAT → `PAT_UNAUTHORIZED`; under-permissioned PAT → `PAT_FORBIDDEN`.
    - Sign out of Zhihu → `ZHIHU_LOGIN_REQUIRED` / `40353`.
    - Simulate risk control → `ZHIHU_RISK_CONTROL` / `40362`; verify no retry.
    - Oversized article or comment set → `LIMIT_EXCEEDED`; verify no request is sent.
    - Disconnect the network → `NETWORK_ERROR`; verify no partial snapshot.
12. In DevTools, search the console and the page DOM for the PAT. Confirm it does not
    appear; only `GM_setValue` storage contains it. Confirm no Zhihu cookie header is sent
    to Halo.
13. Rotate the PAT, save the new value, revoke the old one, and confirm capture still
    succeeds.

## Tests

The userscript exposes `globalThis.__haloZhihuArchive` with
`detectPage`, `buildPayload`, `selectComments`, `submitPayload`, `mapError`,
`legacyRootCommentUrls`, `legacyChildCommentUrls`, `countCommentEntries`, and
`shouldUseLegacyComments` only when running under a test harness (Node). From the
repository root:

```bash
node --test userscripts/zhihu-archive/test
```

The suites use `node:test` and `node:assert/strict`, load fabricated fixtures from
`test/fixtures/`, and make no network calls. They cover URL detection, all four extraction
paths, comment selection/ties/pagination/truncation/reply flattening, legacy v4 root/child
mapping and fallback triggers, payload key parity against the DTOs, size-limit refusal, and
401/400/403/network/schema error mapping.
