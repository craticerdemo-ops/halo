package run.halo.app.zhihu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Computes deterministic SHA-256 hashes of archive payloads using a canonical JSON form.
 *
 * <p>The class is stateless and exposes only deterministic helpers; it is registered as a bean so that services can
 * depend on it for testability while callers may still use the static helpers.
 */
@Component
public final class ZhihuPayloadHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    public ZhihuPayloadHasher() {}

    /**
     * Computes a stable SHA-256 hex digest of the given payload.
     *
     * @param payload payload to hash
     * @return lowercase 64-character hex digest
     */
    public static String sha256Hex(ZhihuArchivePayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return sha256HexOfCanonical(payload);
    }

    /**
     * Computes a stable SHA-256 hex digest of an arbitrary value after canonical JSON serialization.
     *
     * @param value value to serialize and hash
     * @return lowercase 64-character hex digest
     */
    public static String sha256HexOfCanonical(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            String json = MAPPER.writeValueAsString(value);
            JsonNode tree = MAPPER.readTree(json);
            JsonNode canonical = canonicalize(tree);
            byte[] canonicalJson = MAPPER.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson);
            return HEX_FORMAT.formatHex(hash);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize value for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    /**
     * Computes the SHA-256 hex digest of the given bytes.
     *
     * @param value bytes to hash
     * @return lowercase 64-character hex digest
     */
    public static String sha256Hex(byte[] value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return HEX_FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available", e);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> ordered.add(canonicalize(child)));
            return ordered;
        }
        return node;
    }
}
