package run.halo.app.zhihu;

import static run.halo.app.extension.index.query.Queries.equal;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.properties.ZhihuArchiveProperties;
import run.halo.app.infra.utils.HttpSecurityUtils;

/** Secure, size-limited downloader that streams Zhihu assets into attachment storage. */
@Slf4j
@Component
public class ZhihuAssetLocalizerImpl implements ZhihuAssetLocalizer {

    static final String REFERER = "https://www.zhihu.com/";

    static final String ACCEPT = "image/avif,image/webp,image/apng,image/*,*/*;q=0.8";

    private static final String HOST_NOT_ALLOWED = "host-not-allowed";

    private static final String REDIRECT_NOT_ALLOWED = "redirect-not-allowed";

    private static final String SIZE_LIMIT_EXCEEDED = "size-limit-exceeded";

    private static final String DOWNLOAD_FAILED = "download-failed";

    private static final String TIMEOUT = "timeout";

    private final ZhihuArchiveProperties properties;

    private final ZhihuMediaHostPolicy mediaHostPolicy;

    private final AttachmentService attachmentService;

    private final ReactiveExtensionClient client;

    private final ConcurrentMap<String, Mono<LocalizedAsset>> pending = new ConcurrentHashMap<>();

    public ZhihuAssetLocalizerImpl(
            ZhihuArchiveProperties properties,
            ZhihuMediaHostPolicy mediaHostPolicy,
            AttachmentService attachmentService,
            ReactiveExtensionClient client) {
        this.properties = properties;
        this.mediaHostPolicy = mediaHostPolicy;
        this.attachmentService = attachmentService;
        this.client = client;
    }

    @Override
    public Mono<LocalizedAsset> localize(ZhihuAsset asset, String policyName) {
        var url = asset == null ? null : asset.url();
        if (!StringUtils.hasText(url) || !mediaHostPolicy.isAllowed(url)) {
            return Mono.just(LocalizedAsset.failed(url, HOST_NOT_ALLOWED));
        }
        var maxBytes = maxBytes(asset);
        var uri = URI.create(url.trim());
        return buildClient(maxBytes)
                .get()
                .uri(uri)
                .header(HttpHeaders.REFERER, REFERER)
                .header(HttpHeaders.ACCEPT, ACCEPT)
                .exchangeToMono(response -> handleResponse(response, url, policyName, maxBytes))
                .timeout(properties.getResponseTimeout())
                .onErrorResume(error -> {
                    var reason = classify(error);
                    log.debug("Failed to localize Zhihu asset {}: {}", url, reason);
                    return Mono.just(LocalizedAsset.failed(url, reason));
                });
    }

    /**
     * Builds the HTTP client used for one download.
     *
     * <p>Package-private and overridable so tests can supply a canned exchange function.
     *
     * @param maxBytes maximum accepted response size
     * @return configured client
     */
    WebClient buildClient(long maxBytes) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(HttpSecurityUtils.secureHttpClient()))
                .filter(HttpSecurityUtils.maxResponseSizeFilter(maxBytes))
                .build();
    }

    private long maxBytes(ZhihuAsset asset) {
        var size = asset != null && asset.type() == ZhihuAssetType.VIDEO
                ? properties.getMaxVideoSize()
                : properties.getMaxAssetSize();
        return size.toBytes();
    }

    private Mono<LocalizedAsset> handleResponse(ClientResponse response, String url, String policyName, long maxBytes) {
        var status = response.statusCode();
        if (status.is3xxRedirection()) {
            return fail(response, url, REDIRECT_NOT_ALLOWED);
        }
        if (!status.is2xxSuccessful()) {
            return fail(response, url, "http-" + status.value());
        }
        var contentLength = response.headers().contentLength();
        if (contentLength.isPresent() && contentLength.getAsLong() > maxBytes) {
            return fail(response, url, SIZE_LIMIT_EXCEEDED);
        }
        var mediaType = response.headers()
                .contentType()
                .orElseGet(() -> MediaTypeFactory.getMediaType(url).orElse(MediaType.APPLICATION_OCTET_STREAM));
        return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class))
                .flatMap(buffer -> {
                    var bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    if (bytes.length > maxBytes) {
                        return Mono.just(LocalizedAsset.failed(url, SIZE_LIMIT_EXCEEDED));
                    }
                    var filename = buildFilename(bytes, url, mediaType);
                    return store(filename, url, bytes, mediaType, policyName);
                })
                .defaultIfEmpty(LocalizedAsset.failed(url, DOWNLOAD_FAILED));
    }

    /** Stores the asset once per content hash, sharing in-flight work between concurrent callers. */
    private Mono<LocalizedAsset> store(
            String filename, String url, byte[] bytes, MediaType mediaType, String policyName) {
        return Mono.defer(() -> pending.computeIfAbsent(
                filename,
                key -> doStore(key, url, bytes, mediaType, policyName)
                        .doFinally(signal -> pending.remove(key))
                        .cache()));
    }

    private Mono<LocalizedAsset> doStore(
            String filename, String url, byte[] bytes, MediaType mediaType, String policyName) {
        return findExisting(filename)
                .flatMap(this::permalinkOf)
                .map(permalink -> LocalizedAsset.localized(url, permalink))
                .switchIfEmpty(Mono.defer(() -> upload(filename, url, bytes, mediaType, policyName)));
    }

    private Mono<LocalizedAsset> upload(
            String filename, String url, byte[] bytes, MediaType mediaType, String policyName) {
        var buffer = new DefaultDataBufferFactory().wrap(bytes);
        return attachmentService
                .upload(policyName, null, filename, Flux.just(buffer), mediaType)
                .flatMap(this::permalinkOf)
                .map(permalink -> LocalizedAsset.localized(url, permalink))
                .defaultIfEmpty(LocalizedAsset.failed(url, DOWNLOAD_FAILED));
    }

    private Mono<Attachment> findExisting(String filename) {
        var options = new ListOptions().setFieldSelector(FieldSelector.of(equal("spec.displayName", filename)));
        return client.listAll(Attachment.class, options, Sort.unsorted()).next();
    }

    private Mono<String> permalinkOf(Attachment attachment) {
        var fallback =
                attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
        return attachmentService.getPermalink(attachment).map(URI::toString).switchIfEmpty(Mono.justOrEmpty(fallback));
    }

    private static Mono<LocalizedAsset> fail(ClientResponse response, String url, String reason) {
        return response.releaseBody().then(Mono.just(LocalizedAsset.failed(url, reason)));
    }

    private static String classify(Throwable error) {
        if (error instanceof DataBufferLimitException) {
            return SIZE_LIMIT_EXCEEDED;
        }
        if (error instanceof TimeoutException) {
            return TIMEOUT;
        }
        return DOWNLOAD_FAILED;
    }

    static String buildFilename(byte[] content, String url, MediaType mediaType) {
        var extension = extensionOf(url);
        if (extension.isEmpty()) {
            extension = extensionOfMediaType(mediaType);
        }
        var hash = sha256Hex(content).substring(0, 16);
        return extension.isEmpty() ? "zhihu-" + hash : "zhihu-" + hash + "." + extension;
    }

    private static String extensionOf(String url) {
        final String path;
        try {
            path = URI.create(url.trim()).getPath();
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (!StringUtils.hasText(path)) {
            return "";
        }
        var lastSegment = path.substring(path.lastIndexOf('/') + 1);
        var dot = lastSegment.lastIndexOf('.');
        if (dot < 0 || dot == lastSegment.length() - 1) {
            return "";
        }
        var extension = lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return extension.matches("[a-z0-9]{1,5}") ? extension : "";
    }

    private static String extensionOfMediaType(MediaType mediaType) {
        if (mediaType == null || mediaType.getSubtype() == null) {
            return "";
        }
        var subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return switch (subtype) {
            case "jpeg", "pjpeg" -> "jpg";
            case "svg+xml" -> "svg";
            case "x-icon", "vnd.microsoft.icon" -> "ico";
            default -> {
                var normalized = subtype.replaceAll("[^a-z0-9]", "");
                yield normalized.matches("[a-z0-9]{1,5}") ? normalized : "";
            }
        };
    }

    private static String sha256Hex(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
