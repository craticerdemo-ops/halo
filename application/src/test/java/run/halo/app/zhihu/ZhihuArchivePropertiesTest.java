package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import run.halo.app.infra.properties.ZhihuArchiveProperties;

class ZhihuArchivePropertiesTest {

    @Test
    void shouldProvideSensibleDefaults() {
        var properties = new ZhihuArchiveProperties();

        assertThat(properties.getMediaHosts()).containsExactly("zhimg.com", "zhihu.com");
        assertThat(properties.getMaxAssetSize()).isEqualTo(DataSize.ofMegabytes(10));
        assertThat(properties.getMaxVideoSize()).isEqualTo(DataSize.ofMegabytes(200));
        assertThat(properties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(15));
    }
}
