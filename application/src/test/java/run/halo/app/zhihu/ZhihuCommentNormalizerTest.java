package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ZhihuCommentNormalizerTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final ZhihuCommentNormalizer normalizer = new ZhihuCommentNormalizer();

    @Test
    void shouldSelectTopTenRootsInDescendingLikeOrder() {
        var roots = IntStream.rangeClosed(1, 12)
                .mapToObj(likes -> root("r" + likes, likes, List.of()))
                .toList();

        var result = normalizer.normalize(new ZhihuComments(120, roots));

        assertThat(result.total()).isEqualTo(120);
        assertThat(result.threads()).hasSize(10);
        assertThat(result.threads())
                .extracting(thread -> thread.root().likeCount())
                .containsExactly(12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L);
        assertThat(result.suppliedRoots()).isEqualTo(12);
        assertThat(result.selectedRoots()).isEqualTo(10);
        assertThat(result.omittedRoots()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void shouldKeepAllRootsWhenTenOrFewerAreSupplied() {
        var roots = IntStream.rangeClosed(1, 3)
                .mapToObj(likes -> root("r" + likes, likes, List.of()))
                .toList();

        var result = normalizer.normalize(new ZhihuComments(3, roots));

        assertThat(result.threads()).hasSize(3);
        assertThat(result.suppliedRoots()).isEqualTo(3);
        assertThat(result.selectedRoots()).isEqualTo(3);
        assertThat(result.omittedRoots()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void shouldKeepSuppliedOrderForTiedLikeCounts() {
        // Identifiers would sort differently than the supplied order, so id-based sorting would fail.
        var roots = List.of(
                root("3-c", 5, List.of()),
                root("2-b", 5, List.of()),
                root("1-a", 5, List.of()),
                root("0-d", 1, List.of()));

        var result = normalizer.normalize(new ZhihuComments(4, roots));

        assertThat(result.threads())
                .extracting(thread -> thread.root().id())
                .containsExactly("3-c", "2-b", "1-a", "0-d");
    }

    @Test
    void shouldTruncateRepliesToFiftyInSuppliedOrder() {
        var replies = IntStream.rangeClosed(0, 59)
                .mapToObj(index -> reply("reply-" + index, null))
                .toList();
        var roots = List.of(root("root-1", 10, replies));

        var result = normalizer.normalize(new ZhihuComments(60, roots));

        var thread = result.threads().getFirst();
        assertThat(thread.replies()).hasSize(50);
        assertThat(thread.replies().getFirst().id()).isEqualTo("reply-0");
        assertThat(thread.replies().getLast().id()).isEqualTo("reply-49");
        assertThat(thread.omittedReplies()).isEqualTo(10);
        assertThat(thread.truncated()).isTrue();
    }

    @Test
    void shouldKeepExactlyFiftyRepliesUntruncated() {
        var replies = IntStream.rangeClosed(0, 49)
                .mapToObj(index -> reply("reply-" + index, null))
                .toList();
        var roots = List.of(root("root-1", 10, replies));

        var result = normalizer.normalize(new ZhihuComments(50, roots));

        var thread = result.threads().getFirst();
        assertThat(thread.replies()).hasSize(50);
        assertThat(thread.replies().getLast().id()).isEqualTo("reply-49");
        assertThat(thread.omittedReplies()).isZero();
        assertThat(thread.truncated()).isFalse();
    }

    @Test
    void shouldTreatNullRepliesAsEmpty() {
        var roots = List.of(root("root-1", 10, null));

        var result = normalizer.normalize(new ZhihuComments(1, roots));

        var thread = result.threads().getFirst();
        assertThat(thread.replies()).isEmpty();
        assertThat(thread.omittedReplies()).isZero();
        assertThat(thread.truncated()).isFalse();
    }

    @Test
    void shouldRetainReplyToReplyAtTheSameLevel() {
        var replies = List.of(reply("reply-1", null), reply("reply-2", "张三"));
        var roots = List.of(root("root-1", 10, replies));

        var result = normalizer.normalize(new ZhihuComments(2, roots));

        var thread = result.threads().getFirst();
        assertThat(thread.root().id()).isEqualTo("root-1");
        assertThat(thread.replies()).extracting(ZhihuComment::id).containsExactly("reply-1", "reply-2");
        assertThat(thread.replies().get(1).replyToAuthor()).isEqualTo("张三");
        assertThat(thread.replies().get(1)).isEqualTo(replies.get(1));
    }

    @Test
    void shouldPreserveSuppliedCommentFields() {
        var author = new ZhihuAuthor("李四", "https://www.zhihu.com/people/li-si");
        var rootComment = new ZhihuComment(
                "root-1",
                author,
                "<p>原文 <strong>HTML</strong></p>",
                42L,
                CREATED_AT,
                "IP属地：北京",
                "优秀回答者",
                true,
                null,
                List.of());

        var result = normalizer.normalize(new ZhihuComments(42, List.of(rootComment)));

        var normalized = result.threads().getFirst().root();
        assertThat(normalized.author().name()).isEqualTo("李四");
        assertThat(normalized.author().profileUrl()).isEqualTo("https://www.zhihu.com/people/li-si");
        assertThat(normalized.createdAt()).isEqualTo(CREATED_AT);
        assertThat(normalized.ipLocation()).isEqualTo("IP属地：北京");
        assertThat(normalized.badge()).isEqualTo("优秀回答者");
        assertThat(normalized.collapsed()).isTrue();
        assertThat(normalized.likeCount()).isEqualTo(42L);
        assertThat(normalized.contentHtml()).isEqualTo("<p>原文 <strong>HTML</strong></p>");
        assertThat(normalized).isEqualTo(rootComment);
    }

    @Test
    void shouldNotMutateTheSuppliedPayload() {
        var replies = new ArrayList<>(IntStream.rangeClosed(0, 59)
                .mapToObj(index -> reply("reply-" + index, null))
                .toList());
        var roots = new ArrayList<>(List.of(root("r1", 1, replies), root("r2", 5, null), root("r3", 3, null)));
        var rootsCopy = List.copyOf(roots);
        var repliesCopy = List.copyOf(replies);

        normalizer.normalize(new ZhihuComments(3, roots));

        assertThat(roots).containsExactlyElementsOf(rootsCopy);
        assertThat(replies).containsExactlyElementsOf(repliesCopy);
        assertThat(roots.getFirst().replies()).hasSize(60);
    }

    @Test
    void shouldReturnImmutableLists() {
        var roots = List.of(root("root-1", 10, List.of(reply("reply-1", null))));

        var result = normalizer.normalize(new ZhihuComments(1, roots));
        var thread = result.threads().getFirst();

        assertThatThrownBy(() -> result.threads().add(thread)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> thread.replies().add(thread.root())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldReturnEmptyResultForNullComments() {
        var result = normalizer.normalize(null);

        assertThat(result.total()).isZero();
        assertThat(result.threads()).isEmpty();
        assertThat(result.suppliedRoots()).isZero();
        assertThat(result.selectedRoots()).isZero();
        assertThat(result.omittedRoots()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void shouldTreatNullRootsAsEmpty() {
        var result = normalizer.normalize(new ZhihuComments(7, null));

        assertThat(result.total()).isEqualTo(7);
        assertThat(result.threads()).isEmpty();
        assertThat(result.suppliedRoots()).isZero();
        assertThat(result.selectedRoots()).isZero();
        assertThat(result.omittedRoots()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    private static ZhihuComment root(String id, long likeCount, List<ZhihuComment> replies) {
        return new ZhihuComment(
                id,
                new ZhihuAuthor(id + "-author", "https://www.zhihu.com/people/" + id),
                "<p>" + id + "</p>",
                likeCount,
                CREATED_AT,
                "IP属地：北京",
                "优秀回答者",
                false,
                null,
                replies);
    }

    private static ZhihuComment reply(String id, String replyToAuthor) {
        return new ZhihuComment(
                id,
                new ZhihuAuthor(id + "-author", "https://www.zhihu.com/people/" + id),
                "<p>" + id + "</p>",
                0L,
                CREATED_AT,
                "IP属地：上海",
                null,
                false,
                replyToAuthor,
                List.of());
    }
}
