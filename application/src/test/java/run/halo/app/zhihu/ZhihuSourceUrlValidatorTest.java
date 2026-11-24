package run.halo.app.zhihu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ZhihuSourceUrlValidatorTest {

    @ParameterizedTest
    @MethodSource("acceptedUrls")
    void shouldAcceptSupportedUrls(String url) {
        assertThat(ZhihuSourceUrlValidator.isValid(url)).isTrue();
        assertThat(ZhihuSourceUrlValidator.parse(url).getHost()).isNotBlank();
        ZhihuSourceUrlValidator.validate(url);
    }

    @ParameterizedTest
    @MethodSource("rejectedUrls")
    void shouldRejectUnsupportedUrls(String url) {
        assertThat(ZhihuSourceUrlValidator.isValid(url)).isFalse();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ZhihuSourceUrlValidator.validate(url))
                .withMessageContaining("Source URL");
    }

    @Test
    void shouldReturnParsedUriVerbatim() {
        String url = "https://www.zhihu.com/question/1/answer/2?utm=abc";

        URI uri = ZhihuSourceUrlValidator.parse(url);

        assertThat(uri.toString()).isEqualTo(url);
        assertThat(uri.getHost()).isEqualTo("www.zhihu.com");
    }

    static Stream<Arguments> acceptedUrls() {
        return Stream.of(
                Arguments.of("https://zhihu.com/"),
                Arguments.of("https://www.zhihu.com/question/1/answer/2"),
                Arguments.of("https://zhuanlan.zhihu.com/p/123"),
                Arguments.of("https://zhihu.com:443/p/1"));
    }

    static Stream<Arguments> rejectedUrls() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("   "),
                Arguments.of((String) null),
                Arguments.of("http://zhihu.com/p/1"),
                Arguments.of("https://evil.com/p/1"),
                Arguments.of("https://zhihu.com.evil.com/p/1"),
                Arguments.of("https://sub.zhihu.com/p/1"),
                Arguments.of("https://user:pass@zhihu.com/p/1"),
                Arguments.of("https://zhihu.com:8443/p/1"),
                Arguments.of("javascript:alert(1)"),
                Arguments.of("ftp://zhihu.com/p/1"));
    }
}
