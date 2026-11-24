package run.halo.app.zhihu;

import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.equal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.extension.router.selector.LabelSelector;
import run.halo.app.infra.exception.DuplicateNameException;
import run.halo.app.zhihu.ZhihuNormalizedComments.ZhihuCommentThread;

/** Default {@link ZhihuArchiveService} implementation backed by {@link SinglePage} snapshots. */
@Service
public class ZhihuArchiveServiceImpl implements ZhihuArchiveService {

    static final String PAYLOAD_GROUP_NAME = "zhihu-archive";

    static final String INDEX_MARKER_ANNO = "zhihu-archive.halo.run/index-page";

    static final String INDEX_MARKER_VALUE = "true";

    private static final Logger log = LoggerFactory.getLogger(ZhihuArchiveServiceImpl.class);

    private static final String SNAPSHOT_RAW_TYPE = "HTML";

    private static final String LINK_REL = "nofollow noopener noreferrer";

    private static final String VIDEO_FIGURE_SELECTOR = "figure.zhihu-archive-video";

    private static final Duration DELETE_POLL_INTERVAL = Duration.ofMillis(100);

    private static final int DELETE_POLL_ATTEMPTS = 50;

    private static final Comparator<SinglePage> NEWEST_FIRST = (left, right) -> {
        var leftTime = creationTimestamp(left);
        var rightTime = creationTimestamp(right);
        if (leftTime == null && rightTime == null) {
            return 0;
        }
        if (leftTime == null) {
            return 1;
        }
        if (rightTime == null) {
            return -1;
        }
        return rightTime.compareTo(leftTime);
    };

    private final ReactiveExtensionClient client;

    private final AttachmentService attachmentService;

    private final ObjectMapper objectMapper;

    private final ZhihuHtmlSanitizer sanitizer;

    private final ZhihuCommentNormalizer commentNormalizer;

    private final ZhihuAssetHtmlRewriter assetRewriter;

    private final ZhihuAssetLocalizer assetLocalizer;

    private final ZhihuPayloadHasher hasher;

    private final ZhihuArchiveRenderer renderer;

    private final Object indexLock = new Object();

    public ZhihuArchiveServiceImpl(
            ReactiveExtensionClient client,
            AttachmentService attachmentService,
            ObjectMapper objectMapper,
            ZhihuHtmlSanitizer sanitizer,
            ZhihuCommentNormalizer commentNormalizer,
            ZhihuAssetHtmlRewriter assetRewriter,
            ZhihuAssetLocalizer assetLocalizer,
            ZhihuPayloadHasher hasher) {
        this(
                client,
                attachmentService,
                objectMapper,
                sanitizer,
                commentNormalizer,
                assetRewriter,
                assetLocalizer,
                hasher,
                new ZhihuArchiveRenderer());
    }

    @Autowired
    public ZhihuArchiveServiceImpl(
            ReactiveExtensionClient client,
            AttachmentService attachmentService,
            ObjectMapper objectMapper,
            ZhihuHtmlSanitizer sanitizer,
            ZhihuCommentNormalizer commentNormalizer,
            ZhihuAssetHtmlRewriter assetRewriter,
            ZhihuAssetLocalizer assetLocalizer,
            ZhihuPayloadHasher hasher,
            ZhihuArchiveRenderer renderer) {
        this.client = client;
        this.attachmentService = attachmentService;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.commentNormalizer = commentNormalizer;
        this.assetRewriter = assetRewriter;
        this.assetLocalizer = assetLocalizer;
        this.hasher = hasher;
        this.renderer = renderer;
    }

    @Override
    public Mono<SinglePage> create(ZhihuArchiveCreateRequest request, String owner) {
        if (!StringUtils.hasText(owner)) {
            return Mono.error(new IllegalArgumentException("owner must not be blank"));
        }
        if (request == null || request.payload() == null) {
            return Mono.error(new IllegalArgumentException("payload must not be null"));
        }
        var payload = request.payload();
        if (payload.source() == null
                || payload.source().type() == null
                || !StringUtils.hasText(payload.source().id())) {
            return Mono.error(new IllegalArgumentException("source must have a type and id"));
        }
        try {
            ZhihuSourceUrlValidator.validate(payload.source().url());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        final Post.VisibleEnum visibility;
        try {
            visibility = resolveVisibility(request.visibleOrDefault());
        } catch (IllegalArgumentException e) {
            return Mono.error(e);
        }
        var archiveKey = ZhihuPayloadHasher.sha256HexOfCanonical(
                        payload.source().type().name() + ":" + payload.source().id())
                .substring(0, 12);
        var pageName = "zhihu-" + archiveKey;
        return client.fetch(SinglePage.class, pageName)
                .flatMap(existing -> syncVisibility(existing, visibility, owner))
                .switchIfEmpty(Mono.defer(() -> createNew(request, owner, archiveKey, pageName, visibility)));
    }

    static Post.VisibleEnum resolveVisibility(String visible) {
        final Post.VisibleEnum resolved;
        try {
            resolved = Post.VisibleEnum.valueOf(visible.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("visible must be PUBLIC or PRIVATE", e);
        }
        if (resolved != Post.VisibleEnum.PUBLIC && resolved != Post.VisibleEnum.PRIVATE) {
            throw new IllegalArgumentException("visible must be PUBLIC or PRIVATE");
        }
        return resolved;
    }

    private Mono<SinglePage> syncVisibility(SinglePage existing, Post.VisibleEnum visibility, String owner) {
        var spec = existing.getSpec();
        if (spec != null && spec.getVisible() == visibility) {
            return Mono.just(existing);
        }
        if (spec != null) {
            spec.setVisible(visibility);
        }
        MetadataUtil.nullSafeLabels(existing).put(SinglePage.VISIBLE_LABEL, visibility.name());
        return client.update(existing)
                .flatMap(updated -> rebuildIndex(owner).thenReturn(updated).onErrorResume(error -> {
                    log.warn(
                            "Failed to rebuild Zhihu archive index after updating visibility of {}",
                            updated.getMetadata().getName(),
                            error);
                    return Mono.just(updated);
                }));
    }

    private Mono<SinglePage> createNew(
            ZhihuArchiveCreateRequest request,
            String owner,
            String archiveKey,
            String pageName,
            Post.VisibleEnum visibility) {
        var payload = request.payload();
        var policyName = request.policyName();
        var sanitizedArticleHtml = sanitizer.sanitize(
                payload.article() == null ? null : payload.article().html());
        var normalizedComments = commentNormalizer.normalize(payload.comments());
        var sanitizedComments = sanitizeComments(normalizedComments);
        return assetRewriter
                .rewrite(sanitizedArticleHtml, payload.assetsOrEmpty(), policyName)
                .flatMap(rewriteResult -> {
                    var contentHash = hasher.sha256Hex(payload);
                    var rendered = renderer.render(
                            payload,
                            sanitizedComments,
                            rewriteResult.html(),
                            contentHash,
                            rewriteResult.failureCount());
                    return uploadPayload(payload, policyName, archiveKey)
                            .flatMap(attachment -> createSnapshotAndPage(
                                    payload,
                                    owner,
                                    policyName,
                                    pageName,
                                    rendered,
                                    contentHash,
                                    rewriteResult.failureCount(),
                                    attachment,
                                    visibility))
                            .flatMap(created -> rebuildIndex(owner)
                                    .thenReturn(created)
                                    .onErrorResume(error -> {
                                        log.warn(
                                                "Failed to rebuild Zhihu archive index after creating {}",
                                                created.getMetadata().getName(),
                                                error);
                                        return Mono.just(created);
                                    }));
                });
    }

    private Mono<SinglePage> createSnapshotAndPage(
            ZhihuArchivePayload payload,
            String owner,
            String policyName,
            String pageName,
            RenderedArchive rendered,
            String contentHash,
            int assetFailures,
            Attachment payloadAttachment,
            Post.VisibleEnum visibility) {
        var snapshotName = pageName + "-v1";
        var snapshot = buildSnapshot(snapshotName, pageName, rendered.html(), owner, Ref.of(pageName, SinglePage.GVK));
        var page = buildPage(
                payload,
                owner,
                policyName,
                pageName,
                rendered,
                contentHash,
                assetFailures,
                snapshotName,
                payloadAttachment,
                visibility);
        return client.create(snapshot)
                .onErrorResume(DuplicateNameException.class, error -> client.get(Snapshot.class, snapshotName))
                .flatMap(createdSnapshot -> client.create(page)
                        .onErrorResume(DuplicateNameException.class, error -> client.get(SinglePage.class, pageName))
                        .onErrorResume(
                                DataIntegrityViolationException.class,
                                error -> client.get(SinglePage.class, pageName)));
    }

    @Override
    public Mono<SinglePage> get(String name) {
        return client.get(SinglePage.class, name);
    }

    @Override
    public Flux<SinglePage> list() {
        var options = new ListOptions();
        options.setLabelSelector(LabelSelector.builder()
                .eq(ZhihuArchiveKeys.LABEL_ARCHIVE, ZhihuArchiveKeys.ARCHIVE_LABEL_VALUE)
                .build());
        return client.listAll(SinglePage.class, options, Sort.by(desc("metadata.creationTimestamp")))
                .filter(ZhihuArchiveServiceImpl::hasArchiveLabel)
                .filter(ZhihuArchiveServiceImpl::isActive);
    }

    @Override
    public Mono<Void> delete(String name) {
        return client.fetch(SinglePage.class, name)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Archive not found: " + name)))
                .flatMap(page -> {
                    if (!hasArchiveLabel(page)) {
                        return Mono.error(() -> new IllegalArgumentException("Not a Zhihu archive: " + name));
                    }
                    var owner = page.getSpec() == null ? null : page.getSpec().getOwner();
                    return client.delete(page)
                            .then(waitUntilDeleted(name, DELETE_POLL_ATTEMPTS))
                            .then(Mono.defer(() -> rebuildIndex(StringUtils.hasText(owner) ? owner : "anonymous")
                                    .onErrorResume(error -> {
                                        log.warn(
                                                "Failed to rebuild Zhihu archive index after deleting {}", name, error);
                                        return Mono.empty();
                                    })))
                            .then();
                });
    }

    private Mono<Void> waitUntilDeleted(String name, int attempts) {
        return client.fetch(SinglePage.class, name)
                .flatMap(page -> attempts <= 0
                        ? Mono.<Void>empty()
                        : Mono.delay(DELETE_POLL_INTERVAL).then(Mono.defer(() -> waitUntilDeleted(name, attempts - 1))))
                .then();
    }

    @Override
    public Mono<SinglePage> rebuildIndex(String owner) {
        if (!StringUtils.hasText(owner)) {
            return Mono.error(new IllegalArgumentException("owner must not be blank"));
        }
        return Mono.fromCallable(() -> rebuildIndexBlocking(owner)).subscribeOn(Schedulers.boundedElastic());
    }

    private SinglePage rebuildIndexBlocking(String owner) {
        synchronized (indexLock) {
            var pages = list().collectList().block();
            var sortedPages = pages == null
                    ? List.<SinglePage>of()
                    : pages.stream()
                            .filter(ZhihuArchiveServiceImpl::isPublicArchive)
                            .sorted(NEWEST_FIRST)
                            .toList();
            var indexHtml = buildIndexHtml(sortedPages);
            var existing = client.fetch(SinglePage.class, ZhihuArchiveKeys.INDEX_PAGE_NAME)
                    .block();
            if (existing != null && !hasIndexMarker(existing)) {
                throw new IllegalStateException("index page name is occupied");
            }
            var snapshotName = nextIndexSnapshotName(existing);
            var indexPageName = ZhihuArchiveKeys.INDEX_PAGE_NAME;
            var snapshot =
                    buildSnapshot(snapshotName, indexPageName, indexHtml, owner, Ref.of(indexPageName, SinglePage.GVK));
            client.create(snapshot).block();
            if (existing == null) {
                var created =
                        client.create(buildIndexPage(null, owner, snapshotName)).block();
                if (created == null) {
                    throw new IllegalStateException("failed to create index page");
                }
                return created;
            }
            var updated =
                    client.update(buildIndexPage(existing, owner, snapshotName)).block();
            if (updated == null) {
                throw new IllegalStateException("failed to update index page");
            }
            return updated;
        }
    }

    private String buildIndexHtml(List<SinglePage> pages) {
        var document = Jsoup.parseBodyFragment("");
        document.outputSettings().prettyPrint(false);
        var root = document.body().appendElement("div").addClass("zhihu-archive-index");
        root.appendElement("h1").text(ZhihuArchiveKeys.INDEX_PAGE_TITLE);
        if (pages.isEmpty()) {
            root.appendElement("p").addClass("zhihu-archive-index-empty").text("暂无公开存档。");
        } else {
            var list = root.appendElement("ul");
            for (var page : pages) {
                var item = list.appendElement("li");
                var spec = page.getSpec();
                var slug = spec == null ? null : spec.getSlug();
                var href = StringUtils.hasText(slug) ? "/" + slug : "/";
                item.appendElement("a").attr("href", href).text(spec == null ? "" : nullToEmpty(spec.getTitle()));
                var annotations = annotationsOf(page);
                var metaParts = new ArrayList<String>(3);
                addIfPresent(metaParts, annotations.get(ZhihuArchiveKeys.ANNO_AUTHOR_NAME));
                addIfPresent(metaParts, annotations.get(ZhihuArchiveKeys.ANNO_CAPTURED_AT));
                addIfPresent(metaParts, annotations.get(ZhihuArchiveKeys.ANNO_SOURCE_TYPE));
                item.appendElement("span").addClass("zhihu-archive-index-meta").text(String.join(" · ", metaParts));
            }
        }
        return root.outerHtml();
    }

    private static boolean isPublicArchive(SinglePage page) {
        return page.getSpec() != null && page.getSpec().getVisible() == Post.VisibleEnum.PUBLIC;
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value);
        }
    }

    @Override
    public Mono<SinglePage> transferVideo(String name) {
        return get(name).flatMap(page -> {
            var annotations = MetadataUtil.nullSafeAnnotations(page);
            if (ZhihuArchiveKeys.VIDEO_STATE_TRANSFERRED.equals(annotations.get(ZhihuArchiveKeys.ANNO_VIDEO_STATE))) {
                return Mono.just(page);
            }
            var policyName = annotations.get(ZhihuArchiveKeys.ANNO_POLICY_NAME);
            var videoUrl = annotations.get(ZhihuArchiveKeys.ANNO_VIDEO_SOURCE_URL);
            if (!StringUtils.hasText(policyName) || !StringUtils.hasText(videoUrl)) {
                return Mono.error(new IllegalStateException("snapshot has no transferable video"));
            }
            return assetLocalizer
                    .localize(new ZhihuAsset(videoUrl, ZhihuAssetType.VIDEO), policyName)
                    .switchIfEmpty(Mono.error(new IllegalStateException("video transfer failed: empty result")))
                    .flatMap(result -> {
                        if (result == null || result.failed() || !StringUtils.hasText(result.localUrl())) {
                            var reason = result == null ? "empty result" : result.failureReason();
                            return Mono.error(new IllegalStateException("video transfer failed: " + reason));
                        }
                        return applyVideoTransfer(page, result.localUrl(), videoUrl);
                    });
        });
    }

    private Mono<SinglePage> applyVideoTransfer(SinglePage page, String localUrl, String sourceUrl) {
        var spec = page.getSpec();
        var headSnapshotName = spec == null ? null : spec.getHeadSnapshot();
        if (!StringUtils.hasText(headSnapshotName)) {
            return Mono.error(new IllegalStateException("snapshot has no head snapshot"));
        }
        return client.get(Snapshot.class, headSnapshotName).flatMap(headSnapshot -> {
            var headSpec = headSnapshot.getSpec();
            var html = headSpec == null ? null : headSpec.getContentPatch();
            var document = Jsoup.parseBodyFragment(nullToEmpty(html));
            document.outputSettings().prettyPrint(false);
            var body = document.body();
            var figures = body.select(VIDEO_FIGURE_SELECTOR);
            if (figures.isEmpty()) {
                return Mono.error(new IllegalStateException("video figure not found"));
            }
            var sourceId = MetadataUtil.nullSafeAnnotations(page).get(ZhihuArchiveKeys.ANNO_SOURCE_ID);
            var target = findVideoFigure(figures, sourceId);
            var poster = target.select("img[src]").stream()
                    .map(image -> image.attr("src"))
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
            target.replaceWith(videoFigure(localUrl, sourceUrl, poster));

            var snapshotName = page.getMetadata().getName() + "-video-"
                    + ZhihuPayloadHasher.sha256HexOfCanonical(localUrl).substring(0, 8);
            var newSnapshot = buildSnapshot(
                    snapshotName,
                    page.getMetadata().getName(),
                    body.html(),
                    spec.getOwner(),
                    Ref.of(page.getMetadata().getName(), SinglePage.GVK));
            return client.create(newSnapshot).flatMap(created -> {
                spec.setHeadSnapshot(snapshotName);
                if (page.isPublished()) {
                    spec.setReleaseSnapshot(snapshotName);
                }
                var annotations = MetadataUtil.nullSafeAnnotations(page);
                annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_STATE, ZhihuArchiveKeys.VIDEO_STATE_TRANSFERRED);
                annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_ATTACHMENT_PREFIX + nullToEmpty(sourceId), localUrl);
                return client.update(page);
            });
        });
    }

    private static Element findVideoFigure(org.jsoup.select.Elements figures, String sourceId) {
        if (StringUtils.hasText(sourceId)) {
            for (var figure : figures) {
                if (sourceId.equals(figure.attr("data-video-id"))) {
                    return figure;
                }
            }
        }
        return figures.first();
    }

    private static Element videoFigure(String localUrl, String sourceUrl, String poster) {
        var figure = new Element("figure").addClass("zhihu-archive-video").addClass("zhihu-archive-video-transferred");
        var video = figure.appendElement("video")
                .attr("controls", "")
                .attr("preload", "metadata")
                .attr("src", localUrl);
        if (StringUtils.hasText(poster)) {
            video.attr("poster", poster);
        }
        figure.appendElement("figcaption")
                .appendElement("a")
                .attr("href", sourceUrl)
                .attr("target", "_blank")
                .attr("rel", LINK_REL)
                .text("原视频链接");
        return figure;
    }

    private Mono<Attachment> uploadPayload(ZhihuArchivePayload payload, String policyName, String archiveKey) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(payload))
                .flatMap(bytes -> {
                    var filename = "zhihu-payload-" + archiveKey + "-"
                            + ZhihuPayloadHasher.sha256Hex(bytes).substring(0, 12) + ".json";
                    return findAttachment(filename)
                            .switchIfEmpty(Mono.defer(() -> attachmentService.upload(
                                    policyName,
                                    PAYLOAD_GROUP_NAME,
                                    filename,
                                    Flux.just(new DefaultDataBufferFactory().wrap(bytes)),
                                    MediaType.APPLICATION_JSON)));
                })
                .flatMap(attachment -> attachmentService
                        .getPermalink(attachment)
                        .defaultIfEmpty(URI.create(""))
                        .thenReturn(attachment));
    }

    private Mono<Attachment> findAttachment(String displayName) {
        var options = new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.displayName", displayName)));
        return client.listAll(Attachment.class, options, Sort.unsorted()).next();
    }

    private Snapshot buildSnapshot(String snapshotName, String subjectName, String html, String owner, Ref subjectRef) {
        var snapshot = new Snapshot();
        var metadata = new Metadata();
        metadata.setName(snapshotName);
        snapshot.setMetadata(metadata);
        MetadataUtil.nullSafeAnnotations(snapshot).put(Snapshot.KEEP_RAW_ANNO, Boolean.TRUE.toString());
        MetadataUtil.nullSafeLabels(snapshot).put(ZhihuArchiveKeys.LABEL_ARCHIVE, ZhihuArchiveKeys.ARCHIVE_LABEL_VALUE);
        var spec = new Snapshot.SnapShotSpec();
        spec.setSubjectRef(subjectRef);
        spec.setRawType(SNAPSHOT_RAW_TYPE);
        spec.setRawPatch(html);
        spec.setContentPatch(html);
        spec.setOwner(owner);
        spec.setLastModifyTime(Instant.now());
        snapshot.setSpec(spec);
        return snapshot;
    }

    private SinglePage buildPage(
            ZhihuArchivePayload payload,
            String owner,
            String policyName,
            String pageName,
            RenderedArchive rendered,
            String contentHash,
            int assetFailures,
            String snapshotName,
            Attachment payloadAttachment,
            Post.VisibleEnum visibility) {
        var page = new SinglePage();
        var metadata = new Metadata();
        metadata.setName(pageName);
        page.setMetadata(metadata);

        var labels = MetadataUtil.nullSafeLabels(page);
        labels.put(ZhihuArchiveKeys.LABEL_ARCHIVE, ZhihuArchiveKeys.ARCHIVE_LABEL_VALUE);
        labels.put(SinglePage.PUBLISHED_LABEL, Boolean.TRUE.toString());
        labels.put(SinglePage.OWNER_LABEL, owner);
        labels.put(SinglePage.VISIBLE_LABEL, visibility.name());

        var annotations = MetadataUtil.nullSafeAnnotations(page);
        annotations.put(ZhihuArchiveKeys.ANNO_SCHEMA_VERSION, "1");
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_URL, payload.source().url());
        annotations.put(
                ZhihuArchiveKeys.ANNO_SOURCE_TYPE, payload.source().type().name());
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_ID, payload.source().id());
        if (payload.capture() != null && payload.capture().capturedAt() != null) {
            annotations.put(
                    ZhihuArchiveKeys.ANNO_CAPTURED_AT,
                    payload.capture().capturedAt().toString());
        }
        annotations.put(ZhihuArchiveKeys.ANNO_CONTENT_HASH, contentHash);
        annotations.put(ZhihuArchiveKeys.ANNO_POLICY_NAME, policyName);
        annotations.put(
                ZhihuArchiveKeys.ANNO_RAW_PAYLOAD_ATTACHMENT,
                payloadAttachment.getMetadata().getName());

        var article = payload.article();
        if (article != null) {
            if (article.author() != null) {
                if (article.author().name() != null) {
                    annotations.put(
                            ZhihuArchiveKeys.ANNO_AUTHOR_NAME, article.author().name());
                }
                if (article.author().profileUrl() != null) {
                    annotations.put(
                            ZhihuArchiveKeys.ANNO_AUTHOR_URL, article.author().profileUrl());
                }
            }
            if (article.publishedAt() != null) {
                annotations.put(
                        ZhihuArchiveKeys.ANNO_PUBLISHED_AT,
                        article.publishedAt().toString());
            }
            annotations.put(ZhihuArchiveKeys.ANNO_LIKE_COUNT, Long.toString(article.likeCount()));
            annotations.put(ZhihuArchiveKeys.ANNO_COMMENT_COUNT, Long.toString(article.commentCount()));
        }
        annotations.put(ZhihuArchiveKeys.ANNO_ASSET_FAILURES, Integer.toString(assetFailures));

        payload.assetsOrEmpty().stream()
                .filter(asset -> asset != null && asset.type() == ZhihuAssetType.VIDEO)
                .findFirst()
                .ifPresent(video -> {
                    annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_STATE, ZhihuArchiveKeys.VIDEO_STATE_NONE);
                    annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_SOURCE_URL, video.url());
                });

        var spec = new SinglePage.SinglePageSpec();
        spec.setTitle(rendered.title());
        spec.setSlug(rendered.slug());
        spec.setHeadSnapshot(snapshotName);
        spec.setBaseSnapshot(snapshotName);
        spec.setReleaseSnapshot(snapshotName);
        spec.setOwner(owner);
        spec.setDeleted(false);
        spec.setPublish(true);
        spec.setPublishTime(Instant.now());
        spec.setPinned(false);
        spec.setAllowComment(false);
        spec.setVisible(visibility);
        spec.setPriority(0);
        var excerpt = new Post.Excerpt();
        excerpt.setAutoGenerate(true);
        spec.setExcerpt(excerpt);
        spec.setHtmlMetas(List.of(Map.of("name", "robots", "content", "noindex")));
        page.setSpec(spec);
        page.setStatus(null);
        return page;
    }

    private SinglePage buildIndexPage(SinglePage existing, String owner, String snapshotName) {
        var page = existing == null ? new SinglePage() : existing;
        if (existing == null) {
            var metadata = new Metadata();
            metadata.setName(ZhihuArchiveKeys.INDEX_PAGE_NAME);
            page.setMetadata(metadata);
        }
        var labels = MetadataUtil.nullSafeLabels(page);
        labels.put(SinglePage.PUBLISHED_LABEL, Boolean.TRUE.toString());
        labels.put(SinglePage.OWNER_LABEL, owner);
        labels.put(SinglePage.VISIBLE_LABEL, Post.VisibleEnum.PUBLIC.name());
        MetadataUtil.nullSafeAnnotations(page).put(INDEX_MARKER_ANNO, INDEX_MARKER_VALUE);

        var spec = page.getSpec() == null ? new SinglePage.SinglePageSpec() : page.getSpec();
        spec.setTitle(ZhihuArchiveKeys.INDEX_PAGE_TITLE);
        spec.setSlug(ZhihuArchiveKeys.INDEX_PAGE_SLUG);
        spec.setHeadSnapshot(snapshotName);
        spec.setBaseSnapshot(snapshotName);
        spec.setReleaseSnapshot(snapshotName);
        spec.setOwner(owner);
        spec.setDeleted(false);
        spec.setPublish(true);
        spec.setPublishTime(Instant.now());
        spec.setPinned(false);
        spec.setAllowComment(false);
        spec.setVisible(Post.VisibleEnum.PUBLIC);
        spec.setPriority(0);
        var excerpt = new Post.Excerpt();
        excerpt.setAutoGenerate(true);
        spec.setExcerpt(excerpt);
        page.setSpec(spec);
        return page;
    }

    private static String nextIndexSnapshotName(SinglePage existing) {
        if (existing == null || existing.getSpec() == null) {
            return ZhihuArchiveKeys.INDEX_PAGE_NAME + "-v1";
        }
        var prefix = ZhihuArchiveKeys.INDEX_PAGE_NAME + "-v";
        var head = existing.getSpec().getHeadSnapshot();
        if (head != null && head.startsWith(prefix)) {
            try {
                return prefix + (Integer.parseInt(head.substring(prefix.length())) + 1);
            } catch (NumberFormatException ignored) {
                // Fall through to the metadata-version based fallback.
            }
        }
        var version =
                existing.getMetadata() == null ? null : existing.getMetadata().getVersion();
        return prefix + (version == null ? 2 : Math.max(2, version + 1));
    }

    private static boolean hasIndexMarker(SinglePage page) {
        return INDEX_MARKER_VALUE.equals(annotationsOf(page).get(INDEX_MARKER_ANNO));
    }

    private static boolean hasArchiveLabel(SinglePage page) {
        var metadata = page.getMetadata();
        var labels = metadata == null ? null : metadata.getLabels();
        return labels != null
                && ZhihuArchiveKeys.ARCHIVE_LABEL_VALUE.equals(labels.get(ZhihuArchiveKeys.LABEL_ARCHIVE));
    }

    private static boolean isActive(SinglePage page) {
        var metadata = page.getMetadata();
        var spec = page.getSpec();
        var logicallyDeleted = spec != null && Boolean.TRUE.equals(spec.getDeleted());
        return !logicallyDeleted && (metadata == null || metadata.getDeletionTimestamp() == null);
    }

    private static Map<String, String> annotationsOf(SinglePage page) {
        var metadata = page.getMetadata();
        var annotations = metadata == null ? null : metadata.getAnnotations();
        return annotations == null ? Map.of() : annotations;
    }

    private static Instant creationTimestamp(SinglePage page) {
        var metadata = page.getMetadata();
        return metadata == null ? null : metadata.getCreationTimestamp();
    }

    private ZhihuNormalizedComments sanitizeComments(ZhihuNormalizedComments comments) {
        if (comments == null) {
            return new ZhihuNormalizedComments(0, List.of(), 0, 0, 0);
        }
        var threads = comments.threads() == null
                ? List.<ZhihuCommentThread>of()
                : comments.threads().stream()
                        .map(thread -> new ZhihuCommentThread(
                                sanitizeComment(thread.root()),
                                thread.replies() == null
                                        ? List.<ZhihuComment>of()
                                        : thread.replies().stream()
                                                .map(this::sanitizeComment)
                                                .toList(),
                                thread.omittedReplies()))
                        .toList();
        return new ZhihuNormalizedComments(
                comments.total(), threads, comments.suppliedRoots(), comments.selectedRoots(), comments.omittedRoots());
    }

    private ZhihuComment sanitizeComment(ZhihuComment comment) {
        if (comment == null) {
            return null;
        }
        return new ZhihuComment(
                comment.id(),
                comment.author(),
                sanitizer.sanitize(comment.contentHtml()),
                comment.likeCount(),
                comment.createdAt(),
                comment.ipLocation(),
                comment.badge(),
                comment.collapsed(),
                comment.replyToAuthor(),
                comment.replies());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
