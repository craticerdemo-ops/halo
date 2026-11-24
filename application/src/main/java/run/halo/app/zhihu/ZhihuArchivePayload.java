package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Versioned, self-describing archive payload submitted by a capture client.
 *
 * @param schemaVersion payload schema version, currently exactly 1
 * @param source source identity
 * @param article article content and frozen metrics
 * @param comments selected comments
 * @param capture capture metadata
 * @param assets referenced assets, may be {@code null}
 */
public record ZhihuArchivePayload(
        @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Valid ZhihuSource source,
        @NotNull @Valid ZhihuArticle article,
        @NotNull @Valid ZhihuComments comments,
        @NotNull @Valid ZhihuCapture capture,
        @Valid ZhihuAssets assets) {

    /**
     * Returns the referenced assets, or an empty list when none were supplied.
     *
     * @return non-null asset list
     */
    public List<ZhihuAsset> assetsOrEmpty() {
        return assets == null ? List.of() : assets.itemsOrEmpty();
    }
}
