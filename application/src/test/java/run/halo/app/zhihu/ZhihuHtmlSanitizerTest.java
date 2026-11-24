package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

class ZhihuHtmlSanitizerTest {

    private final ZhihuHtmlSanitizer sanitizer = new ZhihuHtmlSanitizer();

    private org.jsoup.nodes.Element sanitizedBody(String html) {
        return Jsoup.parseBodyFragment(sanitizer.sanitize(html)).body();
    }

    @Test
    void shouldRemoveScriptAndKeepParagraph() {
        var body = sanitizedBody("<script>alert(1)</script><p>ok</p>");

        assertThat(body.select("script")).isEmpty();
        assertThat(body.select("p").text()).isEqualTo("ok");
    }

    @Test
    void shouldRemoveIframe() {
        var body = sanitizedBody("<iframe src=\"https://evil.test\"></iframe>");

        assertThat(body.select("iframe")).isEmpty();
        assertThat(body.text()).isEmpty();
    }

    @Test
    void shouldRemoveActiveAndFormElements() {
        var body = sanitizedBody("""
                <object data="x"></object>
                <embed src="x">
                <form><input value="x"><button>b</button></form>
                <svg><circle></circle></svg>
                <math><mi>x</mi></math>
                """);

        assertThat(body.select("object, embed, form, input, button, svg, math")).isEmpty();
    }

    @Test
    void shouldRemoveEventHandlersAndStyles() {
        var body = sanitizedBody("<p onclick=\"alert(1)\" style=\"color:red\">x</p>");

        var paragraph = body.selectFirst("p");
        assertThat(paragraph).isNotNull();
        assertThat(paragraph.hasAttr("onclick")).isFalse();
        assertThat(paragraph.hasAttr("style")).isFalse();
        assertThat(paragraph.text()).isEqualTo("x");
    }

    @Test
    void shouldRemoveJavascriptHrefButKeepText() {
        var body = sanitizedBody("<a href=\"javascript:alert(1)\">x</a>");

        var link = body.selectFirst("a");
        assertThat(link).isNotNull();
        assertThat(link.hasAttr("href")).isFalse();
        assertThat(link.text()).isEqualTo("x");
        assertThat(sanitizer.sanitize("<a href=\"JaVaScRiPt:alert(1)\">x</a>"))
                .doesNotContainIgnoringCase("javascript");
    }

    @Test
    void shouldHardenExternalLinks() {
        var body = sanitizedBody("<a href=\"https://example.com\">x</a>");

        var link = body.selectFirst("a");
        assertThat(link).isNotNull();
        assertThat(link.attr("href")).isEqualTo("https://example.com");
        assertThat(link.attr("target")).isEqualTo("_blank");
        assertThat(link.attr("rel")).contains("noopener", "noreferrer", "nofollow");
    }

    @Test
    void shouldReplaceUnsafeImageSourceWithPlaceholderClass() {
        var body = sanitizedBody("<img src=\"data:image/png;base64,AAA\" alt=\"a\">");

        var image = body.selectFirst("img");
        assertThat(image).isNotNull();
        assertThat(image.hasAttr("src")).isFalse();
        assertThat(image.hasClass("zhihu-archive-broken-asset")).isTrue();
        assertThat(image.attr("alt")).isEqualTo("a");
    }

    @Test
    void shouldPreserveAllowedImageAttributes() {
        var body = sanitizedBody("<img src=\"https://pic1.zhimg.com/a.jpg\" alt=\"a\" width=\"10\" height=\"20\">");

        var image = body.selectFirst("img");
        assertThat(image).isNotNull();
        assertThat(image.attr("src")).isEqualTo("https://pic1.zhimg.com/a.jpg");
        assertThat(image.attr("alt")).isEqualTo("a");
        assertThat(image.attr("width")).isEqualTo("10");
        assertThat(image.attr("height")).isEqualTo("20");
    }

    @Test
    void shouldDropNoscriptFallbackImage() {
        var body = sanitizedBody("""
                <figure><noscript><img src="https://pic1.zhimg.com/a.jpg"></noscript>\
                <img src="https://pic1.zhimg.com/a.jpg"></figure>
                """);

        assertThat(body.select("noscript")).isEmpty();
        assertThat(body.select("figure img")).hasSize(1);
    }

    @Test
    void shouldDropDuplicateImagesInsideFigure() {
        var body = sanitizedBody("""
                <figure><img src="https://pic1.zhimg.com/a.jpg"><img src="https://pic1.zhimg.com/a.jpg"></figure>
                """);

        assertThat(body.select("figure img")).hasSize(1);
    }

    @Test
    void shouldKeepDistinctImagesInsideFigure() {
        var body = sanitizedBody("""
                <figure><img src="https://pic1.zhimg.com/a.jpg"><img src="https://pic1.zhimg.com/b.jpg"></figure>
                """);

        assertThat(body.select("figure img")).hasSize(2);
    }

    @Test
    void shouldPreserveTexSource() {
        var body = sanitizedBody("<span data-tex=\"x^2\">x^2</span>");

        var span = body.selectFirst("span");
        assertThat(span).isNotNull();
        assertThat(span.attr("data-tex")).isEqualTo("x^2");
    }

    @Test
    void shouldPreserveVideoFigureAndFilterClasses() {
        var body = sanitizedBody("""
                <figure class="zhihu-archive-video" data-video-id="123">
                  <figcaption>v</figcaption>
                </figure>
                <div class="evil zhihu-archive">x</div>
                <div class="evil">y</div>
                """);

        var figure = body.selectFirst("figure");
        assertThat(figure).isNotNull();
        assertThat(figure.hasClass("zhihu-archive-video")).isTrue();
        assertThat(figure.attr("data-video-id")).isEqualTo("123");
        assertThat(figure.selectFirst("figcaption")).isNotNull();

        var archiveDiv = body.selectFirst("div.zhihu-archive");
        assertThat(archiveDiv).isNotNull();
        assertThat(archiveDiv.classNames()).containsExactly("zhihu-archive");
        assertThat(body.select("div").stream().noneMatch(div -> div.hasClass("evil")))
                .isTrue();
    }

    @Test
    void shouldPreserveCodeClassesAndWhitespace() {
        var body = sanitizedBody("<pre><code class=\"language-java hljs\">int a;\n  int b;</code></pre>");

        var code = body.selectFirst("pre code");
        assertThat(code).isNotNull();
        assertThat(code.hasClass("language-java")).isTrue();
        assertThat(code.hasClass("hljs")).isTrue();
        assertThat(code.wholeText()).isEqualTo("int a;\n  int b;");
    }

    @Test
    void shouldPreserveTables() {
        var body = sanitizedBody("""
                <table>
                  <thead><tr><th>h</th></tr></thead>
                  <tbody><tr><td>c</td></tr></tbody>
                </table>
                """);

        assertThat(body.select("table")).hasSize(1);
        assertThat(body.select("th").text()).isEqualTo("h");
        assertThat(body.select("td").text()).isEqualTo("c");
    }

    @Test
    void shouldRemoveComments() {
        var sanitized = sanitizer.sanitize("<p>x</p><!-- secret -->");

        assertThat(sanitized).doesNotContain("<!--").doesNotContain("secret");
    }

    @Test
    void shouldBeIdempotent() {
        var input = """
                <div class="evil zhihu-archive">
                  <script>alert(1)</script>
                  <p onclick="x()">ok</p>
                  <a href="https://example.com">link</a>
                  <img src="data:image/png;base64,AAA" alt="a">
                  <span data-tex="x^2">x</span>
                  <!-- comment -->
                </div>
                """;

        var once = sanitizer.sanitize(input);
        var twice = sanitizer.sanitize(once);

        assertThat(twice).isEqualTo(once);
    }
}
