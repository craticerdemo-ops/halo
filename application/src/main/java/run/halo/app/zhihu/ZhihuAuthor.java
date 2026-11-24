package run.halo.app.zhihu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Author identity captured from a Zhihu source.
 *
 * @param name display name of the author
 * @param profileUrl optional Zhihu profile URL
 */
public record ZhihuAuthor(
        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_AUTHOR_NAME_LENGTH)
        String name,

        @Size(max = ZhihuArchiveLimits.MAX_URL_LENGTH) String profileUrl) {}
