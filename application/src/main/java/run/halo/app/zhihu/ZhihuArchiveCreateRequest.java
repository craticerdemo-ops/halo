package run.halo.app.zhihu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request envelope for creating a Zhihu archive snapshot.
 *
 * @param payload captured Zhihu payload
 * @param policyName storage policy used for the raw payload and localized media
 * @param visible page visibility of the created snapshot, {@code PUBLIC} by default; only {@code PUBLIC} and
 *     {@code PRIVATE} are accepted
 */
public record ZhihuArchiveCreateRequest(
        @NotNull @Valid ZhihuArchivePayload payload,

        @NotBlank @Size(max = ZhihuArchiveLimits.MAX_POLICY_NAME_LENGTH)
        String policyName,

        @Pattern(regexp = "PUBLIC|PRIVATE", message = "visible must be PUBLIC or PRIVATE")
        String visible) {

    /**
     * Returns the requested visibility, defaulting to {@code PUBLIC} when absent.
     *
     * @return non-null visibility value
     */
    public String visibleOrDefault() {
        return visible == null || visible.isBlank() ? "PUBLIC" : visible.trim();
    }
}
