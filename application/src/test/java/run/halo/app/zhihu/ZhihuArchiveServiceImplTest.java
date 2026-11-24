package run.halo.app.zhihu;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.exception.DuplicateNameException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZhihuArchiveServiceImplTest {

    private static final String POLICY = "policy";

    private static final Instant CAPTURED_AT = Instant.parse("2026-02-03T04:05:06Z");

    private static final Instant PUBLISHED_AT = Instant.parse("2025-01-02T03:04:05Z");

    private static final Instant CREATED_AT = Instant.parse("2026-02-01T00:00:00Z");

    @Mock
    private ReactiveExtensionClient client;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ZhihuHtmlSanitizer sanitizer;

    @Mock
    private ZhihuAssetHtmlRewriter assetRewriter;

    @Mock
    private ZhihuAssetLocalizer assetLocalizer;

    @Mock
    private ZhihuArchiveRenderer renderer;

    private ZhihuArchiveServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ZhihuArchiveServiceImpl(
                client,
                attachmentService,
                objectMapper,
                sanitizer,
                new ZhihuCommentNormalizer(),
                assetRewriter,
                assetLocalizer,
                new ZhihuPayloadHasher(),
                renderer);
    }

    @Test
    void shouldCreateSnapshotAndPageWithExpectedMetadata() throws Exception {
        stubCreatePipeline(2);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .assertNext(page -> assertThat(page.getMetadata().getName()).startsWith("zhihu-"))
                .verifyComplete();

        var pageCaptor = ArgumentCaptor.forClass(SinglePage.class);
        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(pageCaptor.capture());
        verify(client).create(snapshotCaptor.capture());

        var page = pageCaptor.getValue();
        var snapshot = snapshotCaptor.getValue();
        var pageName = page.getMetadata().getName();
        var snapshotName = pageName + "-v1";

        assertThat(page.getMetadata().getLabels())
                .containsEntry(ZhihuArchiveKeys.LABEL_ARCHIVE, "true")
                .containsEntry(SinglePage.PUBLISHED_LABEL, "true")
                .containsEntry(SinglePage.OWNER_LABEL, "owner")
                .containsEntry(SinglePage.VISIBLE_LABEL, Post.VisibleEnum.PUBLIC.name());

        var annotations = page.getMetadata().getAnnotations();
        assertThat(annotations)
                .containsEntry(ZhihuArchiveKeys.ANNO_SCHEMA_VERSION, "1")
                .containsEntry(ZhihuArchiveKeys.ANNO_SOURCE_URL, "https://zhuanlan.zhihu.com/p/p1")
                .containsEntry(ZhihuArchiveKeys.ANNO_SOURCE_TYPE, "ARTICLE")
                .containsEntry(ZhihuArchiveKeys.ANNO_SOURCE_ID, "p1")
                .containsEntry(ZhihuArchiveKeys.ANNO_CAPTURED_AT, CAPTURED_AT.toString())
                .containsKey(ZhihuArchiveKeys.ANNO_CONTENT_HASH)
                .containsEntry(ZhihuArchiveKeys.ANNO_POLICY_NAME, POLICY)
                .containsEntry(ZhihuArchiveKeys.ANNO_RAW_PAYLOAD_ATTACHMENT, "att-1")
                .containsEntry(ZhihuArchiveKeys.ANNO_AUTHOR_NAME, "Author")
                .containsEntry(ZhihuArchiveKeys.ANNO_AUTHOR_URL, "https://www.zhihu.com/people/a")
                .containsEntry(ZhihuArchiveKeys.ANNO_PUBLISHED_AT, PUBLISHED_AT.toString())
                .containsEntry(ZhihuArchiveKeys.ANNO_LIKE_COUNT, "42")
                .containsEntry(ZhihuArchiveKeys.ANNO_COMMENT_COUNT, "7")
                .containsEntry(ZhihuArchiveKeys.ANNO_ASSET_FAILURES, "2")
                .doesNotContainKey(ZhihuArchiveKeys.ANNO_VIDEO_STATE);
        assertThat(annotations.get(ZhihuArchiveKeys.ANNO_CONTENT_HASH)).hasSize(64);

        var spec = page.getSpec();
        assertThat(spec.getTitle()).isEqualTo("Rendered Title");
        assertThat(spec.getSlug()).isEqualTo("zhihu-rendered");
        assertThat(spec.getHeadSnapshot()).isEqualTo(snapshotName);
        assertThat(spec.getBaseSnapshot()).isEqualTo(snapshotName);
        assertThat(spec.getReleaseSnapshot()).isEqualTo(snapshotName);
        assertThat(spec.getOwner()).isEqualTo("owner");
        assertThat(spec.getDeleted()).isFalse();
        assertThat(spec.getPublish()).isTrue();
        assertThat(spec.getPublishTime()).isNotNull();
        assertThat(spec.getPinned()).isFalse();
        assertThat(spec.getAllowComment()).isFalse();
        assertThat(spec.getVisible()).isEqualTo(Post.VisibleEnum.PUBLIC);
        assertThat(spec.getPriority()).isZero();
        assertThat(spec.getExcerpt().getAutoGenerate()).isTrue();
        assertThat(spec.getHtmlMetas()).containsExactly(Map.of("name", "robots", "content", "noindex"));
        assertThat(page.getStatus()).isNull();

        assertThat(snapshot.getMetadata().getName()).isEqualTo(snapshotName);
        assertThat(snapshot.getMetadata().getAnnotations()).containsEntry(Snapshot.KEEP_RAW_ANNO, "true");
        assertThat(snapshot.getSpec().getRawType()).isEqualTo("HTML");
        assertThat(snapshot.getSpec().getRawPatch()).isEqualTo("<div class=\"zhihu-archive-snapshot\">snapshot</div>");
        assertThat(snapshot.getSpec().getContentPatch())
                .isEqualTo("<div class=\"zhihu-archive-snapshot\">snapshot</div>");
        assertThat(snapshot.getSpec().getOwner()).isEqualTo("owner");
        assertThat(snapshot.getSpec().getLastModifyTime()).isNotNull();
        assertThat(snapshot.getSpec().getSubjectRef().getName()).isEqualTo(pageName);
        assertThat(snapshot.getSpec().getSubjectRef().getGroup()).isEqualTo(SinglePage.GVK.group());
        assertThat(snapshot.getSpec().getSubjectRef().getVersion()).isEqualTo(SinglePage.GVK.version());
        assertThat(snapshot.getSpec().getSubjectRef().getKind()).isEqualTo(SinglePage.GVK.kind());
    }

    @Test
    void shouldUploadAttachmentBeforeCreatingSnapshotAndPage() throws Exception {
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNextCount(1)
                .verifyComplete();

        var inOrder = inOrder(attachmentService, client);
        inOrder.verify(attachmentService)
                .upload(
                        eq(POLICY),
                        eq("zhihu-archive"),
                        matches("zhihu-payload-[0-9a-f]{12}-[0-9a-f]{12}\\.json"),
                        any(),
                        eq(MediaType.APPLICATION_JSON));
        inOrder.verify(client).create(any(Snapshot.class));
        inOrder.verify(client).create(any(SinglePage.class));
    }

    @Test
    void shouldReuseExistingPayloadAttachmentForIdenticalPayload() throws Exception {
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());
        var existing = attachment("att-existing");
        when(attachmentService.getPermalink(existing))
                .thenReturn(Mono.just(URI.create("https://cdn.local/existing.json")));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(existing));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .assertNext(page -> assertThat(page.getMetadata().getAnnotations())
                        .containsEntry(ZhihuArchiveKeys.ANNO_RAW_PAYLOAD_ATTACHMENT, "att-existing"))
                .verifyComplete();

        verify(attachmentService, never())
                .upload(
                        anyString(),
                        anyString(),
                        anyString(),
                        ArgumentMatchers.<Flux<DataBuffer>>any(),
                        any(MediaType.class));
    }

    @Test
    void shouldRebuildIndexAfterCreatingSnapshot() throws Exception {
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .assertNext(page -> assertThat(page.getMetadata().getName()).startsWith("zhihu-"))
                .verifyComplete();

        var pagesCaptor = ArgumentCaptor.forClass(SinglePage.class);
        verify(client, times(2)).create(pagesCaptor.capture());
        var pages = pagesCaptor.getAllValues();
        var indexPages = pages.stream()
                .filter(page -> ZhihuArchiveKeys.INDEX_PAGE_NAME.equals(
                        page.getMetadata().getName()))
                .toList();
        assertThat(indexPages).singleElement().satisfies(page -> {
            assertThat(page.getSpec().getSlug()).isEqualTo(ZhihuArchiveKeys.INDEX_PAGE_SLUG);
            assertThat(page.getSpec().getVisible()).isEqualTo(Post.VisibleEnum.PUBLIC);
        });
        var archivePages = pages.stream()
                .filter(page -> !ZhihuArchiveKeys.INDEX_PAGE_NAME.equals(
                        page.getMetadata().getName()))
                .toList();
        assertThat(archivePages)
                .singleElement()
                .satisfies(page -> assertThat(page.getMetadata().getName()).startsWith("zhihu-"));
    }

    @Test
    void shouldReturnExistingPageAndSkipWorkForDuplicateSource() throws Exception {
        var existing = archivePage("zhihu-existing", "Title", CREATED_AT);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNext(existing)
                .verifyComplete();

        verifyNoInteractions(attachmentService);
        verify(client, never()).create(any());
        verify(client, never()).update(any(SinglePage.class));
        verify(renderer, never()).render(any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldCreatePrivateSnapshotWhenRequested() throws Exception {
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null), "PRIVATE"), "owner"))
                .expectNextCount(1)
                .verifyComplete();

        var pagesCaptor = ArgumentCaptor.forClass(SinglePage.class);
        verify(client, times(2)).create(pagesCaptor.capture());
        var archive = pagesCaptor.getAllValues().stream()
                .filter(page -> !ZhihuArchiveKeys.INDEX_PAGE_NAME.equals(
                        page.getMetadata().getName()))
                .findFirst()
                .orElseThrow();
        assertThat(archive.getSpec().getVisible()).isEqualTo(Post.VisibleEnum.PRIVATE);
        assertThat(archive.getMetadata().getLabels())
                .containsEntry(SinglePage.VISIBLE_LABEL, Post.VisibleEnum.PRIVATE.name());
    }

    @Test
    void shouldPromoteExistingSnapshotVisibilityWhenResubmittedAsPublic() throws Exception {
        var existing = archivePage("zhihu-existing", "Title", CREATED_AT, Post.VisibleEnum.PRIVATE);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.just(existing), Mono.empty());
        when(client.update(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(existing));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .assertNext(page -> {
                    assertThat(page.getSpec().getVisible()).isEqualTo(Post.VisibleEnum.PUBLIC);
                    assertThat(page.getMetadata().getLabels())
                            .containsEntry(SinglePage.VISIBLE_LABEL, Post.VisibleEnum.PUBLIC.name());
                })
                .verifyComplete();

        verify(client).update(existing);
        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldRejectUnsupportedVisibility() {
        var request = request(payload("p1", "Title", List.of(), null), "INTERNAL");

        StepVerifier.create(service.create(request, "owner"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("PUBLIC or PRIVATE"))
                .verify();
    }

    @Test
    void shouldExcludePrivateSnapshotsFromIndex() throws Exception {
        var publicPage = archivePage("zhihu-public", "Public Archive", Instant.parse("2026-01-01T00:00:00Z"));
        var privatePage = archivePage(
                "zhihu-private", "Private Archive", Instant.parse("2026-02-01T00:00:00Z"), Post.VisibleEnum.PRIVATE);
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(publicPage, privatePage));
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.empty());
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.rebuildIndex("owner")).expectNextCount(1).verifyComplete();

        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getSpec().getContentPatch())
                .contains("Public Archive")
                .doesNotContain("Private Archive");
    }

    @Test
    void shouldReturnSamePageWhenSamePayloadIsSubmittedTwice() throws Exception {
        var created = archivePage("zhihu-created", "Title", CREATED_AT);
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty(), Mono.just(created));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNext(created)
                .verifyComplete();

        verify(attachmentService, times(1))
                .upload(anyString(), anyString(), anyString(), any(Flux.class), any(MediaType.class));
        verify(client, times(1)).create(any(SinglePage.class));
    }

    @Test
    void shouldReFetchWhenCreateReportsDuplicateName() throws Exception {
        var existing = archivePage("zhihu-existing", "Title", CREATED_AT);
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty(), Mono.just(existing));
        when(client.create(any(SinglePage.class))).thenReturn(Mono.error(new DuplicateNameException("duplicate")));
        when(client.get(eq(SinglePage.class), anyString())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNext(existing)
                .verifyComplete();

        verify(client).get(eq(SinglePage.class), anyString());
    }

    @Test
    void shouldSanitizeArticleAndEveryCommentBodyBeforeRendering() throws Exception {
        var reply = new ZhihuComment(
                "reply",
                new ZhihuAuthor("Replier", null),
                "<script>reply</script>",
                1,
                CREATED_AT,
                null,
                null,
                null,
                null,
                List.of());
        var root = new ZhihuComment(
                "root",
                new ZhihuAuthor("Root", null),
                "<script>root</script>",
                2,
                CREATED_AT,
                null,
                null,
                null,
                null,
                List.of(reply));
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(root), null)), "owner"))
                .expectNextCount(1)
                .verifyComplete();

        verify(sanitizer).sanitize("<p>article<script>bad</script></p>");
        verify(sanitizer).sanitize("<script>root</script>");
        verify(sanitizer).sanitize("<script>reply</script>");

        var commentsCaptor = ArgumentCaptor.forClass(ZhihuNormalizedComments.class);
        verify(renderer).render(any(), commentsCaptor.capture(), anyString(), anyString(), anyInt());
        var sanitized = commentsCaptor.getValue();
        assertThat(sanitized.threads().getFirst().root().contentHtml()).isEqualTo("root");
        assertThat(sanitized.threads().getFirst().replies().getFirst().contentHtml())
                .isEqualTo("reply");
    }

    @Test
    void shouldPassAssetFailureCountToRenderer() throws Exception {
        stubCreatePipeline(3);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), "owner"))
                .expectNextCount(1)
                .verifyComplete();

        var failureCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(renderer).render(any(), any(), anyString(), anyString(), failureCaptor.capture());
        assertThat(failureCaptor.getValue()).isEqualTo(3);
    }

    @Test
    void shouldAnnotateVideoStateWhenPayloadContainsVideo() throws Exception {
        stubCreatePipeline(0);
        when(client.fetch(eq(SinglePage.class), anyString())).thenReturn(Mono.empty());
        var assets = List.of(new ZhihuAsset("https://www.zhihu.com/video/123", ZhihuAssetType.VIDEO));

        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), assets)), "owner"))
                .expectNextCount(1)
                .verifyComplete();

        var pageCaptor = ArgumentCaptor.forClass(SinglePage.class);
        verify(client).create(pageCaptor.capture());
        assertThat(pageCaptor.getValue().getMetadata().getAnnotations())
                .containsEntry(ZhihuArchiveKeys.ANNO_VIDEO_STATE, ZhihuArchiveKeys.VIDEO_STATE_NONE)
                .containsEntry(ZhihuArchiveKeys.ANNO_VIDEO_SOURCE_URL, "https://www.zhihu.com/video/123");
    }

    @Test
    void shouldRejectCreationWithoutOwner() {
        StepVerifier.create(service.create(request(payload("p1", "Title", List.of(), null)), " "))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void shouldRejectNonZhihuSourceUrl() {
        var payload = new ZhihuArchivePayload(
                1,
                new ZhihuSource("https://evil.example/p/1", ZhihuSourceType.ARTICLE, "p1"),
                new ZhihuArticle("Title", new ZhihuAuthor("Author", null), null, 0, 0, "<p>x</p>"),
                new ZhihuComments(0, List.of()),
                new ZhihuCapture(CAPTURED_AT, "1.0.0"),
                null);

        StepVerifier.create(service.create(request(payload), "owner"))
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(client);
    }

    @Test
    void shouldListOnlyArchivesNewestFirst() {
        var older = archivePage("zhihu-older", "Older", Instant.parse("2026-01-01T00:00:00Z"));
        var newer = archivePage("zhihu-newer", "Newer", Instant.parse("2026-02-01T00:00:00Z"));
        var unrelated = archivePage("zhihu-unrelated", "Unrelated", Instant.parse("2026-03-01T00:00:00Z"));
        unrelated.getMetadata().getLabels().remove(ZhihuArchiveKeys.LABEL_ARCHIVE);
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(older, newer, unrelated));

        StepVerifier.create(service.list().map(page -> page.getMetadata().getName()))
                .expectNext("zhihu-older", "zhihu-newer")
                .verifyComplete();

        var optionsCaptor = ArgumentCaptor.forClass(ListOptions.class);
        var sortCaptor = ArgumentCaptor.forClass(Sort.class);
        verify(client).listAll(eq(SinglePage.class), optionsCaptor.capture(), sortCaptor.capture());
        assertThat(optionsCaptor.getValue().getLabelSelector()).isNotNull();
        assertThat(optionsCaptor.getValue().getLabelSelector().toString()).contains(ZhihuArchiveKeys.LABEL_ARCHIVE);
        assertThat(sortCaptor.getValue().getOrderFor("metadata.creationTimestamp"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void shouldExcludeDeletedAndTerminatingArchivesFromList() {
        var active = archivePage("zhihu-active", "Active", Instant.parse("2026-01-01T00:00:00Z"));
        var logicallyDeleted = archivePage("zhihu-trashed", "Trashed", Instant.parse("2026-02-01T00:00:00Z"));
        logicallyDeleted.getSpec().setDeleted(true);
        var terminating = archivePage("zhihu-terminating", "Terminating", Instant.parse("2026-03-01T00:00:00Z"));
        terminating.getMetadata().setDeletionTimestamp(Instant.parse("2026-04-01T00:00:00Z"));
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(active, logicallyDeleted, terminating));

        StepVerifier.create(service.list().map(page -> page.getMetadata().getName()))
                .expectNext("zhihu-active")
                .verifyComplete();
    }

    @Test
    void shouldDeleteArchivePageAndRebuildIndex() {
        var page = archivePage("zhihu-abc", "Title", CREATED_AT);
        when(client.fetch(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page), Mono.empty());
        when(client.delete(page)).thenReturn(Mono.just(page));
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.empty());
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.delete("zhihu-abc")).verifyComplete();

        verify(client).delete(page);
        var pageCaptor = ArgumentCaptor.forClass(SinglePage.class);
        verify(client).create(pageCaptor.capture());
        assertThat(pageCaptor.getValue().getMetadata().getName()).isEqualTo(ZhihuArchiveKeys.INDEX_PAGE_NAME);
    }

    @Test
    void shouldRejectDeletingMissingArchive() {
        when(client.fetch(eq(SinglePage.class), eq("zhihu-missing"))).thenReturn(Mono.empty());

        StepVerifier.create(service.delete("zhihu-missing"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("not found"))
                .verify();

        verify(client, never()).delete(any(SinglePage.class));
    }

    @Test
    void shouldRejectDeletingNonArchivePage() {
        var page = archivePage("zhihu-abc", "Title", CREATED_AT);
        page.getMetadata().getLabels().remove(ZhihuArchiveKeys.LABEL_ARCHIVE);
        when(client.fetch(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));

        StepVerifier.create(service.delete("zhihu-abc"))
                .expectErrorSatisfies(error -> assertThat(error)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Not a Zhihu archive"))
                .verify();

        verify(client, never()).delete(any(SinglePage.class));
    }

    @Test
    void shouldRebuildIndexWithFixedSlugAndNewestFirstEscapedEntries() throws Exception {
        var older = archivePage("zhihu-older", "<script>Older</script>", Instant.parse("2026-01-01T00:00:00Z"));
        var newer = archivePage("zhihu-newer", "Newer", Instant.parse("2026-02-01T00:00:00Z"));
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(older, newer));
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.empty());
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.rebuildIndex("owner"))
                .assertNext(page -> {
                    assertThat(page.getMetadata().getName()).isEqualTo(ZhihuArchiveKeys.INDEX_PAGE_NAME);
                    assertThat(page.getSpec().getSlug()).isEqualTo(ZhihuArchiveKeys.INDEX_PAGE_SLUG);
                    assertThat(page.getSpec().getTitle()).isEqualTo(ZhihuArchiveKeys.INDEX_PAGE_TITLE);
                    assertThat(page.getSpec().getVisible()).isEqualTo(Post.VisibleEnum.PUBLIC);
                    assertThat(page.getSpec().getOwner()).isEqualTo("owner");
                    assertThat(page.getMetadata().getAnnotations())
                            .containsEntry("zhihu-archive.halo.run/index-page", "true");
                })
                .verifyComplete();

        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(snapshotCaptor.capture());
        var html = snapshotCaptor.getValue().getSpec().getContentPatch();
        assertThat(html)
                .contains("class=\"zhihu-archive-index\"")
                .contains("class=\"zhihu-archive-index-meta\"")
                .contains("&lt;script&gt;Older&lt;/script&gt;")
                .doesNotContain("<script>Older</script>")
                .contains("href=\"/zhihu-newer\"")
                .contains("href=\"/zhihu-older\"");
        assertThat(html.indexOf("href=\"/zhihu-newer\"")).isLessThan(html.indexOf("href=\"/zhihu-older\""));
    }

    @Test
    void shouldRenderEmptyIndexState() throws Exception {
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.empty());
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.rebuildIndex("owner")).expectNextCount(1).verifyComplete();

        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getSpec().getContentPatch()).contains("暂无公开存档。");
    }

    @Test
    void shouldUpdateExistingIndexWithNewSnapshotWithoutMutatingOldOne() throws Exception {
        var existingIndex = indexPage("zhihu-archive-index-v1");
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.just(existingIndex));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.rebuildIndex("owner"))
                .assertNext(page -> {
                    assertThat(page.getSpec().getHeadSnapshot()).isEqualTo("zhihu-archive-index-v2");
                    assertThat(page.getSpec().getBaseSnapshot()).isEqualTo("zhihu-archive-index-v2");
                    assertThat(page.getSpec().getReleaseSnapshot()).isEqualTo("zhihu-archive-index-v2");
                })
                .verifyComplete();

        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getMetadata().getName()).isEqualTo("zhihu-archive-index-v2");
        verify(client, times(1)).update(any(SinglePage.class));
        verify(client, never()).update(any(Snapshot.class));
    }

    @Test
    void shouldFailWhenIndexPageNameIsOccupiedByNonIndexPage() {
        var occupied = archivePage(ZhihuArchiveKeys.INDEX_PAGE_NAME, "User page", CREATED_AT);
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
        when(client.fetch(eq(SinglePage.class), eq(ZhihuArchiveKeys.INDEX_PAGE_NAME)))
                .thenReturn(Mono.just(occupied));

        StepVerifier.create(service.rebuildIndex("owner"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).isEqualTo("index page name is occupied");
                })
                .verify();

        verify(client, never()).create(any());
    }

    @Test
    void shouldTransferVideoAndCreateNewSnapshot() {
        var videoUrl = "https://www.zhihu.com/video/123";
        var page = videoPage(ZhihuArchiveKeys.VIDEO_STATE_NONE, videoUrl);
        var head = snapshot(
                "zhihu-abc-v1",
                "<figure class=\"zhihu-archive-video\" data-video-id=\"p1\">"
                        + "<img src=\"https://cdn.local/poster.jpg\">"
                        + "<a href=\"" + videoUrl + "\">watch</a></figure>");
        when(client.get(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));
        when(client.get(eq(Snapshot.class), eq("zhihu-abc-v1"))).thenReturn(Mono.just(head));
        when(assetLocalizer.localize(any(), eq(POLICY)))
                .thenReturn(Mono.just(LocalizedAsset.localized(videoUrl, "https://cdn.local/video.mp4")));
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.update(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.transferVideo("zhihu-abc"))
                .assertNext(updated -> {
                    var newSnapshotName = updated.getSpec().getHeadSnapshot();
                    assertThat(newSnapshotName).startsWith("zhihu-abc-video-");
                    assertThat(updated.getSpec().getReleaseSnapshot()).isEqualTo(newSnapshotName);
                    assertThat(updated.getMetadata().getAnnotations())
                            .containsEntry(ZhihuArchiveKeys.ANNO_VIDEO_STATE, ZhihuArchiveKeys.VIDEO_STATE_TRANSFERRED)
                            .containsEntry(
                                    ZhihuArchiveKeys.ANNO_VIDEO_ATTACHMENT_PREFIX + "p1",
                                    "https://cdn.local/video.mp4");
                })
                .verifyComplete();

        var snapshotCaptor = ArgumentCaptor.forClass(Snapshot.class);
        verify(client).create(snapshotCaptor.capture());
        var html = snapshotCaptor.getValue().getSpec().getContentPatch();
        assertThat(html)
                .contains("zhihu-archive-video-transferred")
                .contains("<video controls")
                .contains("preload=\"metadata\"")
                .contains("src=\"https://cdn.local/video.mp4\"")
                .contains("poster=\"https://cdn.local/poster.jpg\"")
                .contains("原视频链接")
                .doesNotContain("data-video-id");
    }

    @Test
    void shouldReturnUnchangedPageWhenVideoAlreadyTransferred() {
        var page = videoPage(ZhihuArchiveKeys.VIDEO_STATE_TRANSFERRED, "https://www.zhihu.com/video/123");
        when(client.get(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));

        StepVerifier.create(service.transferVideo("zhihu-abc")).expectNext(page).verifyComplete();

        verifyNoInteractions(assetLocalizer);
        verify(client, never()).create(any());
        verify(client, never()).update(any());
    }

    @Test
    void shouldReportVideoTransferFailureWithoutWritingAnnotations() {
        var videoUrl = "https://www.zhihu.com/video/123";
        var page = videoPage(ZhihuArchiveKeys.VIDEO_STATE_NONE, videoUrl);
        when(client.get(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));
        when(assetLocalizer.localize(any(), eq(POLICY)))
                .thenReturn(Mono.just(LocalizedAsset.failed(videoUrl, "too-large")));

        StepVerifier.create(service.transferVideo("zhihu-abc"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("too-large");
                })
                .verify();

        verify(client, never()).create(any());
        verify(client, never()).update(any());
    }

    @Test
    void shouldErrorWhenSnapshotHasNoTransferableVideo() {
        var page = archivePage("zhihu-abc", "Title", CREATED_AT);
        when(client.get(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));

        StepVerifier.create(service.transferVideo("zhihu-abc"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).isEqualTo("snapshot has no transferable video");
                })
                .verify();

        verifyNoInteractions(assetLocalizer);
    }

    @Test
    void shouldErrorWhenVideoFigureIsMissing() {
        var videoUrl = "https://www.zhihu.com/video/123";
        var page = videoPage(ZhihuArchiveKeys.VIDEO_STATE_NONE, videoUrl);
        var head = snapshot("zhihu-abc-v1", "<p>no video here</p>");
        when(client.get(eq(SinglePage.class), eq("zhihu-abc"))).thenReturn(Mono.just(page));
        when(client.get(eq(Snapshot.class), eq("zhihu-abc-v1"))).thenReturn(Mono.just(head));
        when(assetLocalizer.localize(any(), eq(POLICY)))
                .thenReturn(Mono.just(LocalizedAsset.localized(videoUrl, "https://cdn.local/video.mp4")));

        StepVerifier.create(service.transferVideo("zhihu-abc"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).isEqualTo("video figure not found");
                })
                .verify();

        verify(client, never()).create(any());
    }

    private void stubCreatePipeline(int failures) throws Exception {
        when(sanitizer.sanitize(any())).thenAnswer(invocation -> {
            String html = invocation.getArgument(0);
            if (html == null) {
                return "";
            }
            return html.replace("<script>bad</script>", "")
                    .replace("<script>root</script>", "root")
                    .replace("<script>reply</script>", "reply");
        });
        when(assetRewriter.rewrite(anyString(), anyList(), anyString()))
                .thenReturn(Mono.just(new ZhihuAssetRewriteResult("<p>rewritten</p>", failures, 1)));
        when(renderer.render(any(), any(), anyString(), anyString(), anyInt()))
                .thenReturn(new RenderedArchive(
                        "<div class=\"zhihu-archive-snapshot\">snapshot</div>", "Rendered Title", "zhihu-rendered"));
        when(objectMapper.writeValueAsBytes(any())).thenReturn("{}".getBytes(UTF_8));
        var attachment = attachment("att-1");
        when(attachmentService.upload(anyString(), anyString(), anyString(), any(Flux.class), any(MediaType.class)))
                .thenReturn(Mono.just(attachment));
        when(attachmentService.getPermalink(attachment))
                .thenReturn(Mono.just(URI.create("https://cdn.local/payload.json")));
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
        when(client.create(any(Snapshot.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.create(any(SinglePage.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.listAll(eq(SinglePage.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.error(new IllegalStateException("index rebuild disabled in this test")));
    }

    private static ZhihuArchiveCreateRequest request(ZhihuArchivePayload payload) {
        return request(payload, null);
    }

    private static ZhihuArchiveCreateRequest request(ZhihuArchivePayload payload, String visible) {
        return new ZhihuArchiveCreateRequest(payload, POLICY, visible);
    }

    private static ZhihuArchivePayload payload(
            String id, String title, List<ZhihuComment> roots, List<ZhihuAsset> assets) {
        return new ZhihuArchivePayload(
                1,
                new ZhihuSource("https://zhuanlan.zhihu.com/p/" + id, ZhihuSourceType.ARTICLE, id),
                new ZhihuArticle(
                        title,
                        new ZhihuAuthor("Author", "https://www.zhihu.com/people/a"),
                        PUBLISHED_AT,
                        42,
                        7,
                        "<p>article<script>bad</script></p>"),
                new ZhihuComments(7, roots),
                new ZhihuCapture(CAPTURED_AT, "1.0.0"),
                assets == null ? null : new ZhihuAssets(assets));
    }

    private static Attachment attachment(String name) {
        var attachment = new Attachment();
        var metadata = new Metadata();
        metadata.setName(name);
        attachment.setMetadata(metadata);
        return attachment;
    }

    private static SinglePage archivePage(String name, String title, Instant createdAt) {
        return archivePage(name, title, createdAt, Post.VisibleEnum.PUBLIC);
    }

    private static SinglePage archivePage(String name, String title, Instant createdAt, Post.VisibleEnum visible) {
        var page = new SinglePage();
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(createdAt);
        metadata.setLabels(new HashMap<>(Map.of(
                ZhihuArchiveKeys.LABEL_ARCHIVE, "true",
                SinglePage.PUBLISHED_LABEL, "true",
                SinglePage.OWNER_LABEL, "owner",
                SinglePage.VISIBLE_LABEL, visible.name())));
        var annotations = new HashMap<String, String>();
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_ID, "p1");
        annotations.put(ZhihuArchiveKeys.ANNO_AUTHOR_NAME, "Author");
        annotations.put(ZhihuArchiveKeys.ANNO_CAPTURED_AT, CAPTURED_AT.toString());
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_TYPE, "ARTICLE");
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_URL, "https://zhuanlan.zhihu.com/p/1");
        metadata.setAnnotations(annotations);
        page.setMetadata(metadata);

        var spec = new SinglePage.SinglePageSpec();
        spec.setTitle(title);
        spec.setSlug(name);
        spec.setOwner("owner");
        spec.setHeadSnapshot(name + "-v1");
        spec.setBaseSnapshot(name + "-v1");
        spec.setReleaseSnapshot(name + "-v1");
        spec.setDeleted(false);
        spec.setPublish(true);
        spec.setVisible(visible);
        page.setSpec(spec);
        return page;
    }

    private static SinglePage indexPage(String headSnapshot) {
        var page = archivePage(ZhihuArchiveKeys.INDEX_PAGE_NAME, ZhihuArchiveKeys.INDEX_PAGE_TITLE, CREATED_AT);
        page.getMetadata().getLabels().remove(ZhihuArchiveKeys.LABEL_ARCHIVE);
        page.getMetadata().getAnnotations().put("zhihu-archive.halo.run/index-page", "true");
        page.getSpec().setSlug(ZhihuArchiveKeys.INDEX_PAGE_SLUG);
        page.getSpec().setHeadSnapshot(headSnapshot);
        page.getSpec().setBaseSnapshot(headSnapshot);
        page.getSpec().setReleaseSnapshot(headSnapshot);
        return page;
    }

    private static SinglePage videoPage(String videoState, String videoUrl) {
        var page = archivePage("zhihu-abc", "Title", CREATED_AT);
        var annotations = page.getMetadata().getAnnotations();
        annotations.put(ZhihuArchiveKeys.ANNO_POLICY_NAME, POLICY);
        annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_SOURCE_URL, videoUrl);
        annotations.put(ZhihuArchiveKeys.ANNO_VIDEO_STATE, videoState);
        annotations.put(ZhihuArchiveKeys.ANNO_SOURCE_ID, "p1");
        return page;
    }

    private static Snapshot snapshot(String name, String html) {
        var snapshot = new Snapshot();
        var metadata = new Metadata();
        metadata.setName(name);
        snapshot.setMetadata(metadata);
        var spec = new Snapshot.SnapShotSpec();
        spec.setRawType("HTML");
        spec.setRawPatch(html);
        spec.setContentPatch(html);
        spec.setOwner("owner");
        snapshot.setSpec(spec);
        return snapshot;
    }
}
