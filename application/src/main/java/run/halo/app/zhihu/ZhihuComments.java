package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Captured comment tree for a Zhihu source.
 *
 * @param total total number of comments reported by Zhihu
 * @param roots root comments selected for the archive
 */
public record ZhihuComments(
        @Min(0) long total,

        @NotNull @Valid @Size(max = ZhihuArchiveLimits.MAX_ROOT_COMMENTS)
        List<ZhihuComment> roots) {}
