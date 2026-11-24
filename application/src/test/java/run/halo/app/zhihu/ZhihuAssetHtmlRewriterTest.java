package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ZhihuAssetHtmlRewriterTest {

    private static final String POLICY = "policy";

    private final List<ZhihuAsset> requested = new CopyOnWriteArrayList<>();

    @Test
    void shouldReplaceImageWithLocalUrlAndKeepAttributes() {
        var rewriter = rewriter(asset -> LocalizedAsset.localized(asset.url(), "https://cdn.local/a.jpg"));

        var result = rewrite(
                        rewriter,
                        "<p><img src=\"https://pic1.zhimg.com/a.jpg\" alt=\"hi\" width=\"10\" height=\"20\"></p>")
                .block();

        assertThat(result).isNotNull();
        assertThat(result.localizedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(result.html())
                .contains("src=\"https://cdn.local/a.jpg\"")
                .contains("alt=\"hi\"")
                .contains("width=\"10\"")
                .contains("height=\"20\"");
    }

    @Test
    void shouldReplaceFailedImageWithPlaceholder() {
        var url = "https://pic1.zhimg.com/broken.jpg";
        var rewriter = rewriter(asset -> LocalizedAsset.failed(asset.url(), "http-404"));

        var result =
                rewrite(rewriter, "<p><img src=\"" + url + "\" alt=\"hi\"></p>").block();

        assertThat(result).isNotNull();
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.localizedCount()).isZero();
        assertThat(result.html())
                .contains("class=\"zhihu-archive-placeholder\"")
                .contains("href=\"" + url + "\"")
                .contains(">" + url + "<");
    }

    @Test
    void shouldPreserveAndFillTexSpans() {
        var rewriter = rewriter(asset -> LocalizedAsset.localized(asset.url(), "https://cdn.local/x.jpg"));

        var result = rewrite(rewriter, "<p><span data-tex=\"x^2\"></span><span data-tex=\"y\">kept</span></p>")
                .block();

        assertThat(result).isNotNull();
        assertThat(result.html())
                .contains("class=\"zhihu-archive-math\"")
                .contains("data-tex=\"x^2\"")
                .contains(">x^2<")
                .contains(">kept<");
        assertThat(requested).isEmpty();
    }

    @Test
    void shouldConvertEquationImageToMathSpanWithoutDownloading() {
        var rewriter = rewriter(asset -> LocalizedAsset.localized(asset.url(), "https://cdn.local/x.jpg"));

        var result = rewrite(
                        rewriter, "<p><img src=\"https://www.zhihu.com/equation?tex=a+b%5E2\" alt=\"equation\"></p>")
                .block();

        assertThat(result).isNotNull();
        assertThat(result.html())
                .contains("class=\"zhihu-archive-math\"")
                .contains("data-tex=\"a b^2\"")
                .contains(">a b^2<")
                .doesNotContain("<img");
        assertThat(requested).isEmpty();
    }

    @Test
    void shouldLocalizeVideoPosterAndKeepVideoMetadata() {
        var rewriter = rewriter(asset -> LocalizedAsset.localized(asset.url(), "https://cdn.local/poster.jpg"));

        var result = rewrite(
                        rewriter,
                        "<figure class=\"zhihu-archive-video\" data-video-id=\"v1\">"
                                + "<img src=\"https://pic1.zhimg.com/poster.jpg\" alt=\"poster\">"
                                + "<a href=\"https://www.zhihu.com/video/v1\" target=\"_blank\""
                                + " rel=\"nofollow noopener noreferrer\">watch</a>"
                                + "</figure>")
                .block();

        assertThat(result).isNotNull();
        assertThat(result.localizedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(requested).singleElement().satisfies(asset -> {
            assertThat(asset.url()).isEqualTo("https://pic1.zhimg.com/poster.jpg");
            assertThat(asset.type()).isEqualTo(ZhihuAssetType.POSTER);
        });
        assertThat(result.html())
                .contains("data-video-id=\"v1\"")
                .contains("href=\"https://www.zhihu.com/video/v1\"")
                .contains("src=\"https://cdn.local/poster.jpg\"");
    }

    @Test
    void shouldReportMixedSuccessAndFailure() {
        var rewriter = rewriter(asset -> asset.url().endsWith("ok.jpg")
                ? LocalizedAsset.localized(asset.url(), "https://cdn.local/ok.jpg")
                : LocalizedAsset.failed(asset.url(), "http-404"));

        var result = rewrite(
                        rewriter,
                        "<p><img src=\"https://pic1.zhimg.com/ok.jpg\">"
                                + "<img src=\"https://pic1.zhimg.com/bad.jpg\"></p>")
                .block();

        assertThat(result).isNotNull();
        assertThat(result.localizedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.html())
                .contains("src=\"https://cdn.local/ok.jpg\"")
                .contains("class=\"zhihu-archive-placeholder\"")
                .contains("https://pic1.zhimg.com/bad.jpg");
    }

    @Test
    void shouldReturnEmptyResultForBlankHtml() {
        var rewriter = rewriter(asset -> LocalizedAsset.localized(asset.url(), "unused"));

        StepVerifier.create(rewriter.rewrite(null, List.of(), POLICY))
                .assertNext(result -> assertThat(result).isEqualTo(new ZhihuAssetRewriteResult("", 0, 0)))
                .verifyComplete();
        StepVerifier.create(rewriter.rewrite("   ", List.of(), POLICY))
                .assertNext(result -> assertThat(result).isEqualTo(new ZhihuAssetRewriteResult("", 0, 0)))
                .verifyComplete();
    }

    private Mono<ZhihuAssetRewriteResult> rewrite(ZhihuAssetHtmlRewriter rewriter, String html) {
        return rewriter.rewrite(html, List.of(), POLICY);
    }

    private ZhihuAssetHtmlRewriter rewriter(Function<ZhihuAsset, LocalizedAsset> stub) {
        ZhihuAssetLocalizer localizer = (asset, policyName) -> {
            requested.add(asset);
            return Mono.just(stub.apply(asset));
        };
        return new ZhihuAssetHtmlRewriter(localizer);
    }
}
