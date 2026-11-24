package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ZhihuPayloadHasherTest {

    @Test
    void shouldHashSamePayloadDeterministically() {
        ZhihuArchivePayload payload = payload("A title");

        String first = ZhihuPayloadHasher.sha256Hex(payload);
        String second = ZhihuPayloadHasher.sha256Hex(payload);

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    void shouldChangeHashWhenTitleChanges() {
        String first = ZhihuPayloadHasher.sha256Hex(payload("A title"));
        String second = ZhihuPayloadHasher.sha256Hex(payload("Another title"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void shouldIgnoreLinkedHashMapInsertionOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", nestedMap("z", 1, "a", 2));
        first.put("a", 3);

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", 3);
        second.put("b", nestedMap("a", 2, "z", 1));

        assertThat(ZhihuPayloadHasher.sha256HexOfCanonical(first))
                .isEqualTo(ZhihuPayloadHasher.sha256HexOfCanonical(second));
    }

    @Test
    void shouldIgnoreTreeMapInsertionOrder() {
        Map<String, Object> first = new TreeMap<>();
        first.put("b", nestedMap("z", 1, "a", 2));
        first.put("a", 3);

        Map<String, Object> second = new TreeMap<>();
        second.put("a", 3);
        second.put("b", nestedMap("a", 2, "z", 1));

        assertThat(ZhihuPayloadHasher.sha256HexOfCanonical(first))
                .isEqualTo(ZhihuPayloadHasher.sha256HexOfCanonical(second));
    }

    @Test
    void shouldPreserveArrayOrder() {
        Map<String, Object> forward = Map.of("items", new Object[] {1, 2, 3});
        Map<String, Object> reversed = Map.of("items", new Object[] {3, 2, 1});

        assertThat(ZhihuPayloadHasher.sha256HexOfCanonical(forward))
                .isNotEqualTo(ZhihuPayloadHasher.sha256HexOfCanonical(reversed));
    }

    @Test
    void shouldSerializeInstantStably() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("capturedAt", Instant.parse("2024-02-01T00:00:00Z"));
        first.put("title", "A title");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("title", "A title");
        second.put("capturedAt", Instant.parse("2024-02-01T00:00:00Z"));

        String firstHash = ZhihuPayloadHasher.sha256HexOfCanonical(first);
        String secondHash = ZhihuPayloadHasher.sha256HexOfCanonical(second);

        assertThat(firstHash).isEqualTo(secondHash).matches("[0-9a-f]{64}");
        assertThat(ZhihuPayloadHasher.sha256HexOfCanonical(Map.of("capturedAt", Instant.parse("2024-02-01T00:00:01Z"))))
                .isNotEqualTo(firstHash);
    }

    private static Map<String, Object> nestedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static ZhihuArchivePayload payload(String title) {
        ZhihuAuthor author = new ZhihuAuthor("Author", "https://www.zhihu.com/people/author");
        return new ZhihuArchivePayload(
                1,
                new ZhihuSource("https://zhihu.com/p/1", ZhihuSourceType.ARTICLE, "1"),
                new ZhihuArticle(title, author, Instant.parse("2024-01-01T00:00:00Z"), 1, 2, "<p>Hello</p>"),
                new ZhihuComments(1, List.of()),
                new ZhihuCapture(Instant.parse("2024-02-01T00:00:00Z"), "1.0.0"),
                new ZhihuAssets(List.of()));
    }
}
