package run.halo.app.zhihu;

import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import run.halo.app.zhihu.ZhihuNormalizedComments.ZhihuCommentThread;

/**
 * Composes the self-contained HTML document stored in an archive snapshot.
 *
 * <p>All dynamic text and attributes are written through Jsoup so that they are escaped; the already-sanitized article
 * and comment bodies are appended as parsed markup.
 */
@Component
public class ZhihuArchiveRenderer {

    private static final String SNAPSHOT_CLASS = "zhihu-archive-snapshot";

    private static final ZhihuHtmlSanitizer SANITIZER = new ZhihuHtmlSanitizer();

    private static final String LINK_REL = "nofollow noopener noreferrer";

    private static final String STYLE = """
            .zhihu-archive-snapshot { color: inherit; line-height: 1.7; }
            .zhihu-archive-snapshot .zhihu-archive-banner { border-bottom: 1px solid rgba(0, 0, 0, 0.12); margin-bottom: 1.5rem; padding-bottom: 1rem; }
            .zhihu-archive-snapshot .zhihu-archive-provenance { color: #6b7280; font-size: 0.875rem; }
            .zhihu-archive-snapshot .zhihu-archive-warning { color: #92400e; font-size: 0.875rem; }
            .zhihu-archive-snapshot .zhihu-archive-article img,
            .zhihu-archive-snapshot .zhihu-archive-article video,
            .zhihu-archive-snapshot .zhihu-archive-comments img,
            .zhihu-archive-snapshot .zhihu-archive-comments video { max-width: 100%; height: auto; }
            .zhihu-archive-snapshot .zhihu-archive-comment-list,
            .zhihu-archive-snapshot .zhihu-archive-replies { list-style: none; padding-left: 0; }
            .zhihu-archive-snapshot .zhihu-archive-replies { margin-left: 1.25rem; padding-left: 1rem; border-left: 2px solid rgba(0, 0, 0, 0.08); }
            .zhihu-archive-snapshot .zhihu-archive-comment,
            .zhihu-archive-snapshot .zhihu-archive-reply { margin: 1rem 0; }
            .zhihu-archive-snapshot .zhihu-archive-comment-body { white-space: pre-wrap; overflow-wrap: anywhere; }
            .zhihu-archive-snapshot .zhihu-archive-comment-meta,
            .zhihu-archive-snapshot .zhihu-archive-ip,
            .zhihu-archive-snapshot .zhihu-archive-badge,
            .zhihu-archive-snapshot .zhihu-archive-collapsed { color: #6b7280; font-size: 0.8125rem; }
            .zhihu-archive-snapshot .zhihu-archive-badge { border: 1px solid currentColor; border-radius: 999px; padding: 0 0.4rem; margin-left: 0.4rem; }
            .zhihu-archive-snapshot .zhihu-archive-placeholder { color: #b91c1c; word-break: break-all; }
            .zhihu-archive-snapshot .zhihu-archive-math { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
            .zhihu-archive-snapshot .zhihu-archive-truncated { color: #6b7280; font-size: 0.875rem; font-style: italic; }
            .zhihu-archive-snapshot .zhihu-archive-footer { border-top: 1px solid rgba(0, 0, 0, 0.12); color: #6b7280; font-size: 0.875rem; margin-top: 1.5rem; padding-top: 1rem; }
            .zhihu-archive-snapshot .zhihu-archive-index-meta { color: #6b7280; font-size: 0.875rem; }
            """;

    /**
     * Renders the archive document.
     *
     * @param payload captured payload; must not be {@code null}
     * @param comments normalized comments, may be {@code null}
     * @param articleHtml sanitized and asset-rewritten article HTML
     * @param contentHash canonical payload hash
     * @param assetFailures number of assets that could not be localized
     * @return composed archive HTML and derived identity
     */
    public RenderedArchive render(
            ZhihuArchivePayload payload,
            ZhihuNormalizedComments comments,
            String articleHtml,
            String contentHash,
            int assetFailures) {
        var document = Jsoup.parseBodyFragment("");
        document.outputSettings().prettyPrint(false);
        var body = document.body();

        var snapshot = new Element("div").addClass(SNAPSHOT_CLASS);
        snapshot.appendElement("style").text(STYLE);

        appendBanner(snapshot, payload, contentHash, assetFailures);
        appendArticle(snapshot, articleHtml);
        appendComments(snapshot, comments);
        appendFooter(snapshot);

        body.appendChild(snapshot);
        return new RenderedArchive(snapshot.outerHtml(), resolveTitle(payload), resolveSlug(payload, contentHash));
    }

    private static void appendBanner(
            Element snapshot, ZhihuArchivePayload payload, String contentHash, int assetFailures) {
        var header = snapshot.appendElement("header").addClass("zhihu-archive-banner");

        var provenance = header.appendElement("p").addClass("zhihu-archive-provenance");
        provenance.appendText("知乎存档 · 抓取于 ");
        var capturedAt = payload.capture() == null ? null : payload.capture().capturedAt();
        var capturedAtText = capturedAt == null ? "" : capturedAt.toString();
        provenance.appendElement("time").attr("datetime", capturedAtText).text(capturedAtText);
        provenance.appendText(" · 内容哈希 ");
        provenance.appendElement("code").text(nullToEmpty(contentHash));

        header.appendElement("h1").text(resolveTitle(payload));

        var article = payload.article();
        var source = payload.source();
        var metrics = header.appendElement("p").addClass("zhihu-archive-metrics");
        metrics.appendText("作者 ");
        appendAuthor(metrics, article == null ? null : article.author(), null);
        metrics.appendText(" · 原文 ");
        var sourceUrl = source == null ? "" : nullToEmpty(source.url());
        metrics.appendElement("a")
                .attr("href", sourceUrl)
                .attr("target", "_blank")
                .attr("rel", LINK_REL)
                .text(sourceUrl);
        metrics.appendText(" · 点赞 " + (article == null ? 0L : article.likeCount()));
        metrics.appendText(" · 评论 " + (article == null ? 0L : article.commentCount()));

        header.appendElement("p").addClass("zhihu-archive-warning").text("本页为抓取时的不可变快照，原文可能已变更或删除。");

        if (assetFailures > 0) {
            header.appendElement("p")
                    .addClass("zhihu-archive-asset-failures")
                    .text(assetFailures + " 个资源未能本地化，已保留原链接。");
        }
    }

    private static void appendArticle(Element snapshot, String articleHtml) {
        var article = snapshot.appendElement("article").addClass("zhihu-archive-article");
        if (StringUtils.hasText(articleHtml)) {
            article.append(articleHtml);
        }
    }

    private static void appendComments(Element snapshot, ZhihuNormalizedComments comments) {
        var section = snapshot.appendElement("section").addClass("zhihu-archive-comments");
        var threads =
                comments == null || comments.threads() == null ? List.<ZhihuCommentThread>of() : comments.threads();
        var total = comments == null ? 0L : comments.total();
        section.appendElement("h2").text("评论（显示 " + renderedCommentCount(threads) + " 条，共 " + total + " 条）");

        if (threads.isEmpty()) {
            section.appendElement("p").addClass("zhihu-archive-comments-empty").text("暂无评论。");
        } else {
            var list = section.appendElement("ol").addClass("zhihu-archive-comment-list");
            for (var thread : threads) {
                list.appendChild(renderComment(thread.root(), "zhihu-archive-comment", thread));
            }
        }

        if (comments != null && comments.omittedRoots() > 0) {
            section.appendElement("p")
                    .addClass("zhihu-archive-truncated")
                    .text("仅保留点赞最高的 " + ZhihuArchiveLimits.RENDER_ROOT_COMMENTS + " 条根评论，另有 " + comments.omittedRoots()
                            + " 条未收录");
        }
    }

    private static long renderedCommentCount(List<ZhihuCommentThread> threads) {
        long count = 0;
        for (var thread : threads) {
            count++;
            if (thread.replies() != null) {
                count += thread.replies().size();
            }
        }
        return count;
    }

    private static Element renderComment(ZhihuComment comment, String className, ZhihuCommentThread thread) {
        var item = new Element("li").addClass(className);
        if (comment == null) {
            return item;
        }
        appendAuthor(item, comment.author(), "zhihu-archive-comment-author");

        if (StringUtils.hasText(comment.badge())) {
            item.appendElement("span").addClass("zhihu-archive-badge").text(comment.badge());
        }
        if (StringUtils.hasText(comment.ipLocation())) {
            item.appendElement("span").addClass("zhihu-archive-ip").text("IP 属地：" + comment.ipLocation());
        }

        var commentBody = item.appendElement("div").addClass("zhihu-archive-comment-body");
        var commentHtml = SANITIZER.sanitize(nullToEmpty(comment.contentHtml()));
        if (StringUtils.hasText(commentHtml)) {
            commentBody.append(commentHtml);
        }

        var meta = item.appendElement("p").addClass("zhihu-archive-comment-meta");
        if (comment.createdAt() != null) {
            var createdAt = comment.createdAt().toString();
            meta.appendElement("time").attr("datetime", createdAt).text(createdAt);
            meta.appendText(" · ");
        }
        meta.appendText("赞 " + comment.likeCount());

        if (Boolean.TRUE.equals(comment.collapsed())) {
            item.appendElement("p").addClass("zhihu-archive-collapsed").text("该评论已被折叠");
        }
        if (StringUtils.hasText(comment.replyToAuthor())) {
            item.appendElement("p").addClass("zhihu-archive-reply-to").text("回复 @" + comment.replyToAuthor());
        }

        if (thread != null) {
            appendReplies(item, thread);
        }
        return item;
    }

    private static void appendReplies(Element item, ZhihuCommentThread thread) {
        var replies = thread.replies() == null ? List.<ZhihuComment>of() : thread.replies();
        if (!replies.isEmpty()) {
            var list = item.appendElement("ol").addClass("zhihu-archive-replies");
            for (var reply : replies) {
                list.appendChild(renderComment(reply, "zhihu-archive-reply", null));
            }
        }
        if (thread.omittedReplies() > 0) {
            item.appendElement("p")
                    .addClass("zhihu-archive-truncated")
                    .text("仅保留前 " + ZhihuArchiveLimits.RENDER_REPLIES_PER_ROOT + " 条回复，另有 " + thread.omittedReplies()
                            + " 条未收录");
        }
    }

    private static void appendAuthor(Element parent, ZhihuAuthor author, String className) {
        if (author == null || !StringUtils.hasText(author.name())) {
            return;
        }
        if (StringUtils.hasText(author.profileUrl())) {
            var link = parent.appendElement("a")
                    .attr("href", author.profileUrl())
                    .attr("target", "_blank")
                    .attr("rel", LINK_REL)
                    .text(author.name());
            if (className != null) {
                link.addClass(className);
            }
            return;
        }
        var span = parent.appendElement("span").text(author.name());
        if (className != null) {
            span.addClass(className);
        }
    }

    private static void appendFooter(Element snapshot) {
        snapshot.appendElement("footer")
                .addClass("zhihu-archive-footer")
                .appendElement("p")
                .text("本存档仅用于个人备份与研究，内容版权归原作者所有。");
    }

    private static String resolveTitle(ZhihuArchivePayload payload) {
        var article = payload == null ? null : payload.article();
        var title = article == null ? null : article.title();
        if (StringUtils.hasText(title)) {
            return title.trim();
        }
        var source = payload == null ? null : payload.source();
        return "知乎存档 " + (source == null ? "" : nullToEmpty(source.id()));
    }

    private static String resolveSlug(ZhihuArchivePayload payload, String contentHash) {
        var source = payload == null ? null : payload.source();
        var id = source == null ? null : source.id();
        var tail = id == null
                ? ""
                : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-");
        tail = tail.replaceAll("^-+", "").replaceAll("-+$", "");
        if (!StringUtils.hasText(tail)) {
            var hash = nullToEmpty(contentHash);
            tail = hash.substring(0, Math.min(12, hash.length()));
        }
        var slug = "zhihu-" + tail;
        if (slug.length() > ZhihuArchiveLimits.MAX_SLUG_LENGTH) {
            slug = slug.substring(0, ZhihuArchiveLimits.MAX_SLUG_LENGTH);
        }
        return slug.replaceAll("-+$", "");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
