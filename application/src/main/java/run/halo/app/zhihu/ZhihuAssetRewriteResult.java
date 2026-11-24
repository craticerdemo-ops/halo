package run.halo.app.zhihu;

/**
 * Outcome of rewriting archive HTML assets.
 *
 * @param html rewritten HTML
 * @param failureCount number of assets that could not be localized
 * @param localizedCount number of assets localized successfully
 */
public record ZhihuAssetRewriteResult(String html, int failureCount, int localizedCount) {}
