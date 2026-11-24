package run.halo.app.zhihu;

/**
 * Result of localizing a single archive asset.
 *
 * @param sourceUrl the original remote URL
 * @param localUrl permalink of the stored attachment, {@code null} on failure
 * @param failed whether localization failed
 * @param failureReason machine-readable failure reason, {@code null} on success
 */
public record LocalizedAsset(String sourceUrl, String localUrl, boolean failed, String failureReason) {

    /**
     * Creates a successful localization result.
     *
     * @param sourceUrl the original remote URL
     * @param localUrl permalink of the stored attachment
     * @return successful result
     */
    public static LocalizedAsset localized(String sourceUrl, String localUrl) {
        return new LocalizedAsset(sourceUrl, localUrl, false, null);
    }

    /**
     * Creates a failed localization result.
     *
     * @param sourceUrl the original remote URL
     * @param failureReason machine-readable failure reason
     * @return failed result
     */
    public static LocalizedAsset failed(String sourceUrl, String failureReason) {
        return new LocalizedAsset(sourceUrl, null, true, failureReason);
    }
}
