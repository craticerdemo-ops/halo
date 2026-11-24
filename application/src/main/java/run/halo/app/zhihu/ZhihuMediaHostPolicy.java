package run.halo.app.zhihu;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import run.halo.app.infra.properties.ZhihuArchiveProperties;

/**
 * Gatekeeper deciding whether a Zhihu asset URL may be downloaded.
 *
 * <p>Only absolute {@code https} URLs without credentials on an allowlisted host (or one of its subdomains) and on the
 * default HTTPS port are accepted. No DNS resolution is performed here; the secure HTTP client additionally blocks
 * private addresses when connecting.
 */
@Component
public class ZhihuMediaHostPolicy {

    private static final String SCHEME = "https";

    private static final int DEFAULT_HTTPS_PORT = 443;

    private static final Pattern IPV4_PATTERN = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");

    private final ZhihuArchiveProperties properties;

    public ZhihuMediaHostPolicy(ZhihuArchiveProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks whether the URL is an acceptable asset source.
     *
     * @param url the URL to check, may be {@code null}
     * @return {@code true} when the URL may be downloaded
     */
    public boolean isAllowed(String url) {
        return parse(url) != null;
    }

    /**
     * Parses the URL, rejecting anything that is not an acceptable asset source.
     *
     * @param url the URL to parse
     * @return the parsed URI
     * @throws IllegalArgumentException when the URL is not allowed
     */
    public URI parseAllowed(String url) {
        var uri = parse(url);
        if (uri == null) {
            throw new IllegalArgumentException("Asset URL is not allowed: " + url);
        }
        return uri;
    }

    private URI parse(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        final URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!uri.isAbsolute() || !SCHEME.equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
            return null;
        }
        var host = uri.getHost();
        if (!StringUtils.hasText(host) || isIpAddress(host)) {
            return null;
        }
        var port = uri.getPort();
        if (port != -1 && port != DEFAULT_HTTPS_PORT) {
            return null;
        }
        return isAllowedHost(host) ? uri : null;
    }

    private boolean isAllowedHost(String host) {
        var normalizedHost = host.toLowerCase(Locale.ROOT);
        var mediaHosts = properties.getMediaHosts();
        if (mediaHosts == null) {
            return false;
        }
        for (var entry : mediaHosts) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            var normalizedEntry = entry.trim().toLowerCase(Locale.ROOT);
            if (normalizedHost.equals(normalizedEntry) || normalizedHost.endsWith("." + normalizedEntry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpAddress(String host) {
        if (host.startsWith("[") || host.contains(":")) {
            return true;
        }
        return IPV4_PATTERN.matcher(host).matches();
    }
}
