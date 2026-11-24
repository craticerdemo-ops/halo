package run.halo.app.zhihu;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Capture metadata recorded by the client.
 *
 * @param capturedAt time the capture was performed
 * @param clientVersion version of the capture client
 */
public record ZhihuCapture(
        @NotNull Instant capturedAt, @Size(max = 64) String clientVersion) {}
