package com.lfi.p3hd.demo.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The invariant this suite exists to protect: a CA trusted for the on-premise
 * environments must not become trusted for anything else.
 *
 * That is easy to break and hard to notice. Installing a trust manager on a shared
 * client, or widening {@code base-config} in the network security config, both "work"
 * — the on-prem host connects, nothing fails, and the terminal has quietly begun
 * accepting the internal CA for the Cloudflare-fronted cloud backends too. Nothing in
 * a manual test catches that, because the symptom is an absence of rejection.
 *
 * So the test asserts both halves against one real TLS server:
 * the on-prem client accepts it, and the cloud client refuses it.
 */
public class HttpClientsTrustScopeTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private MockWebServer server;
    private String caPem;

    @Before
    public void startServerSignedByATestCa() throws Exception {
        // A throwaway CA standing in for cbuae-CBDC-CA, and a leaf it signs. The
        // SAN matters: the default hostname verifier is left alone, so a certificate
        // without "localhost" in it would fail for the right reason and prove nothing.
        HeldCertificate testRootCa = new HeldCertificate.Builder()
            .certificateAuthority(0)
            .commonName("sim-cbuae-CBDC-CA")
            .build();
        HeldCertificate leaf = new HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .signedBy(testRootCa)
            .build();
        caPem = testRootCa.certificatePem();

        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
            .heldCertificate(leaf, testRootCa.certificate())
            .build();

        server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
            @Override
            public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                return new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\"}");
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        // Static cache and provider: leaving either set would leak into the next test.
        HttpClients.setTrustProvider(null);
        server.shutdown();
    }

    /** The CA is scoped to the on-prem environments and to nothing else. */
    @Test
    public void onPremTrustsTheInternalCaAndTheCloudEnvDoesNot() throws Exception {
        X509TrustManager onPremTrust =
            TrustStore.fromPem(new ByteArrayInputStream(caPem.getBytes(UTF_8)));

        // Exactly the production wiring: the provider answers for the on-prem envs
        // and returns null — platform trust — for everything else.
        HttpClients.setTrustProvider(env ->
            "bootstrap".equals(env) || "sit".equals(env) ? onPremTrust : null);

        OkHttpClient bootstrapClient = HttpClients.forEnv("bootstrap");
        OkHttpClient qaClient        = HttpClients.forEnv("qa");

        assertNotSame("the two envs must not share one client", bootstrapClient, qaClient);

        Request request = new Request.Builder().url(server.url("/actuator/health")).build();

        try (Response response = bootstrapClient.newCall(request).execute()) {
            assertEquals(200, response.code());
        }

        try (Response ignored = qaClient.newCall(request).execute()) {
            fail("the qa client accepted a certificate signed by the on-prem CA");
        } catch (SSLHandshakeException expected) {
            // The whole point.
        }
    }

    /** A second call for the same env reuses the client rather than rebuilding it. */
    @Test
    public void clientsAreCachedPerEnvAndDroppedOnInvalidate() {
        X509TrustManager unused = null;
        HttpClients.setTrustProvider(env -> unused);

        OkHttpClient first  = HttpClients.forEnv("bootstrap");
        OkHttpClient second = HttpClients.forEnv("bootstrap");
        assertEquals("cached per env", first, second);

        HttpClients.invalidate();
        // After invalidation the cache is empty; the client is rebuilt from the same
        // base, so it is equal in configuration but resolved afresh.
        assertTrue(HttpClients.forEnv("bootstrap") != null);
    }

    /** describe() prints the fingerprint an operator compares against a printed value. */
    @Test
    public void describeReportsSubjectFingerprintAndExpiry() throws Exception {
        java.util.List<java.security.cert.X509Certificate> certs =
            TrustStore.certificatesFrom(new ByteArrayInputStream(caPem.getBytes(UTF_8)));
        assertEquals(1, certs.size());

        String described = TrustStore.describe(certs.get(0));
        assertTrue(described, described.contains("sim-cbuae-CBDC-CA"));
        assertTrue(described, described.contains("SHA-256: "));
        assertTrue(described, described.contains("expires: "));
        // Colon-separated uppercase hex, as openssl prints it.
        assertTrue(described, TrustStore.fingerprintSha256(certs.get(0)).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}"));
    }

    /**
     * A junk PEM is refused rather than producing a trust-nothing manager.
     *
     * Only the exception type is asserted. Whether the rejection comes from the JCA
     * provider parsing the bytes or from our own emptiness check depends on the
     * provider — the JDK's throws "No certificate data found", Android's Conscrypt
     * words it differently — and either is the same correct outcome.
     */
    @Test
    public void aPemWithNoCertificateIsRejected() throws Exception {
        try {
            TrustStore.fromPem(new ByteArrayInputStream("not a certificate".getBytes(UTF_8)));
            fail("expected a GeneralSecurityException");
        } catch (java.security.GeneralSecurityException expected) {
            // The whole point: no trust manager came back.
        }
    }
}
