package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.properties.ZhihuArchiveProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ZhihuAssetLocalizerImplTest {

    @Mock
    AttachmentService attachmentService;

    @Mock
    ReactiveExtensionClient client;

    ZhihuArchiveProperties properties;

    ZhihuMediaHostPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new ZhihuArchiveProperties();
        policy = new ZhihuMediaHostPolicy(properties);
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.empty());
    }

    @Test
    void shouldStoreAssetAndReturnPermalink() {
        var attachment = new Attachment();
        when(attachmentService.upload(
                        eq("policy"),
                        isNull(),
                        anyString(),
                        org.mockito.ArgumentMatchers.<Flux<DataBuffer>>any(),
                        eq(MediaType.IMAGE_JPEG)))
                .thenReturn(Mono.just(attachment));
        when(attachmentService.getPermalink(attachment)).thenReturn(Mono.just(URI.create("/upload/zhihu.jpg")));

        var localizer = localizer(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body("bytes")
                .build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isFalse();
                    assertThat(result.sourceUrl()).isEqualTo("https://pic1.zhimg.com/a.jpg");
                    assertThat(result.localUrl()).isEqualTo("/upload/zhihu.jpg");
                })
                .verifyComplete();

        var filename = ArgumentCaptor.forClass(String.class);
        verify(attachmentService)
                .upload(
                        eq("policy"),
                        isNull(),
                        filename.capture(),
                        org.mockito.ArgumentMatchers.<Flux<DataBuffer>>any(),
                        eq(MediaType.IMAGE_JPEG));
        assertThat(filename.getValue()).matches("zhihu-[0-9a-f]{16}\\.jpg");
    }

    @Test
    void shouldReuseExistingAttachmentForIdenticalContent() throws Exception {
        var existing = new Attachment();
        var metadata = new Metadata();
        metadata.setName("existing");
        existing.setMetadata(metadata);
        when(client.listAll(eq(Attachment.class), any(ListOptions.class), any(Sort.class)))
                .thenReturn(Flux.just(existing));
        when(attachmentService.getPermalink(existing)).thenReturn(Mono.just(URI.create("/upload/zhihu-reused.jpg")));

        var localizer = localizer(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body("bytes")
                .build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isFalse();
                    assertThat(result.localUrl()).isEqualTo("/upload/zhihu-reused.jpg");
                })
                .verifyComplete();

        verify(attachmentService, never())
                .upload(
                        anyString(),
                        isNull(),
                        anyString(),
                        ArgumentMatchers.<Flux<DataBuffer>>any(),
                        any(MediaType.class));
    }

    @Test
    void shouldFailWithHttpStatus() {
        var localizer = localizer(request -> Mono.just(
                ClientResponse.create(HttpStatus.NOT_FOUND).body("missing").build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.failureReason()).isEqualTo("http-404");
                })
                .verifyComplete();

        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldRejectRedirects() {
        var localizer = localizer(request -> Mono.just(ClientResponse.create(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://evil.com/a.jpg")
                .build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.failureReason()).isEqualTo("redirect-not-allowed");
                })
                .verifyComplete();

        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldNotFetchDisallowedHost() {
        var invocations = new AtomicInteger();
        var localizer = localizer(request -> {
            invocations.incrementAndGet();
            return Mono.error(new AssertionError("network must not be used"));
        });

        StepVerifier.create(localize(localizer, "https://evil.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.failureReason()).isEqualTo("host-not-allowed");
                })
                .verifyComplete();

        assertThat(invocations).hasValue(0);
        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldRejectOversizedResponseByContentLength() {
        properties.setMaxAssetSize(DataSize.ofBytes(5));
        var localizer = localizer(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, "6")
                .body("bytes")
                .build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.failureReason()).isEqualTo("size-limit-exceeded");
                })
                .verifyComplete();

        verifyNoInteractions(attachmentService);
    }

    @Test
    void shouldReportUploadFailureWithoutThrowing() {
        when(attachmentService.upload(
                        eq("policy"),
                        isNull(),
                        anyString(),
                        org.mockito.ArgumentMatchers.<Flux<DataBuffer>>any(),
                        any(MediaType.class)))
                .thenReturn(Mono.error(new IllegalStateException("storage unavailable")));

        var localizer = localizer(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body("bytes")
                .build()));

        StepVerifier.create(localize(localizer, "https://pic1.zhimg.com/a.jpg"))
                .assertNext(result -> {
                    assertThat(result.failed()).isTrue();
                    assertThat(result.failureReason()).isEqualTo("download-failed");
                })
                .verifyComplete();
    }

    private static Mono<LocalizedAsset> localize(ZhihuAssetLocalizer localizer, String url) {
        return localizer.localize(new ZhihuAsset(url, ZhihuAssetType.IMAGE), "policy");
    }

    private ZhihuAssetLocalizerImpl localizer(ExchangeFunction exchangeFunction) {
        return new ZhihuAssetLocalizerImpl(properties, policy, attachmentService, client) {
            @Override
            WebClient buildClient(long maxBytes) {
                return WebClient.builder().exchangeFunction(exchangeFunction).build();
            }
        };
    }
}
