package run.halo.app.core.endpoint.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.Metadata;
import run.halo.app.zhihu.ZhihuArchiveCreateRequest;
import run.halo.app.zhihu.ZhihuArchivePayload;
import run.halo.app.zhihu.ZhihuArchiveService;
import run.halo.app.zhihu.ZhihuArticle;
import run.halo.app.zhihu.ZhihuAssets;
import run.halo.app.zhihu.ZhihuAuthor;
import run.halo.app.zhihu.ZhihuCapture;
import run.halo.app.zhihu.ZhihuComments;
import run.halo.app.zhihu.ZhihuSource;
import run.halo.app.zhihu.ZhihuSourceType;

/**
 * Tests for {@link ZhihuArchiveEndpoint}.
 *
 * <p>Production authentication and authorization for these routes are enforced by {@code WebServerSecurityConfig} for
 * {@code /apis/**} together with {@code role-template-zhihu-archives.yaml}. These tests exercise routing, request
 * validation, and service delegation only; no security filter chain is installed.
 *
 * @since 2.22.0
 */
@ExtendWith(MockitoExtension.class)
class ZhihuArchiveEndpointTest {

    @Mock
    private ZhihuArchiveService zhihuArchiveService;

    @Spy
    private SpringValidatorAdapter validator =
            new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator());

    @InjectMocks
    private ZhihuArchiveEndpoint endpoint;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToRouterFunction(endpoint.endpoint()).build();
    }

    @Test
    void shouldListZhihuArchivesWithDefaults() {
        when(zhihuArchiveService.list()).thenReturn(Flux.just(singlePage("archive-1"), singlePage("archive-2")));

        webTestClient
                .get()
                .uri("/zhihu-archives")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.page")
                .isEqualTo(1)
                .jsonPath("$.size")
                .isEqualTo(20)
                .jsonPath("$.total")
                .isEqualTo(2)
                .jsonPath("$.items.length()")
                .isEqualTo(2)
                .jsonPath("$.items[0].metadata.name")
                .isEqualTo("archive-1")
                .jsonPath("$.items[1].metadata.name")
                .isEqualTo("archive-2");

        verify(zhihuArchiveService).list();
    }

    @Test
    void shouldListZhihuArchivesWithRequestedPageAndSize() {
        when(zhihuArchiveService.list())
                .thenReturn(Flux.just(singlePage("archive-1"), singlePage("archive-2"), singlePage("archive-3")));

        webTestClient
                .get()
                .uri("/zhihu-archives?page=2&size=1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.page")
                .isEqualTo(2)
                .jsonPath("$.size")
                .isEqualTo(1)
                .jsonPath("$.total")
                .isEqualTo(3)
                .jsonPath("$.items.length()")
                .isEqualTo(1)
                .jsonPath("$.items[0].metadata.name")
                .isEqualTo("archive-2");

        verify(zhihuArchiveService).list();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "size=101", "page=abc"})
    void shouldRejectInvalidPagination(String query) {
        webTestClient
                .get()
                .uri("/zhihu-archives?" + query)
                .exchange()
                .expectStatus()
                .isBadRequest();

        verifyNoInteractions(zhihuArchiveService);
    }

    @Test
    void shouldCreateZhihuArchiveForAnonymousOwner() {
        var createRequest = createRequest();
        when(zhihuArchiveService.create(eq(createRequest), eq("anonymous")))
                .thenReturn(Mono.just(singlePage("archive-1")));

        webTestClient
                .post()
                .uri("/zhihu-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.metadata.name")
                .isEqualTo("archive-1");

        verify(zhihuArchiveService).create(eq(createRequest), eq("anonymous"));
    }

    @Test
    void shouldRejectCreateWithoutPayload() {
        var createRequest = new ZhihuArchiveCreateRequest(null, "default-policy", null);

        webTestClient
                .post()
                .uri("/zhihu-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest)
                .exchange()
                .expectStatus()
                .isBadRequest();

        verifyNoInteractions(zhihuArchiveService);
    }

    @Test
    void shouldRejectCreateWithBlankPolicyName() {
        var createRequest = new ZhihuArchiveCreateRequest(validPayload(), " ", null);

        webTestClient
                .post()
                .uri("/zhihu-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest)
                .exchange()
                .expectStatus()
                .isBadRequest();

        verifyNoInteractions(zhihuArchiveService);
    }

    @Test
    void shouldRejectCreateWithUnsupportedVisibility() {
        var createRequest = new ZhihuArchiveCreateRequest(validPayload(), "default-policy", "INTERNAL");

        webTestClient
                .post()
                .uri("/zhihu-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest)
                .exchange()
                .expectStatus()
                .isBadRequest();

        verifyNoInteractions(zhihuArchiveService);
    }

    @Test
    void shouldTransferVideo() {
        when(zhihuArchiveService.transferVideo("archive-1")).thenReturn(Mono.just(singlePage("archive-1")));

        webTestClient
                .post()
                .uri("/zhihu-archives/archive-1/transfer-video")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.metadata.name")
                .isEqualTo("archive-1");

        verify(zhihuArchiveService).transferVideo("archive-1");
    }

    @Test
    void shouldMapTransferVideoFailureToBadRequest() {
        when(zhihuArchiveService.transferVideo("archive-1"))
                .thenReturn(Mono.error(new IllegalStateException("transfer failed")));

        webTestClient
                .post()
                .uri("/zhihu-archives/archive-1/transfer-video")
                .exchange()
                .expectStatus()
                .isBadRequest();

        verify(zhihuArchiveService).transferVideo("archive-1");
    }

    @Test
    void shouldDeleteZhihuArchive() {
        when(zhihuArchiveService.delete("archive-1")).thenReturn(Mono.empty());

        webTestClient
                .delete()
                .uri("/zhihu-archives/archive-1")
                .exchange()
                .expectStatus()
                .isNoContent();

        verify(zhihuArchiveService).delete("archive-1");
    }

    @Test
    void shouldMapDeleteFailureToBadRequest() {
        when(zhihuArchiveService.delete("archive-1"))
                .thenReturn(Mono.error(new IllegalArgumentException("not an archive")));

        webTestClient
                .delete()
                .uri("/zhihu-archives/archive-1")
                .exchange()
                .expectStatus()
                .isBadRequest();

        verify(zhihuArchiveService).delete("archive-1");
    }

    @Test
    void shouldRebuildArchiveIndex() {
        when(zhihuArchiveService.rebuildIndex("anonymous")).thenReturn(Mono.just(singlePage("zhihu-archive-index")));

        webTestClient
                .post()
                .uri("/zhihu-archives/index/rebuild")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.metadata.name")
                .isEqualTo("zhihu-archive-index");

        verify(zhihuArchiveService).rebuildIndex("anonymous");
    }

    @Test
    void shouldExposeAllRoutesAtExpectedPaths() {
        when(zhihuArchiveService.list()).thenReturn(Flux.empty());
        when(zhihuArchiveService.create(any(ZhihuArchiveCreateRequest.class), anyString()))
                .thenReturn(Mono.just(singlePage("archive-1")));
        when(zhihuArchiveService.transferVideo("archive-1")).thenReturn(Mono.just(singlePage("archive-1")));
        when(zhihuArchiveService.delete("archive-1")).thenReturn(Mono.empty());
        when(zhihuArchiveService.rebuildIndex("anonymous")).thenReturn(Mono.just(singlePage("zhihu-archive-index")));

        webTestClient.get().uri("/zhihu-archives").exchange().expectStatus().isOk();
        webTestClient
                .post()
                .uri("/zhihu-archives")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(createRequest())
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .post()
                .uri("/zhihu-archives/archive-1/transfer-video")
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .post()
                .uri("/zhihu-archives/index/rebuild")
                .exchange()
                .expectStatus()
                .isOk();
        webTestClient
                .delete()
                .uri("/zhihu-archives/archive-1")
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    @Test
    void shouldUseConsoleApiGroupVersion() {
        var groupVersion = endpoint.groupVersion();

        assertEquals("api.console.halo.run", groupVersion.group());
        assertEquals("v1alpha1", groupVersion.version());
    }

    private static SinglePage singlePage(String name) {
        var page = new SinglePage();
        var metadata = new Metadata();
        metadata.setName(name);
        page.setMetadata(metadata);
        return page;
    }

    private static ZhihuArchiveCreateRequest createRequest() {
        return new ZhihuArchiveCreateRequest(validPayload(), "default-policy", null);
    }

    private static ZhihuArchivePayload validPayload() {
        var author = new ZhihuAuthor("Author", "https://www.zhihu.com/people/author");
        var source = new ZhihuSource("https://zhuanlan.zhihu.com/p/1", ZhihuSourceType.ARTICLE, "1");
        var article = new ZhihuArticle("A title", author, Instant.parse("2024-01-01T00:00:00Z"), 10, 2, "<p>Hello</p>");
        var comments = new ZhihuComments(0, List.of());
        var capture = new ZhihuCapture(Instant.parse("2024-02-01T00:00:00Z"), "1.0.0");
        return new ZhihuArchivePayload(1, source, article, comments, capture, new ZhihuAssets(List.of()));
    }
}
