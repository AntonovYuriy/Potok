package io.potok.action;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SslCheckActionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");

    /** Hand-rolled X509 stub: only the fields buildOutput reads matter. */
    private static X509Certificate stub(Instant notAfter) {
        return new X509Certificate() {
            @Override
            public Date getNotAfter() {
                return Date.from(notAfter);
            }

            @Override
            public javax.security.auth.x500.X500Principal getSubjectX500Principal() {
                return new javax.security.auth.x500.X500Principal("CN=unit.test");
            }

            @Override
            public javax.security.auth.x500.X500Principal getIssuerX500Principal() {
                return new javax.security.auth.x500.X500Principal("CN=unit.issuer");
            }

            // —— unused below ——
            @Override
            public void checkValidity() {
            }

            @Override
            public void checkValidity(Date date) {
            }

            @Override
            public int getVersion() {
                return 3;
            }

            @Override
            public BigInteger getSerialNumber() {
                return BigInteger.ONE;
            }

            @Override
            public java.security.Principal getIssuerDN() {
                return getIssuerX500Principal();
            }

            @Override
            public java.security.Principal getSubjectDN() {
                return getSubjectX500Principal();
            }

            @Override
            public Date getNotBefore() {
                return Date.from(NOW.minusSeconds(86400));
            }

            @Override
            public byte[] getTBSCertificate() {
                return new byte[0];
            }

            @Override
            public byte[] getSignature() {
                return new byte[0];
            }

            @Override
            public String getSigAlgName() {
                return "SHA256withRSA";
            }

            @Override
            public String getSigAlgOID() {
                return "";
            }

            @Override
            public byte[] getSigAlgParams() {
                return new byte[0];
            }

            @Override
            public boolean[] getIssuerUniqueID() {
                return new boolean[0];
            }

            @Override
            public boolean[] getSubjectUniqueID() {
                return new boolean[0];
            }

            @Override
            public boolean[] getKeyUsage() {
                return new boolean[0];
            }

            @Override
            public int getBasicConstraints() {
                return -1;
            }

            @Override
            public byte[] getEncoded() {
                return new byte[0];
            }

            @Override
            public void verify(java.security.PublicKey key) {
            }

            @Override
            public void verify(java.security.PublicKey key, String sigProvider) {
            }

            @Override
            public String toString() {
                return "stub";
            }

            @Override
            public java.security.PublicKey getPublicKey() {
                try {
                    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                    g.initialize(2048);
                    KeyPair kp = g.generateKeyPair();
                    return kp.getPublic();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public boolean hasUnsupportedCriticalExtension() {
                return false;
            }

            @Override
            public java.util.Set<String> getCriticalExtensionOIDs() {
                return java.util.Set.of();
            }

            @Override
            public java.util.Set<String> getNonCriticalExtensionOIDs() {
                return java.util.Set.of();
            }

            @Override
            public byte[] getExtensionValue(String oid) {
                return new byte[0];
            }
        };
    }

    @Test
    void daysLeftComputedFromNotAfter() {
        Map<String, Object> out = SslCheckActionHandler.buildOutput(
                "example.com", 443, stub(NOW.plusSeconds(30L * 86400)), NOW);

        assertThat(out.get("days_left")).isEqualTo(30L);
        assertThat(out.get("host")).isEqualTo("example.com");
        assertThat((String) out.get("subject")).contains("unit.test");
        assertThat((String) out.get("not_after")).startsWith("2026-07-11");
    }

    @Test
    void expiredCertificateGivesNegativeDays() {
        Map<String, Object> out = SslCheckActionHandler.buildOutput(
                "old.example", 443, stub(NOW.minusSeconds(5L * 86400)), NOW);

        assertThat(out.get("days_left")).isEqualTo(-5L);
    }

    @Test
    void partialDayRoundsDown() {
        Map<String, Object> out = SslCheckActionHandler.buildOutput(
                "x", 443, stub(NOW.plusSeconds(86400 + 3600)), NOW);

        assertThat(out.get("days_left")).isEqualTo(1L);
    }

    @Test
    void missingHostFailsGracefully() {
        SslCheckActionHandler handler = new SslCheckActionHandler(
                Clock.fixed(NOW, ZoneOffset.UTC));
        StepResult result = handler.execute(new StepContext(
                UUID.randomUUID(), "wf", "check", Map.of(), 1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("host");
    }
}
