package run.halo.app.zhihu;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Rewrites the assets of already-sanitized archive HTML.
 *
 * <p>Equations are converted to text math, video figures keep their external link while their poster is localized, and
 * all remaining images are downloaded into attachment storage. Every failure is replaced by a placeholder linking to
 * the original asset so that a snapshot is never aborted by a single unavailable asset.
 */
@Component
public class ZhihuAssetHtmlRewriter {

    private static final String MATH_CLASS = "zhihu-archive-math";

    private static final String PLACEHOLDER_CLASS = "zhihu-archive-placeholder";

    private static final String VIDEO_FIGURE_SELECTOR = "figure.zhihu-archive-video";

    private static final String EQUATION_IMAGE_SELECTOR = "img[src*=\"equation?tex=\"]";

    private static final String EMPTY_RESULT = "empty-result";

    private final ZhihuAssetLocalizer localizer;

    public ZhihuAssetHtmlRewriter(ZhihuAssetLocalizer localizer) {
        this.localizer = localizer;
    }

    /**
     * Rewrites the assets of sanitized HTML.
     *
     * @param sanitizedHtml sanitized archive HTML
     * @param assets additional assets referenced by the payload, may be {@code null}
     * @param policyName attachment policy used to store localized assets
     * @return rewrite result
     */
    public Mono<ZhihuAssetRewriteResult> rewrite(String sanitizedHtml, List<ZhihuAsset> assets, String policyName) {
        if (!StringUtils.hasText(sanitizedHtml)) {
            return Mono.just(new ZhihuAssetRewriteResult("", 0, 0));
        }
        var document = Jsoup.parseBodyFragment(sanitizedHtml);
        document.outputSettings().prettyPrint(false);
        var body = document.body();

        rewriteEquations(body);

        var tasks = new LinkedHashMap<String, Task>();
        Set<Element> posterElements = Collections.newSetFromMap(new IdentityHashMap<>());
        collectVideoPosters(body, tasks, posterElements);
        collectImages(body, tasks, posterElements);
        collectSuppliedAssets(assets, tasks);

        return Flux.fromIterable(tasks.values())
                .flatMap(
                        task -> localizer
                                .localize(new ZhihuAsset(task.url, task.type), policyName)
                                .defaultIfEmpty(LocalizedAsset.failed(task.url, EMPTY_RESULT))
                                .map(result -> new Outcome(task, result)),
                        4)
                .collectList()
                .map(outcomes -> applyOutcomes(body, outcomes));
    }

    private static void rewriteEquations(Element body) {
        body.select("span[data-tex]").forEach(span -> {
            span.addClass(MATH_CLASS);
            if (span.text().isBlank()) {
                span.text(span.attr("data-tex"));
            }
        });
        for (var image : body.select(EQUATION_IMAGE_SELECTOR)) {
            var tex = texParameter(image.attr("src"));
            if (tex == null) {
                continue;
            }
            var span = new Element("span")
                    .addClass(MATH_CLASS)
                    .attr("data-tex", tex)
                    .text(tex);
            image.replaceWith(span);
        }
    }

    private static void collectVideoPosters(Element body, Map<String, Task> tasks, Set<Element> posterElements) {
        for (var figure : body.select(VIDEO_FIGURE_SELECTOR)) {
            for (var image : figure.select("img[src]")) {
                posterElements.add(image);
                var src = image.attr("src");
                if (isAbsoluteHttpUrl(src)) {
                    addTask(tasks, src, ZhihuAssetType.POSTER, image);
                }
            }
        }
    }

    private static void collectImages(Element body, Map<String, Task> tasks, Set<Element> posterElements) {
        for (var image : body.select("img[src]")) {
            if (posterElements.contains(image)) {
                continue;
            }
            var src = image.attr("src");
            if (isAbsoluteHttpUrl(src)) {
                addTask(tasks, src, ZhihuAssetType.IMAGE, image);
            }
        }
    }

    private static void collectSuppliedAssets(List<ZhihuAsset> assets, Map<String, Task> tasks) {
        if (assets == null) {
            return;
        }
        for (var asset : assets) {
            if (asset == null || !StringUtils.hasText(asset.url())) {
                continue;
            }
            if (asset.type() == ZhihuAssetType.VIDEO || asset.type() == ZhihuAssetType.EQUATION) {
                continue;
            }
            var type = asset.type() == null ? ZhihuAssetType.IMAGE : asset.type();
            addTask(tasks, asset.url(), type, null);
        }
    }

    private static void addTask(Map<String, Task> tasks, String url, ZhihuAssetType type, Element target) {
        var task = tasks.computeIfAbsent(url, key -> new Task(key, type));
        if (target != null) {
            task.targets.add(target);
        }
    }

    private static ZhihuAssetRewriteResult applyOutcomes(Element body, List<Outcome> outcomes) {
        int failures = 0;
        int localized = 0;
        for (var outcome : outcomes) {
            var result = outcome.result;
            if (result.failed()) {
                failures++;
                for (var target : outcome.task.targets) {
                    target.replaceWith(placeholder(result.sourceUrl()));
                }
            } else {
                localized++;
                for (var target : outcome.task.targets) {
                    target.attr("src", result.localUrl());
                }
            }
        }
        return new ZhihuAssetRewriteResult(body.html().trim(), failures, localized);
    }

    private static Element placeholder(String url) {
        return new Element("a")
                .addClass(PLACEHOLDER_CLASS)
                .attr("href", url)
                .attr("target", "_blank")
                .attr("rel", "nofollow noopener noreferrer")
                .text(url);
    }

    static String texParameter(String src) {
        if (!StringUtils.hasText(src)) {
            return null;
        }
        String query = null;
        try {
            query = URI.create(src.trim()).getRawQuery();
        } catch (IllegalArgumentException ignored) {
            // Fall back to manual extraction for non-URI values.
        }
        if (query == null) {
            var questionMark = src.indexOf('?');
            if (questionMark >= 0 && questionMark < src.length() - 1) {
                query = src.substring(questionMark + 1);
            }
        }
        if (query == null) {
            return null;
        }
        for (var pair : query.split("&")) {
            var equals = pair.indexOf('=');
            if (equals <= 0 || !"tex".equals(pair.substring(0, equals))) {
                continue;
            }
            var raw = pair.substring(equals + 1);
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
        return null;
    }

    private static boolean isAbsoluteHttpUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        var normalized = url.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private record Outcome(Task task, LocalizedAsset result) {}

    private static final class Task {

        private final String url;

        private final ZhihuAssetType type;

        private final List<Element> targets = new ArrayList<>();

        private Task(String url, ZhihuAssetType type) {
            this.url = url;
            this.type = type;
        }
    }
}
