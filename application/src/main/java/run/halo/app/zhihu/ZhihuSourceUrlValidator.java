package run.halo.app.zhihu;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Validates that a source URL points at a supported Zhihu origin over HTTPS. */
public final class ZhihuSourceUrlValidator {

    public static final Set<String> ALLOWED_HOSTS = Set.of("zhihu.com", "www.zhihu.com", "zhuanlan.zhihu.com");

    private ZhihuSourceUrlValidator() {}

    /**
     * Checks whether the given URL is an acceptable Zhihu source URL.
     *
     * @param url URL to check
     * @return {@code true} when the URL is valid
     */
    public static boolean isValid(String url) {
        try {
            validate(url);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Validates the given URL and throws when it is not an acceptable Zhihu source URL.
     *
     * @param url URL to validate
     * @throws IllegalArgumentException when the URL is null, blank, malformed, or not an acceptable Zhihu source URL
     */
    public static void validate(String url) {
        parse(url);
    }

    /**
     * Parses the given URL after validating its scheme, credentials, host, and port.
     *
     * @param url URL to parse
     * @return parsed URI, without any rewriting
     * @throws IllegalArgumentException when the URL is null, blank, malformed, or not an acceptable Zhihu source URL
     */
    public static URI parse(String url) {
        if (url == null) {
            throw new IllegalArgumentException("Source URL must not be null");
        }
        if (url.isBlank()) {
            throw new IllegalArgumentException("Source URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Source URL is not a valid URI: " + url, e);
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("Source URL must not be opaque: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Source URL must use HTTPS: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Source URL must not contain credentials: " + url);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("Source URL must contain a host: " + url);
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(normalizedHost)) {
            throw new IllegalArgumentException("Source URL host is not allowed: " + host);
        }
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new IllegalArgumentException("Source URL must not use a non-default port: " + port);
        }
        return uri;
    }
}
