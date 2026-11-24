package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.halo.app.infra.properties.ZhihuArchiveProperties;

class ZhihuMediaHostPolicyTest {

    private final ZhihuMediaHostPolicy policy = new ZhihuMediaHostPolicy(new ZhihuArchiveProperties());

    @ParameterizedTest
    @MethodSource("acceptedUrls")
    void shouldAcceptAllowedUrls(String url) {
        assertThat(policy.isAllowed(url)).isTrue();
        assertThat(policy.parseAllowed(url).getHost()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("rejectedUrls")
    void shouldRejectDisallowedUrls(String url) {
        assertThat(policy.isAllowed(url)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> policy.parseAllowed(url));
    }

    @Test
    void shouldRejectBlankUrl() {
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.isAllowed("")).isFalse();
        assertThat(policy.isAllowed("   ")).isFalse();
    }

    static Stream<Arguments> acceptedUrls() {
        return Stream.of(
                Arguments.of("https://pic1.zhimg.com/a.jpg"),
                Arguments.of("https://www.zhihu.com/a.png"),
                Arguments.of("https://zhihu.com/a.png"),
                Arguments.of("https://pic1.zhimg.com:443/a.jpg"));
    }

    static Stream<Arguments> rejectedUrls() {
        return Stream.of(
                Arguments.of("http://pic1.zhimg.com/a.jpg"),
                Arguments.of("https://evil.com/a.jpg"),
                Arguments.of("https://zhimg.com.evil.com/a.jpg"),
                Arguments.of("https://evilzhimg.com/a.jpg"),
                Arguments.of("https://user:pass@pic1.zhimg.com/a.jpg"),
                Arguments.of("https://127.0.0.1/a.jpg"),
                Arguments.of("https://pic1.zhimg.com:8443/a.jpg"));
    }
}
