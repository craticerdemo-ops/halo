package run.halo.app.zhihu;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A remote asset referenced by an archive payload.
 *
 * @param url asset URL
 * @param type asset type
 */
public record ZhihuAsset(
        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_URL_LENGTH)
        String url,

        @NotNull ZhihuAssetType type) {}
