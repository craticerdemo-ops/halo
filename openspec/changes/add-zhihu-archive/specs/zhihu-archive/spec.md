# Spec Delta

## Purpose

Preserve a point-in-time, self-hosted snapshot of a Zhihu article and its most-liked comments, including frozen engagement metrics and localized media, so the record survives deletion or moderation on Zhihu.

## ADDED Requirements

### Requirement: Browser-assisted capture

The system SHALL provide a capture client that runs in the user's authenticated Zhihu browser session, collects the article and comment payload, and submits it to Halo. The capture client SHALL NOT transmit Zhihu session cookies to Halo.

#### Scenario: Capture an article URL

- **WHEN** the user activates capture on a supported `zhuanlan.zhihu.com/p/{id}` page while logged in to Zhihu
- **THEN** the capture client SHALL collect the article body, author, original publish time, like count, comment count, and the selected comments
- **AND** it SHALL submit a normalized payload to the configured Halo endpoint using a Personal Access Token

#### Scenario: Unsupported URL type

- **WHEN** the user activates capture on a Zhihu URL that is not an article, answer, pin, or video
- **THEN** the capture client SHALL refuse to submit and SHALL report the supported URL types

#### Scenario: Zhihu session expired

- **WHEN** Zhihu responds to a capture request with a login or risk-control error
- **THEN** the capture client SHALL stop, report that the user must sign in to Zhihu, and SHALL NOT submit a partial payload

#### Scenario: Primary comment API returns no entries

- **WHEN** `comment_v5` returns `200` with an empty `data` array while the source reports a non-zero comment count
- **THEN** the capture client SHALL retry the legacy `/api/v4/<resource>/<id>/comments` list before selecting comments
- **AND** it SHALL fall back to `api.zhihu.com/comments/<rootId>/child_comments` for replies when the v5 child list is empty or the roots came from the legacy list
- **AND** if both channels return no root comments, it SHALL abort with `COMMENTS_UNAVAILABLE` instead of submitting a snapshot with a zero-length comment list

### Requirement: Capture payload contract

The system SHALL accept a versioned, normalized archive payload containing source identity, article fields, selected comments, asset references, and capture metadata. The payload SHALL be self-describing so the server does not need to read Zhihu.

#### Scenario: Valid payload

- **WHEN** an authorized caller submits a payload with a supported source type, a Zhihu source URL, article HTML, and comment entries
- **THEN** the system SHALL accept the payload and create a snapshot
- **AND** the stored snapshot SHALL retain the payload schema version

#### Scenario: Missing required fields

- **WHEN** a payload omits the source URL, source type, article HTML, or capture time
- **THEN** the system SHALL reject the request with a client error identifying the missing fields
- **AND** the system SHALL NOT create a partial snapshot

#### Scenario: Oversized payload

- **WHEN** a payload exceeds the configured maximum size or comment-entry count
- **THEN** the system SHALL reject the request with a client error
- **AND** the system SHALL NOT truncate silently

### Requirement: Source URL validation

The system SHALL accept source URLs only from supported Zhihu hosts and SHALL reject any other origin.

#### Scenario: Supported host

- **WHEN** the payload source URL uses `https` on `zhihu.com`, `www.zhihu.com`, or `zhuanlan.zhihu.com`
- **THEN** the system SHALL accept the source URL and store it verbatim as the canonical source

#### Scenario: Non-Zhihu or non-HTTPS URL

- **WHEN** the payload source URL uses another host, a non-HTTPS scheme, or a URL with embedded credentials
- **THEN** the system SHALL reject the request with a client error

### Requirement: Snapshot storage and provenance

Each snapshot SHALL be stored as a published `SinglePage` carrying an archive label and provenance metadata, and SHALL be immutable with respect to subsequent captures.

#### Scenario: Snapshot created

- **WHEN** a valid payload is accepted
- **THEN** the system SHALL create one `SinglePage` labeled as a Zhihu archive
- **AND** it SHALL record the source URL, source type, source id, capture time, content hash, author name and profile URL, original publish time, like count, and comment count
- **AND** it SHALL store the raw payload as an Attachment and reference it from the snapshot metadata

#### Scenario: Repeated capture does not mutate history

- **WHEN** a snapshot already exists for a source and the user captures the same source again
- **THEN** the system SHALL create a new snapshot with its own capture time and content hash
- **AND** the earlier snapshot SHALL remain unchanged

#### Scenario: Identical retry is idempotent

- **WHEN** the same payload with the same content hash is submitted twice for the same source
- **THEN** the system SHALL return the existing snapshot instead of creating a duplicate

### Requirement: Default public visibility with optional private mode

Every new snapshot SHALL default to `PUBLIC` visibility, and the create request SHALL accept an explicit `PRIVATE` value.

#### Scenario: Create request omits visibility

- **WHEN** a create request omits `visible`
- **THEN** the created `SinglePage` SHALL be `PUBLIC`
- **AND** it SHALL be reachable by anonymous visitors

#### Scenario: Create request asks for private visibility

- **WHEN** a create request sends `visible: "PRIVATE"`
- **THEN** the created `SinglePage` SHALL be `PRIVATE`
- **AND** it SHALL NOT be reachable by anonymous visitors, but SHALL remain accessible to its owner and administrators

#### Scenario: Unsupported visibility values

- **WHEN** a create request sends a visibility other than `PUBLIC` or `PRIVATE`
- **THEN** the request SHALL be rejected with a validation error

#### Scenario: Resubmission changes visibility

- **WHEN** an existing snapshot is resubmitted for the same source with a different `visible` value
- **THEN** its visibility SHALL be updated and the index SHALL be rebuilt
- **AND** its content, snapshot, and provenance metadata SHALL remain unchanged

#### Scenario: Explicit visibility change after creation

- **WHEN** the owner later changes visibility through normal `SinglePage` management
- **THEN** the snapshot content and provenance metadata SHALL remain unchanged

### Requirement: Snapshot rendering

The snapshot page SHALL render a provenance banner, the article body, and a comments section, with all engagement figures labeled as values captured at the snapshot time.

#### Scenario: Provenance banner

- **WHEN** a snapshot is rendered
- **THEN** the page SHALL show the original source link, author, original publish time, capture time, content hash, and the frozen like and comment counts
- **AND** it SHALL state that the data is a point-in-time archive and that copyright belongs to the original authors

#### Scenario: Article body

- **WHEN** the article body is rendered
- **THEN** the page SHALL show sanitized article HTML with localized media
- **AND** it SHALL NOT embed live Zhihu widgets, scripts, or iframes

#### Scenario: No live counters

- **WHEN** the page is viewed at any later time
- **THEN** like and comment counts SHALL remain the values stored at capture time
- **AND** the system SHALL NOT fetch updated counts from Zhihu

### Requirement: Comment selection and preservation

The snapshot SHALL contain at most 10 root comments selected by descending like count from the supplied comments, and SHALL contain every reply to each selected root comment up to 50 replies per root. Comments SHALL preserve author, timestamp, like count, IP location, author badge, collapsed state, and reply-to attribution.

#### Scenario: Top comments by like count

- **WHEN** the payload supplies more than 10 root comments
- **THEN** the snapshot SHALL include the 10 root comments with the highest like counts
- **AND** tied like counts SHALL be ordered by the capture client's supplied order

#### Scenario: Fewer than 10 root comments

- **WHEN** the payload supplies 10 or fewer root comments
- **THEN** the snapshot SHALL include all of them

#### Scenario: Replies capped per root

- **WHEN** a selected root comment has more than 50 replies in the payload
- **THEN** the snapshot SHALL include the first 50 replies in the supplied order
- **AND** it SHALL mark the thread as truncated and state how many replies were omitted

#### Scenario: Reply-to-reply attribution

- **WHEN** a reply targets another reply rather than the root comment
- **THEN** the snapshot SHALL render it on the same level as other replies
- **AND** it SHALL show which author it replies to

#### Scenario: Preserved fields

- **WHEN** a comment carries an IP location, author badge, like count, timestamp, or collapsed flag
- **THEN** the snapshot SHALL retain and render each supplied value

### Requirement: Asset localization

The system SHALL download referenced article images, comment images, avatars, and equation images into Halo attachment storage and SHALL render the local copy in the snapshot.

#### Scenario: Image localized

- **WHEN** an asset URL belongs to an allowlisted Zhihu media host and the download succeeds
- **THEN** the system SHALL store the bytes as an Attachment
- **AND** the snapshot SHALL reference the local attachment instead of the remote URL
- **AND** identical bytes SHALL reuse the existing attachment instead of uploading a second copy

#### Scenario: Fallback markup duplicates an image

- **WHEN** the source HTML wraps a fallback image in `noscript` next to the lazy-loaded image
- **THEN** the sanitizer SHALL drop the `noscript` element
- **AND** each figure SHALL render at most one image per distinct source URL

#### Scenario: Referer required by origin

- **WHEN** the media origin requires a `Referer` header
- **THEN** the system SHALL send `Referer: https://www.zhihu.com/` with the download request

#### Scenario: Asset download fails

- **WHEN** an asset is unavailable, exceeds the size limit, or is rejected by validation
- **THEN** the snapshot SHALL still be created
- **AND** the failing asset SHALL be rendered as a placeholder with its original URL
- **AND** the snapshot SHALL record the asset failure count

#### Scenario: Disallowed asset origin

- **WHEN** an asset URL points to a host that is not an allowlisted Zhihu media host
- **THEN** the system SHALL NOT download it
- **AND** it SHALL render the original link or a placeholder

### Requirement: Equation preservation

Equation images SHALL be preserved as readable text rather than as remote images.

#### Scenario: LaTeX equation captured

- **WHEN** the article contains an equation with a recoverable TeX source
- **THEN** the snapshot SHALL render the TeX source with the supported math markup
- **AND** it SHALL NOT depend on the Zhihu equation image endpoint

### Requirement: Video link retention and on-demand transfer

Videos SHALL be stored as their original link plus a localized poster, and SHALL be transferred to attachment storage only when the owner explicitly requests it.

#### Scenario: Snapshot keeps video link

- **WHEN** the captured article contains a video
- **THEN** the snapshot SHALL show the video title, a localized poster when available, and the original video link
- **AND** it SHALL NOT download the video during capture

#### Scenario: Owner transfers video

- **WHEN** the owner triggers transfer for a snapshot video
- **THEN** the system SHALL download the video into attachment storage and replace the page link with the local file reference
- **AND** it SHALL report the stored size and location

#### Scenario: Video transfer fails or is too large

- **WHEN** the video exceeds the configured limit or the download fails
- **THEN** the system SHALL keep the original link
- **AND** it SHALL report the failure without altering the rest of the snapshot

#### Scenario: Repeated transfer

- **WHEN** transfer is triggered for a video that was already transferred
- **THEN** the system SHALL return the existing local reference without downloading again

### Requirement: Archive index page

The system SHALL maintain a generated archive index `SinglePage` that lists snapshots newest first.

#### Scenario: Index updated on create

- **WHEN** a snapshot is created
- **THEN** the system SHALL regenerate the index page
- **AND** the index entry SHALL show title, author, original publish date, capture date, frozen like and comment counts, and a link to the snapshot

#### Scenario: Index updated on delete

- **WHEN** a snapshot is deleted through the archive management action
- **THEN** the system SHALL rebuild the index and the regenerated index SHALL omit the deleted snapshot
- **AND** when a snapshot is deleted through normal `SinglePage` management, the owner SHALL be able to regenerate the index from the console

#### Scenario: Theme independence

- **WHEN** the active theme is changed
- **THEN** the archive index and snapshot pages SHALL remain readable without theme-specific templates

### Requirement: Archive deletion and recapture

The system SHALL allow an authorized user to permanently delete a Zhihu archive snapshot, wait for Halo to clean up its versions, comments, and counters, and rebuild the archive index so the same source can be captured again.

#### Scenario: Delete an archive snapshot

- **WHEN** an authorized user deletes a page that carries the Zhihu archive label
- **THEN** the system SHALL permanently delete the `SinglePage` and wait for its cleanup to complete
- **AND** it SHALL rebuild the archive index without the deleted snapshot
- **AND** the archive list SHALL no longer show the deleted or terminating snapshot

#### Scenario: Reject deleting a non-archive or missing page

- **WHEN** the requested name does not exist or the page does not carry the Zhihu archive label
- **THEN** the system SHALL reject the request with a client error
- **AND** it SHALL NOT delete the page

#### Scenario: Recapture after deletion

- **WHEN** the same source is captured again after its archive was permanently deleted
- **THEN** the system SHALL create a new snapshot with a new `SinglePage` and re-rendered content
- **AND** it SHALL NOT reuse or resubmit the deleted snapshot

### Requirement: HTML sanitization

The system SHALL sanitize all supplied HTML before storing or rendering it.

#### Scenario: Unsafe markup removed

- **WHEN** a payload contains scripts, event-handler attributes, iframes, or unsupported active content
- **THEN** the system SHALL remove or neutralize it before storage
- **AND** the snapshot SHALL NOT execute the removed content when rendered

#### Scenario: Safe formatting preserved

- **WHEN** a payload contains paragraphs, links, images, lists, code blocks, blockquotes, emphasis, or tables
- **THEN** the system SHALL preserve those elements in the stored snapshot

### Requirement: Authorization and access control

Snapshot creation and management SHALL require authenticated, authorized callers.

#### Scenario: Personal Access Token accepted

- **WHEN** a caller presents a valid Personal Access Token with attachment upload and single-page management permissions
- **THEN** the system SHALL accept the capture request

#### Scenario: Anonymous request rejected

- **WHEN** a capture request has no credentials
- **THEN** the system SHALL reject it with an authentication error
- **AND** it SHALL NOT create a snapshot

#### Scenario: Insufficient permission

- **WHEN** an authenticated caller lacks the required permissions
- **THEN** the system SHALL reject the request with a forbidden error

### Requirement: Console archive management

The console SHALL provide a list of snapshots and actions to transfer videos, permanently delete snapshots, and rebuild the archive index.

#### Scenario: List snapshots

- **WHEN** an authorized user opens the archive management view
- **THEN** the console SHALL show snapshots with title, author, capture time, source type, and visibility
- **AND** each row SHALL link to the snapshot page

#### Scenario: Trigger video transfer from console

- **WHEN** the user selects the video transfer action for a snapshot that still references a remote video
- **THEN** the console SHALL call the transfer endpoint and show progress and the final result

#### Scenario: Rebuild index from console

- **WHEN** the user selects the rebuild action
- **THEN** the console SHALL regenerate the archive index and report completion

#### Scenario: Delete snapshot from console

- **WHEN** the user selects the delete action for a snapshot and confirms
- **THEN** the console SHALL permanently delete the archive, show progress, and refresh the list
- **AND** the action SHALL require the `system:zhihu-archives:manage` permission
