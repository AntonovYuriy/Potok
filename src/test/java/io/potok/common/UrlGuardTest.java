package io.potok.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlGuardTest {

    private final UrlGuard guard = new UrlGuard(false);

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8080/x",        // loopback
            "http://localhost/x",             // loopback via name
            "http://10.1.2.3/",               // RFC1918 10/8
            "http://172.16.0.1/",             // RFC1918 172.16/12
            "http://192.168.1.1/api",         // RFC1918 192.168/16
            "http://169.254.169.254/latest/meta-data/", // link-local = cloud metadata
            "http://[::1]/",                  // IPv6 loopback
            "http://[fc00::1]/",              // IPv6 unique-local
            "http://0.0.0.0/",                // wildcard
    })
    void blocksPrivateAddresses(String url) {
        assertThatThrownBy(() -> guard.check(url))
                .isInstanceOf(UrlGuard.BlockedUrlException.class)
                .hasMessageContaining("private/internal address")
                .hasMessageContaining("POTOK_ALLOW_PRIVATE_URLS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://93.184.216.34/", "https://8.8.8.8/dns"})
    void allowsPublicAddresses(String url) {
        assertThatCode(() -> guard.check(url)).doesNotThrowAnyException();
    }

    @Test
    void allowFlagDisablesTheGuard() {
        UrlGuard permissive = new UrlGuard(true);
        assertThatCode(() -> permissive.check("http://127.0.0.1/")).doesNotThrowAnyException();
        assertThatCode(() -> permissive.checkHost("169.254.169.254")).doesNotThrowAnyException();
    }

    @Test
    void unresolvableHostPassesThrough() {
        // DNS failure is the HTTP client's error to report, not the guard's
        assertThatCode(() -> guard.check("http://definitely-not-a-host.invalid/"))
                .doesNotThrowAnyException();
    }

    @Test
    void unparseableUrlPassesThrough() {
        assertThatCode(() -> guard.check("not a url at all")).doesNotThrowAnyException();
    }

    @Test
    void checkHostBlocksBareHosts() {
        assertThatThrownBy(() -> guard.checkHost("192.168.0.10"))
                .isInstanceOf(UrlGuard.BlockedUrlException.class);
    }
}
