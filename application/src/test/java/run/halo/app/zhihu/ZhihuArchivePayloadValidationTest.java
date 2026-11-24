package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ZhihuArchivePayloadValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldAcceptFullyValidPayload() {
        assertThat(validate(validPayload())).isEmpty();
    }

    @Test
    void shouldRejectNullSource() {
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, null, validArticle(), validComments(), validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectNullSourceUrl() {
        ZhihuSource source = new ZhihuSource(null, ZhihuSourceType.ARTICLE, "1");
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, source, validArticle(), validComments(), validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectNullSourceType() {
        ZhihuSource source = new ZhihuSource("https://zhihu.com/p/1", null, "1");
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, source, validArticle(), validComments(), validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectNullArticleHtml() {
        ZhihuArticle article =
                new ZhihuArticle("title", validAuthor(), Instant.parse("2024-01-01T00:00:00Z"), 0, 0, null);
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), article, validComments(), validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectNullCapturedAt() {
        ZhihuCapture capture = new ZhihuCapture(null, "1.0.0");
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), validArticle(), validComments(), capture, validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectArticleHtmlLongerThanLimit() {
        String oversizedHtml = "a".repeat(ZhihuArchiveLimits.MAX_ARTICLE_HTML_LENGTH + 1);
        ZhihuArticle article =
                new ZhihuArticle("title", validAuthor(), Instant.parse("2024-01-01T00:00:00Z"), 0, 0, oversizedHtml);
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), article, validComments(), validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectTooManyRootComments() {
        ZhihuComment comment = validComment();
        List<ZhihuComment> roots =
                new ArrayList<>(Collections.nCopies(ZhihuArchiveLimits.MAX_ROOT_COMMENTS + 1, comment));
        ZhihuComments comments = new ZhihuComments(roots.size(), roots);
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), validArticle(), comments, validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldRejectTooManyRepliesForRoot() {
        ZhihuComment reply = validComment();
        List<ZhihuComment> replies =
                new ArrayList<>(Collections.nCopies(ZhihuArchiveLimits.MAX_REPLIES_PER_ROOT + 1, reply));
        ZhihuComment root = new ZhihuComment(
                "root",
                validAuthor(),
                "<p>root</p>",
                0,
                Instant.parse("2024-01-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                replies);
        ZhihuComments comments = new ZhihuComments(1, List.of(root));
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), validArticle(), comments, validCapture(), validAssets());

        assertThat(validate(payload)).isNotEmpty();
    }

    @Test
    void shouldAcceptNullAssets() {
        ZhihuArchivePayload payload =
                new ZhihuArchivePayload(1, validSource(), validArticle(), validComments(), validCapture(), null);

        assertThat(validate(payload)).isEmpty();
        assertThat(payload.assetsOrEmpty()).isEmpty();
    }

    private static Set<ConstraintViolation<ZhihuArchivePayload>> validate(ZhihuArchivePayload payload) {
        return validator.validate(payload);
    }

    private static ZhihuArchivePayload validPayload() {
        return new ZhihuArchivePayload(
                1, validSource(), validArticle(), validComments(), validCapture(), validAssets());
    }

    private static ZhihuSource validSource() {
        return new ZhihuSource("https://zhihu.com/question/1/answer/2", ZhihuSourceType.ANSWER, "2");
    }

    private static ZhihuAuthor validAuthor() {
        return new ZhihuAuthor("Author", "https://www.zhihu.com/people/author");
    }

    private static ZhihuArticle validArticle() {
        return new ZhihuArticle("A title", validAuthor(), Instant.parse("2024-01-01T00:00:00Z"), 10, 2, "<p>Hello</p>");
    }

    private static ZhihuComments validComments() {
        return new ZhihuComments(1, List.of(validComment()));
    }

    private static ZhihuComment validComment() {
        return new ZhihuComment(
                "comment-1",
                validAuthor(),
                "<p>A comment</p>",
                1,
                Instant.parse("2024-01-02T00:00:00Z"),
                "Shanghai",
                "Author",
                Boolean.FALSE,
                null,
                List.of());
    }

    private static ZhihuCapture validCapture() {
        return new ZhihuCapture(Instant.parse("2024-02-01T00:00:00Z"), "1.0.0");
    }

    private static ZhihuAssets validAssets() {
        return new ZhihuAssets(List.of(
                new ZhihuAsset("https://pic1.zhimg.com/a.jpg", ZhihuAssetType.IMAGE),
                new ZhihuAsset("https://pic1.zhimg.com/b.png", ZhihuAssetType.AVATAR)));
    }
}
