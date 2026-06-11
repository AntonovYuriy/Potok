package io.potok.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF guard for user-supplied URLs (http action, pollers, preview).
 * Resolves the target host and rejects addresses that point inside the
 * deployment: loopback, RFC1918 private ranges, link-local (which includes
 * the 169.254.169.254 cloud metadata endpoint), wildcard and IPv6
 * unique-local. POTOK_ALLOW_PRIVATE_URLS=true disables the guard for
 * self-hosted setups that genuinely automate internal services.
 *
 * Honest limitations (documented in README): the initial URL is checked, a
 * redirect chain is not re-checked, and a DNS answer that changes between
 * this check and the request (DNS rebinding) is not caught.
 */
@Component
public class UrlGuard {

    private final boolean allowPrivate;

    public UrlGuard(@Value("${potok.allow-private-urls:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /** @throws BlockedUrlException when the URL resolves to a private/internal address */
    public void check(String url) {
        if (allowPrivate) {
            return;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return; // unparseable URL: let the HTTP client produce its own error
        }
        if (host != null) {
            checkHost(host);
        }
    }

    /** Same check for actions that take a bare host (ssl_check). */
    public void checkHost(String host) {
        if (allowPrivate) {
            return;
        }
        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return; // unresolvable: the request itself will fail with a clear DNS error
        }
        for (InetAddress address : resolved) {
            if (isPrivate(address)) {
                throw new BlockedUrlException(
                        "refusing to call '" + host + "': it resolves to a private/internal address ("
                                + address.getHostAddress() + "). Potok blocks loopback, private (RFC1918), "
                                + "link-local and cloud-metadata addresses to prevent SSRF; "
                                + "set POTOK_ALLOW_PRIVATE_URLS=true if this instance should reach internal hosts.");
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique-local fc00::/7 — not covered by isSiteLocalAddress (deprecated fec0::/10 only)
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    public static class BlockedUrlException extends RuntimeException {
        public BlockedUrlException(String message) {
            super(message);
        }
    }
}
