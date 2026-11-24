package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Captured article body and frozen metrics for a Zhihu source.
 *
 * @param title article title
 * @param author article author
 * @param publishedAt original publish time
 * @param likeCount number of likes at capture time
 * @param commentCount number of comments at capture time
 * @param html article HTML to be sanitized by the server
 */
public record ZhihuArticle(
        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_TITLE_LENGTH)
        String title,

        @NotNull @Valid ZhihuAuthor author,
        Instant publishedAt,
        @Min(0) long likeCount,
        @Min(0) long commentCount,

        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_ARTICLE_HTML_LENGTH)
        String html) {}
