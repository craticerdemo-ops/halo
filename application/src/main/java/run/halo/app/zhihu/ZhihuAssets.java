package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Collection of assets referenced by an archive payload.
 *
 * @param items assets, may be {@code null}
 */
public record ZhihuAssets(
        @Valid @Size(max = ZhihuArchiveLimits.MAX_ASSETS) List<ZhihuAsset> items) {

    /**
     * Returns the asset list, or an empty list when none were supplied.
     *
     * @return non-null asset list
     */
    public List<ZhihuAsset> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}
