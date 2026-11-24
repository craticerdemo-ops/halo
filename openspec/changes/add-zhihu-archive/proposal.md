# Proposal

## Why

Zhihu articles and their comment threads can be deleted or moderated without notice, and Zhihu offers no export. Authors and readers need a point-in-time, self-hosted snapshot that preserves the article body, the highest-liked comments, and the like counts as evidence. Halo should provide that archival surface without becoming a scraper: capture happens in the user's own logged-in browser, and the server only accepts an already-normalized payload.

## What Changes

- Add a browser-side capture contract, delivered as a userscript (and usable from an extension), that runs in the user's logged-in Zhihu tab and POSTs a normalized payload to Halo. Halo never fetches Zhihu pages or Zhihu APIs.
- Add an upload-authorized console API to create a snapshot from that payload. The API validates the source URL, sanitizes all HTML, localizes external assets, and creates the snapshot atomically.
- Store each snapshot as a `SinglePage` labeled for archival, with provenance annotations: source URL/type/source id, capture time, content hash, author, original publish time, like count, comment count, and a reference to the raw payload stored as an Attachment.
- Render snapshot content as a self-contained archive page: a provenance banner (source link, author, publish time, capture time, frozen like/comment counts, content hash), the article body, and a static comment section containing the **top 10 root comments by like count** plus all of their replies, capped at **50 replies per root comment**. Comments preserve author display name, original URL, timestamp, like count, IP location, author badge, collapsed state, and reply-to attribution.
- Default every snapshot to `PUBLIC` visibility, with an opt-in `PRIVATE` mode; the public index lists public snapshots only.
- Maintain a generated archive index page (`SinglePage` at a fixed slug) that lists snapshots by capture time with title, author, original publish date, frozen like/comment counts, and links.
- Localize article images, avatars, and equation images into Halo Attachments, downloading with the `Referer` header Zhihu's CDN requires. Keep videos as links only; expose an explicit "transfer video" action that downloads a video into attachment storage on demand.
- Add a minimal console module to browse archives and trigger video transfer and index rebuild.
- No plugin or theme API changes: snapshots are ordinary `SinglePage` resources, so any theme can render them.

**Non-goals**

- Server-side scraping, crawling, signature generation, or storage of Zhihu session cookies.
- Importing comments into Halo's native comment system.
- Periodic monitoring for deleted Zhihu content.
- Full comment pagination; only the top 10 root comments and their replies are archived.

## Capabilities

### New Capabilities

- `zhihu-archive`: browser-assisted capture contract, snapshot creation and storage, archive rendering with frozen engagement metrics, asset localization, archive index maintenance, and on-demand video transfer.

### Modified Capabilities

- None.

## Impact

- **Backend**: new console endpoint package under `application/src/main/java/run/halo/app/core/endpoint/console/`; reuse of `SinglePageService`, `AttachmentService`, `ReactiveExtensionClient`, and the SSRF-safe HTTP client. An attachment download path that accepts a `Referer` header may be added.
- **Frontend**: new console module under `ui/console-src/modules/` with list and actions; generated OpenAPI client update.
- **API**: new console endpoints; OpenAPI docs and `ui/packages/api-client` must be regenerated.
- **Data**: no schema migration. Snapshot data lives in `SinglePage` annotations/labels and in Attachments; raw payload is stored as a JSON attachment.
- **Security**: new endpoint requires upload/manage permissions and is reachable with a Personal Access Token; HTML is sanitized server-side with the same Jsoup allowlist used for comments; only `zhihu.com` / `zhuanlan.zhihu.com` source URLs and known image CDN hosts are accepted; outbound asset downloads use the SSRF-safe client with a response size cap.
- **Privacy**: IP location and author identity are stored because the archive is meant to be evidentiary; snapshots default to `PUBLIC` and can be marked `PRIVATE`. Public exposure of these fields remains the user's explicit choice, and documentation must call out the personal-information impact.
- **i18n**: new console strings in `ui/src/locales/` for both `zh-CN` and `en`.
- **Distribution**: the capture userscript is shipped as a static artifact and documented with PAT setup; it is not part of the Halo console bundle.
