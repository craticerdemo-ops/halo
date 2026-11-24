package run.halo.app.zhihu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import run.halo.app.zhihu.ZhihuNormalizedComments.ZhihuCommentThread;

/**
 * Defensively normalizes a client-supplied comment payload into the bounded shape rendered by an archive snapshot.
 *
 * <p>The client is expected to have already loaded a candidate page of root comments; this class enforces the same caps
 * server-side: at most {@link ZhihuArchiveLimits#RENDER_ROOT_COMMENTS} root comments selected by descending like count
 * and at most {@link ZhihuArchiveLimits#RENDER_REPLIES_PER_ROOT} replies per selected root, in the supplied order.
 *
 * <p>Comment content is never rewritten: author, timestamp, like count, IP location, badge, collapsed state, and
 * reply-to attribution are preserved verbatim. The supplied payload is never mutated, and every returned list is
 * immutable.
 */
@Component
public class ZhihuCommentNormalizer {

    private static final Comparator<ZhihuComment> BY_LIKE_COUNT_DESC =
            Comparator.comparingLong(ZhihuComment::likeCount).reversed();

    /**
     * Normalizes the supplied comments.
     *
     * @param comments the client-supplied payload, may be {@code null}
     * @return the bounded, immutable comment view; never {@code null}
     */
    public ZhihuNormalizedComments normalize(ZhihuComments comments) {
        if (comments == null) {
            return new ZhihuNormalizedComments(0, List.of(), 0, 0, 0);
        }

        var suppliedRoots = nonNullCopy(comments.roots());

        // Sorting a copy keeps the client-supplied order for equal like counts (stable sort)
        // while never mutating the payload.
        var ranked = new ArrayList<>(suppliedRoots);
        ranked.sort(BY_LIKE_COUNT_DESC);

        var selectedCount = Math.min(ranked.size(), ZhihuArchiveLimits.RENDER_ROOT_COMMENTS);
        var threads = new ArrayList<ZhihuCommentThread>(selectedCount);
        for (var root : ranked.subList(0, selectedCount)) {
            threads.add(toThread(root));
        }

        var selectedRoots = threads.size();
        var omittedRoots = Math.max(0, suppliedRoots.size() - selectedRoots);
        return new ZhihuNormalizedComments(
                comments.total(), List.copyOf(threads), suppliedRoots.size(), selectedRoots, omittedRoots);
    }

    private static ZhihuCommentThread toThread(ZhihuComment root) {
        var suppliedReplies = nonNullCopy(root.replies());
        var keptCount = Math.min(suppliedReplies.size(), ZhihuArchiveLimits.RENDER_REPLIES_PER_ROOT);
        // Replies keep the supplied order; reply-to-reply entries stay at the same level.
        var replies = List.copyOf(suppliedReplies.subList(0, keptCount));
        var omittedReplies = Math.max(0, suppliedReplies.size() - replies.size());
        return new ZhihuCommentThread(root, replies, omittedReplies);
    }

    private static <T> List<T> nonNullCopy(List<T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        var copy = new ArrayList<T>(source.size());
        for (var element : source) {
            if (element != null) {
                copy.add(element);
            }
        }
        return List.copyOf(copy);
    }
}
