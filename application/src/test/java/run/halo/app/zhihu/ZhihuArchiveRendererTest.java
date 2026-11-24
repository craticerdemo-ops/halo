package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.zhihu.ZhihuNormalizedComments.ZhihuCommentThread;

class ZhihuArchiveRendererTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-02-03T04:05:06Z");

    private static final Instant PUBLISHED_AT = Instant.parse("2025-01-02T03:04:05Z");

    private static final Instant CREATED_AT = Instant.parse("2026-02-01T00:00:00Z");

    private static final String CONTENT_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final ZhihuArchiveRenderer renderer = new ZhihuArchiveRenderer();

    @Test
    void shouldEscapeDynamicTextAndAttributes() {
        var author =
                new ZhihuAuthor("A & \"B\" <script>alert(1)</script>", "https://www.zhihu.com/people/x?a=1&b=\"q\"");
        var comment = new ZhihuComment(
                "c1",
                new ZhihuAuthor("<b>commenter</b>", null),
                "<script>alert('body')</script> & \"quoted\"",
                3,
                CREATED_AT,
                "1.2.3.4 & <bad>",
                null,
                null,
                null,
                List.of());
        var comments = new ZhihuNormalizedComments(1, List.of(new ZhihuCommentThread(comment, List.of(), 0)), 1, 1, 0);

        var rendered = renderer.render(
                payload("<script>alert('title')</script> & \"quoted\"", author),
                comments,
                "<p>safe</p>",
                CONTENT_HASH,
                0);

        assertThat(rendered.html())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("A &amp;")
                .contains("&lt;b&gt;commenter&lt;/b&gt;")
                .contains("1.2.3.4 &amp; &lt;bad&gt;")
                .contains("&amp;")
                .contains("href=\"https://www.zhihu.com/people/x?a=1&amp;b=&quot;q&quot;\"");
    }

    @Test
    void shouldRenderBannerWithFrozenMetricsAndProvenance() {
        var rendered = renderer.render(
                payload("Frozen Title", new ZhihuAuthor("Author", "https://www.zhihu.com/people/a")),
                normalized(List.of()),
                "<p>article</p>",
                CONTENT_HASH,
                0);

        assertThat(rendered.html())
                .contains("class=\"zhihu-archive-snapshot\"")
                .contains("class=\"zhihu-archive-banner\"")
                .contains("抓取于")
                .contains("<time datetime=\"2026-02-03T04:05:06Z\">2026-02-03T04:05:06Z</time>")
                .contains("<code>" + CONTENT_HASH + "</code>")
                .contains("<h1>Frozen Title</h1>")
                .contains(
                        "作者 <a href=\"https://www.zhihu.com/people/a\" target=\"_blank\" rel=\"nofollow noopener noreferrer\">Author</a>")
                .contains(
                        "原文 <a href=\"https://zhuanlan.zhihu.com/p/1\" target=\"_blank\" rel=\"nofollow noopener noreferrer\">https://zhuanlan.zhihu.com/p/1</a>")
                .contains("点赞 42")
                .contains("评论 7")
                .contains("本页为抓取时的不可变快照，原文可能已变更或删除。")
                .contains("class=\"zhihu-archive-article\"><p>article</p></article>")
                .contains("class=\"zhihu-archive-footer\"");
        assertThat(rendered.title()).isEqualTo("Frozen Title");
    }

    @Test
    void shouldAppendSanitizedArticleHtmlWithoutEscapingIt() {
        var rendered = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)),
                normalized(List.of()),
                "<p>hello <strong>world</strong></p>",
                CONTENT_HASH,
                0);

        assertThat(rendered.html()).contains("<p>hello <strong>world</strong></p>");
    }

    @Test
    void shouldRenderNestedRepliesWithReplyToAndPreservedFields() {
        var reply = new ZhihuComment(
                "reply-1",
                new ZhihuAuthor("Replier", "https://www.zhihu.com/people/r"),
                "reply body",
                5,
                CREATED_AT,
                null,
                "热评",
                null,
                "RootAuthor",
                List.of());
        var replyToReply = new ZhihuComment(
                "reply-2",
                new ZhihuAuthor("SecondReplier", null),
                "reply to reply",
                1,
                CREATED_AT,
                "北京",
                null,
                null,
                "Replier",
                List.of());
        var root = new ZhihuComment(
                "root-1",
                new ZhihuAuthor("RootAuthor", null),
                "root body",
                9,
                CREATED_AT,
                "上海",
                "作者",
                true,
                null,
                List.of(reply, replyToReply));
        var thread = new ZhihuCommentThread(root, List.of(reply, replyToReply), 0);

        var rendered = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)),
                new ZhihuNormalizedComments(10, List.of(thread), 1, 1, 0),
                "",
                CONTENT_HASH,
                0);

        assertThat(rendered.html())
                .contains("class=\"zhihu-archive-comment-list\"")
                .contains("class=\"zhihu-archive-comment\"")
                .contains("class=\"zhihu-archive-replies\"")
                .contains("class=\"zhihu-archive-reply\"")
                .contains("IP 属地：上海")
                .contains("IP 属地：北京")
                .contains("class=\"zhihu-archive-badge\">作者</span>")
                .contains("class=\"zhihu-archive-badge\">热评</span>")
                .contains("class=\"zhihu-archive-collapsed\">该评论已被折叠</p>")
                .contains("回复 @RootAuthor")
                .contains("回复 @Replier")
                .contains("赞 9")
                .contains("赞 5");
        assertThat(rendered.html()).containsOnlyOnce("<ol class=\"zhihu-archive-replies\">");
    }

    @Test
    void shouldRenderBothTruncationNotesWhenCountsArePositive() {
        var root = new ZhihuComment(
                "r", new ZhihuAuthor("A", null), "body", 1, CREATED_AT, null, null, null, null, List.of());
        var thread = new ZhihuCommentThread(root, List.of(), 3);
        var comments = new ZhihuNormalizedComments(20, List.of(thread), 4, 1, 2);

        var rendered =
                renderer.render(payload("Title", new ZhihuAuthor("Author", null)), comments, "", CONTENT_HASH, 0);

        assertThat(rendered.html()).contains("仅保留点赞最高的 10 条根评论，另有 2 条未收录").contains("仅保留前 50 条回复，另有 3 条未收录");
    }

    @Test
    void shouldNotRenderTruncationNotesWhenNothingWasOmitted() {
        var rendered = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)), normalized(List.of()), "", CONTENT_HASH, 0);

        assertThat(rendered.html()).doesNotContain("条未收录");
    }

    @Test
    void shouldRenderEmptyCommentsSection() {
        var rendered = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)),
                new ZhihuNormalizedComments(9, List.of(), 0, 0, 0),
                "",
                CONTENT_HASH,
                0);

        assertThat(rendered.html())
                .contains("评论（显示 0 条，共 9 条）")
                .contains("class=\"zhihu-archive-comments-empty\">暂无评论。</p>");
    }

    @Test
    void shouldRenderAssetFailureBannerLineOnlyWhenFailuresExist() {
        var withFailures = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)), normalized(List.of()), "", CONTENT_HASH, 2);
        var withoutFailures = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)), normalized(List.of()), "", CONTENT_HASH, 0);

        assertThat(withFailures.html()).contains("class=\"zhihu-archive-asset-failures\">2 个资源未能本地化，已保留原链接。</p>");
        assertThat(withoutFailures.html()).doesNotContain("个资源未能本地化");
    }

    @Test
    void shouldSanitizeSlugAndFallBackToContentHash() {
        var rendered = renderer.render(
                payloadForId("AbC Def!", "Title", new ZhihuAuthor("Author", null)),
                normalized(List.of()),
                "",
                CONTENT_HASH,
                0);
        assertThat(rendered.slug()).isEqualTo("zhihu-abc-def");

        var fallback = renderer.render(
                payloadForId("中文", "Title", new ZhihuAuthor("Author", null)),
                normalized(List.of()),
                "",
                CONTENT_HASH,
                0);
        assertThat(fallback.slug()).isEqualTo("zhihu-" + CONTENT_HASH.substring(0, 12));
    }

    @Test
    void shouldTruncateSlugToMaximumLength() {
        var rendered = renderer.render(
                payloadForId("a".repeat(200), "Title", new ZhihuAuthor("Author", null)),
                normalized(List.of()),
                "",
                CONTENT_HASH,
                0);

        assertThat(rendered.slug()).hasSize(ZhihuArchiveLimits.MAX_SLUG_LENGTH).startsWith("zhihu-");
    }

    @Test
    void shouldFallBackToSourceIdWhenTitleIsBlank() {
        var rendered = renderer.render(
                payloadForId("p1", "   ", new ZhihuAuthor("Author", null)), normalized(List.of()), "", CONTENT_HASH, 0);

        assertThat(rendered.title()).isEqualTo("知乎存档 p1");
    }

    @Test
    void shouldPreserveSanitizedCommentMarkup() {
        var comment = new ZhihuComment(
                "c1",
                new ZhihuAuthor("A", null),
                "<b>bold</b> <a href=\"https://www.zhihu.com/x\" onclick=\"bad()\">link</a><script>bad</script>",
                1,
                CREATED_AT,
                null,
                null,
                null,
                null,
                List.of());
        var comments = new ZhihuNormalizedComments(1, List.of(new ZhihuCommentThread(comment, List.of(), 0)), 1, 1, 0);

        var rendered =
                renderer.render(payload("Title", new ZhihuAuthor("A", null)), comments, "<p>a</p>", CONTENT_HASH, 0);

        assertThat(rendered.html())
                .contains("<b>bold</b>")
                .contains("href=\"https://www.zhihu.com/x\"")
                .doesNotContain("onclick")
                .doesNotContain("<script>");
    }

    @Test
    void shouldNotEmitLiveCounterPlaceholders() {
        var rendered = renderer.render(
                payload("Title", new ZhihuAuthor("Author", null)), normalized(List.of()), "", CONTENT_HASH, 0);

        assertThat(rendered.html()).doesNotContain("{{").doesNotContain("}}").doesNotContain("data-live");
    }

    private static ZhihuNormalizedComments normalized(List<ZhihuCommentThread> threads) {
        return new ZhihuNormalizedComments(7, threads, threads.size(), threads.size(), 0);
    }

    private static ZhihuArchivePayload payload(String title, ZhihuAuthor author) {
        return payloadForId("p1", title, author);
    }

    private static ZhihuArchivePayload payloadForId(String id, String title, ZhihuAuthor author) {
        return new ZhihuArchivePayload(
                1,
                new ZhihuSource("https://zhuanlan.zhihu.com/p/1", ZhihuSourceType.ARTICLE, id),
                new ZhihuArticle(title, author, PUBLISHED_AT, 42, 7, "<p>article</p>"),
                new ZhihuComments(7, List.of()),
                new ZhihuCapture(CAPTURED_AT, "1.0.0"),
                null);
    }
}
