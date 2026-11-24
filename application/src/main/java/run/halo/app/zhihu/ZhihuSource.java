package run.halo.app.zhihu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Identity of the captured Zhihu source.
 *
 * @param url source URL, stored verbatim
 * @param type source content type
 * @param id Zhihu-side identifier of the source
 */
public record ZhihuSource(
        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_URL_LENGTH)
        String url,

        @NotNull ZhihuSourceType type,
        @NotBlank @Size(max = 128) String id) {}
