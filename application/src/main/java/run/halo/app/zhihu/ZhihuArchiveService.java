package run.halo.app.zhihu;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.content.SinglePage;

/**
 * Creates and maintains immutable Zhihu archive snapshots.
 *
 * @since 2.22.0
 */
public interface ZhihuArchiveService {

    /**
     * Creates a snapshot for the given payload, or returns the existing snapshot when the same source has already been
     * archived.
     *
     * @param request validated create request
     * @param owner username that owns the snapshot
     * @return the created or existing snapshot
     */
    Mono<SinglePage> create(ZhihuArchiveCreateRequest request, String owner);

    /**
     * Fetches a snapshot by metadata name.
     *
     * @param name snapshot metadata name
     * @return the snapshot
     */
    Mono<SinglePage> get(String name);

    /**
     * Lists all archive snapshots, newest first. Soft-deleted and terminating pages are excluded.
     *
     * @return snapshot stream
     */
    Flux<SinglePage> list();

    /**
     * Permanently deletes an archive snapshot page and waits for Halo to remove its versions, comments and counters.
     * Only pages carrying the Zhihu archive label can be deleted through this method. The archive index is rebuilt
     * after the page is gone so it is safe to capture the same source again.
     *
     * @param name snapshot metadata name
     * @return completion signal
     */
    Mono<Void> delete(String name);

    /**
     * Rebuilds the fixed-slug archive index page.
     *
     * @param owner username that owns the index page
     * @return the rebuilt index page
     */
    Mono<SinglePage> rebuildIndex(String owner);

    /**
     * Transfers the source video of a snapshot into attachment storage. Repeated calls are idempotent.
     *
     * @param name snapshot metadata name
     * @return the updated snapshot
     */
    Mono<SinglePage> transferVideo(String name);
}
