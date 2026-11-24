package run.halo.app.zhihu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * Sanitizes client-supplied archive HTML with an explicit allowlist.
 *
 * <p>The archive payload comes from a browser userscript and must never be trusted. This sanitizer keeps the semantic
 * formatting needed by an article snapshot, removes active content, restricts class names to the archive stylesheet
 * contract, and hardens links and images.
 */
@Component
public class ZhihuHtmlSanitizer {

    private static final Set<String> FORBIDDEN_TAGS = Set.of(
            "script",
            "style",
            "iframe",
            "object",
            "embed",
            "form",
            "input",
            "button",
            "textarea",
            "select",
            "option",
            "svg",
            "math",
            "link",
            "meta",
            "base",
            "frame",
            "frameset",
            "noscript");

    private static final Set<String> ALLOWED_CLASSES = Set.of(
            "zhihu-archive",
            "zhihu-archive-banner",
            "zhihu-archive-article",
            "zhihu-archive-comments",
            "zhihu-archive-comment",
            "zhihu-archive-reply",
            "zhihu-archive-truncated",
            "zhihu-archive-video",
            "zhihu-archive-placeholder",
            "zhihu-archive-broken-asset",
            "zhihu-archive-footer",
            "zhihu-archive-meta",
            "zhihu-archive-math",
            "hljs");

    private static final String BROKEN_ASSET_CLASS = "zhihu-archive-broken-asset";

    private static final String LINK_REL = "nofollow noopener noreferrer";

    private static final String FORBIDDEN_TAG_SELECTOR = String.join(",", FORBIDDEN_TAGS);

    private final Safelist safelist;

    public ZhihuHtmlSanitizer() {
        this.safelist = Safelist.relaxed()
                .addTags("figure", "figcaption", "s")
                .addAttributes("a", "target", "rel")
                .addAttributes("img", "src", "alt", "width", "height")
                .addAttributes("span", "class", "data-tex")
                .addAttributes("figure", "class", "data-video-id")
                .addAttributes("figcaption", "class")
                .addAttributes("div", "class")
                .addAttributes("code", "class")
                .addAttributes("pre", "class")
                .preserveRelativeLinks(true);
    }

    /**
     * Sanitizes the given HTML fragment.
     *
     * @param html untrusted HTML, may be {@code null}
     * @return sanitized body HTML, never {@code null}
     */
    public String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var outputSettings = new Document.OutputSettings().prettyPrint(false);
        // <noscript> fallbacks duplicate the neighbouring <img>: Jsoup.clean would drop the
        // tag but keep its children, producing the same image twice in one figure.
        var preparsed = Jsoup.parseBodyFragment(html);
        preparsed.outputSettings().prettyPrint(false);
        preparsed.body().select("noscript").remove();
        var cleaned = Jsoup.clean(preparsed.body().html(), "", safelist, outputSettings);
        var document = Jsoup.parseBodyFragment(cleaned);
        document.outputSettings().prettyPrint(false);
        var body = document.body();

        body.select(FORBIDDEN_TAG_SELECTOR).remove();
        removeDuplicateFigureImages(body);
        body.select("[class]").forEach(ZhihuHtmlSanitizer::filterClasses);
        body.select("a[href]").forEach(ZhihuHtmlSanitizer::hardenLink);
        body.select("img").forEach(ZhihuHtmlSanitizer::hardenImage);
        body.select("[^on], [style]").forEach(ZhihuHtmlSanitizer::removeActiveAttributes);
        removeComments(body);
        return body.html().trim();
    }

    private static void removeDuplicateFigureImages(Element body) {
        for (var figure : body.select("figure")) {
            var seen = new HashSet<String>();
            for (var image : new ArrayList<>(figure.select("img[src]"))) {
                if (!seen.add(image.attr("src"))) {
                    image.remove();
                }
            }
        }
    }

    private static void filterClasses(Element element) {
        var tokens = element.classNames();
        var kept = new ArrayList<String>(tokens.size());
        var allowCodeClasses = "code".equals(element.normalName()) || "pre".equals(element.normalName());
        for (var token : tokens) {
            if (ALLOWED_CLASSES.contains(token) || (allowCodeClasses && token.startsWith("language-"))) {
                kept.add(token);
            }
        }
        if (kept.isEmpty()) {
            element.removeAttr("class");
        } else {
            element.attr("class", String.join(" ", kept));
        }
    }

    private static void hardenLink(Element link) {
        var href = link.attr("href");
        if (!isSafeUrl(href)) {
            link.removeAttr("href");
            link.removeAttr("target");
            link.removeAttr("rel");
            return;
        }
        link.attr("target", "_blank");
        link.attr("rel", LINK_REL);
    }

    private static void hardenImage(Element image) {
        var src = image.attr("src");
        if (isSafeUrl(src)) {
            return;
        }
        image.removeAttr("src");
        image.addClass(BROKEN_ASSET_CLASS);
    }

    private static boolean isSafeUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        var probe = value.replaceAll("[\\u0000-\\u0020\\u007F]", "").toLowerCase(Locale.ROOT);
        if (probe.isEmpty()) {
            return false;
        }
        if (probe.startsWith("http://") || probe.startsWith("https://")) {
            return true;
        }
        return probe.startsWith("#")
                || probe.startsWith("/")
                || probe.startsWith("./")
                || probe.startsWith("../")
                || probe.startsWith("?");
    }

    private static void removeActiveAttributes(Element element) {
        var attributeKeys = element.attributes().asList().stream()
                .map(attribute -> attribute.getKey())
                .toList();
        for (var key : attributeKeys) {
            var normalized = key.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("on") || "style".equals(normalized)) {
                element.removeAttr(key);
            }
        }
    }

    private static void removeComments(Node node) {
        for (var child : List.copyOf(new ArrayList<>(node.childNodes()))) {
            if (child instanceof Comment) {
                child.remove();
            } else {
                removeComments(child);
            }
        }
    }
}
