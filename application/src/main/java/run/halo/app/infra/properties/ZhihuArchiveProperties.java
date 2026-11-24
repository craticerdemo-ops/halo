package run.halo.app.infra.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration for the Zhihu archive feature.
 *
 * @since 2.22.0
 */
@Data
@ConfigurationProperties("halo.zhihu-archive")
public class ZhihuArchiveProperties {

    /** Hosts (and their subdomains) from which archive assets may be downloaded. */
    private List<String> mediaHosts = new ArrayList<>(List.of("zhimg.com", "zhihu.com"));

    /** Maximum size of a single non-video asset. */
    private DataSize maxAssetSize = DataSize.ofMegabytes(10);

    /** Maximum size of a single video asset. */
    private DataSize maxVideoSize = DataSize.ofMegabytes(200);

    /** Maximum time to wait for an asset response. */
    private Duration responseTimeout = Duration.ofSeconds(15);
}
