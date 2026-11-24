package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * A captured Zhihu comment, which may contain nested replies.
 *
 * @param id Zhihu-side comment identifier
 * @param author comment author
 * @param contentHtml sanitized comment HTML
 * @param likeCount number of likes
 * @param createdAt creation time reported by Zhihu
 * @param ipLocation IP location label shown by Zhihu
 * @param badge badge label shown by Zhihu
 * @param collapsed whether Zhihu collapsed the comment
 * @param replyToAuthor author this comment replies to, when known
 * @param replies nested replies
 */
public record ZhihuComment(
        @Size(max = 128) String id,
        @NotNull @Valid ZhihuAuthor author,

        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_COMMENT_HTML_LENGTH)
        String contentHtml,

        @Min(0) long likeCount,
        Instant createdAt,
        @Size(max = 200) String ipLocation,
        @Size(max = 200) String badge,
        Boolean collapsed,
        @Size(max = 200) String replyToAuthor,

        @Valid @Size(max = ZhihuArchiveLimits.MAX_REPLIES_PER_ROOT)
        List<ZhihuComment> replies) {}
