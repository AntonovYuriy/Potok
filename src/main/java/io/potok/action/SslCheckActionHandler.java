package io.potok.action;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Certificate expiry check: connects with TLS, reads the served leaf
 * certificate and reports how many days it has left.
 *
 * with: host (required), port (optional, 443).
 * Output: {host, port, days_left, not_after, subject, issuer}.
 *
 * Deliberately trusts ANY certificate: the whole point is inspecting certs
 * that may already be expired or self-signed — a validating trust manager
 * would abort the handshake before we could read them. Nothing sensitive is
 * transmitted; the connection is closed right after the handshake.
 *
 * Also the smallest realistic example of extending the engine: one bean
 * implementing ActionHandler, discovered automatically.
 */
@Component
public class SslCheckActionHandler implements ActionHandler {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Clock clock;
    private final io.potok.common.UrlGuard urlGuard;

    @Autowired
    public SslCheckActionHandler(io.potok.common.UrlGuard urlGuard) {
        this(Clock.systemUTC(), urlGuard);
    }

    SslCheckActionHandler(Clock clock, io.potok.common.UrlGuard urlGuard) {
        this.clock = clock;
        this.urlGuard = urlGuard;
    }

    @Override
    public String type() {
        return "ssl_check";
    }

    @Override
    public StepResult execute(StepContext ctx) {
        String host;
        try {
            host = ctx.requireString("host");
        } catch (IllegalArgumentException e) {
            return StepResult.fail(e.getMessage());
        }
        int port;
        try {
            port = Integer.parseInt(ctx.optionalString("port", "443"));
        } catch (NumberFormatException e) {
            return StepResult.fail("'port' must be a number");
        }

        try {
            urlGuard.checkHost(host);
            X509Certificate leaf = fetchLeafCertificate(host, port);
            return StepResult.ok(buildOutput(host, port, leaf, clock.instant()));
        } catch (io.potok.common.UrlGuard.BlockedUrlException e) {
            return StepResult.fail(e.getMessage());
        } catch (Exception e) {
            return StepResult.fail("ssl_check " + host + ":" + port + " failed: "
                    + io.potok.common.Errors.describe(e));
        }
    }

    /** Pure transform, unit-testable with a fixed clock. */
    static Map<String, Object> buildOutput(String host, int port, X509Certificate cert, Instant now) {
        long daysLeft = Duration.between(now, cert.getNotAfter().toInstant()).toDays();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("host", host);
        output.put("port", port);
        output.put("days_left", daysLeft);
        output.put("not_after", cert.getNotAfter().toInstant().toString());
        output.put("subject", cert.getSubjectX500Principal().getName());
        output.put("issuer", cert.getIssuerX500Principal().getName());
        return output;
    }

    private static X509Certificate fetchLeafCertificate(String host, int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAllForInspection()}, null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            socket.startHandshake();
            Certificate[] chain = socket.getSession().getPeerCertificates();
            if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
                throw new IllegalStateException("server presented no X.509 certificate");
            }
            return leaf;
        }
    }

    /** See class javadoc: inspection requires accepting expired/self-signed certs. */
    private static final class TrustAllForInspection implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
