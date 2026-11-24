package run.halo.app.zhihu;

import reactor.core.publisher.Mono;

/** Localizes a remote Zhihu asset into attachment storage. */
public interface ZhihuAssetLocalizer {

    /**
     * Downloads and stores a single asset.
     *
     * <p>Failures are reported in the returned result and never as reactive errors.
     *
     * @param asset the asset to localize
     * @param policyName the attachment policy to store the asset in
     * @return localization result
     */
    Mono<LocalizedAsset> localize(ZhihuAsset asset, String policyName);
}
