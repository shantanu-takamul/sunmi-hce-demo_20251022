package com.lfi.p3hd.demo.net;

import android.util.Log;

import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.qr.QRConfig;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate-authority material: reading it, describing it, and turning it into a
 * trust manager.
 *
 * <h3>The two trust paths, and why there are two</h3>
 * The bundled {@code cbuae-CBDC-CA} anchor is installed by
 * {@code res/xml/network_security_config.xml}, scoped to the on-prem domain, entirely
 * outside this class. That is the shipping path and the one to prefer: the platform
 * enforces it, no HTTP code is involved, and it cannot be misconfigured at runtime.
 *
 * This class exists for what the NSC cannot express — a PEM the operator imports onto
 * the device after the APK was built, for a host or a rotated root nobody knew about at
 * build time. {@link #operatorOverrideFor} feeds that into {@link HttpClients}, and only
 * for the on-prem environments.
 *
 * <h3>What this class will not do</h3>
 * It never hand-implements {@link X509TrustManager}. Every trust decision goes through
 * {@link TrustManagerFactory}, which brings path building, basic-constraints checking,
 * expiry, and the rest of PKIX with it — the details that a hand-rolled
 * {@code checkServerTrusted} silently skips. It never touches the hostname verifier
 * either. An override widens which CAs are acceptable; it must not stop the name on the
 * certificate from having to match the host being dialled.
 */
public final class TrustStore {

    private static final String TAG = "TrustStore";

    /** File name of the operator-imported override, app-private storage. */
    private static final String OVERRIDE_FILE = "ca_override.pem";

    private TrustStore() {}

    // -------------------------------------------------------------------------
    // Pure certificate handling — no Android, no app state
    // -------------------------------------------------------------------------

    /**
     * Builds an {@link X509TrustManager} that accepts chains anchored at the
     * certificates in a PEM stream.
     *
     * Multi-certificate PEMs are supported and expected: a CA handed over as
     * "root plus issuing intermediate" is one file with two blocks, and taking only
     * the first would reject every chain the intermediate signed. The stream is fully
     * consumed and closed.
     *
     * @throws GeneralSecurityException if the PEM holds no usable certificate
     */
    public static X509TrustManager fromPem(InputStream pem)
            throws GeneralSecurityException, IOException {
        List<X509Certificate> anchors = certificatesFrom(pem);
        if (anchors.isEmpty()) {
            throw new GeneralSecurityException("no certificate found in the supplied PEM");
        }

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        for (int i = 0; i < anchors.size(); i++) {
            keyStore.setCertificateEntry("ca" + i, anchors.get(i));
        }

        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);

        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new GeneralSecurityException("no X509TrustManager produced for the supplied PEM");
    }

    /** Every X.509 certificate in a PEM (or DER) stream, in file order. */
    public static List<X509Certificate> certificatesFrom(InputStream pem)
            throws GeneralSecurityException, IOException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed = factory.generateCertificates(pem);
            List<X509Certificate> certs = new ArrayList<>(parsed.size());
            for (Certificate cert : parsed) {
                if (cert instanceof X509Certificate) certs.add((X509Certificate) cert);
            }
            return certs;
        } finally {
            pem.close();
        }
    }

    /**
     * A three-line human description of an anchor, for the Settings CA row.
     *
     * The fingerprint is the operationally important line: inside CB the only way to
     * confirm the terminal trusts the right root is to read this off the screen and
     * compare it against a value printed on paper. Colon-separated uppercase hex is
     * what {@code openssl x509 -fingerprint -sha256} prints, so the two can be compared
     * character by character.
     */
    public static String describe(X509Certificate cert) {
        if (cert == null) return "none";
        return "subject: " + cert.getSubjectDN().getName()
            + "\nSHA-256: " + fingerprintSha256(cert)
            + "\nexpires: " + formatUtcDay(cert.getNotAfter());
    }

    /** Uppercase colon-separated SHA-256 of the certificate's DER encoding. */
    public static String fingerprintSha256(X509Certificate cert) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder out = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (out.length() > 0) out.append(':');
                out.append(String.format(Locale.US, "%02X", b));
            }
            return out.toString();
        } catch (GeneralSecurityException e) {
            return "unavailable";
        }
    }

    private static String formatUtcDay(java.util.Date date) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }

    // -------------------------------------------------------------------------
    // The operator override — app-private file, on-prem environments only
    // -------------------------------------------------------------------------

    /**
     * The {@link HttpClients.TrustProvider} implementation.
     *
     * Returns null — meaning "platform trust, as scoped by the NSC" — for every cloud
     * environment and whenever no override has been imported. That null is what keeps
     * every existing environment byte-identical: an override can only ever affect
     * bootstrap and sit.
     */
    public static X509TrustManager operatorOverrideFor(String env) {
        if (!QRConfig.isOnPrem(env)) return null;

        File pem = overrideFile();
        if (pem == null || !pem.exists()) return null;

        try (InputStream in = new FileInputStream(pem)) {
            X509TrustManager tm = fromPem(in);
            Log.i(TAG, "ca-override: active for env=" + env);
            return tm;
        } catch (Exception e) {
            // Fall back to the bundled anchor rather than taking the terminal offline.
            Log.e(TAG, "ca-override: unreadable, falling back to the bundled anchor", e);
            return null;
        }
    }

    /** True when the operator has imported a CA override onto this device. */
    public static boolean hasOverride() {
        File pem = overrideFile();
        return pem != null && pem.exists();
    }

    /**
     * Stores a PEM as the operator override after checking it parses.
     *
     * Validated before it is written: an unparseable file saved here would silently
     * do nothing, and "I imported the CA and it still fails" is the worst possible
     * thing to be debugging over a VDI session.
     *
     * @return the description of the first anchor it contains
     */
    public static String importOverride(InputStream pem)
            throws GeneralSecurityException, IOException {
        byte[] bytes = readAll(pem);
        List<X509Certificate> certs =
            certificatesFrom(new ByteArrayInputStream(bytes));
        if (certs.isEmpty()) {
            throw new GeneralSecurityException("no certificate found in the supplied PEM");
        }

        File target = overrideFile();
        if (target == null) throw new IOException("app storage unavailable");
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        HttpClients.invalidate();
        Log.i(TAG, "ca-override: imported " + certs.size() + " certificate(s)");
        return describe(certs.get(0));
    }

    /** Removes the override, returning the terminal to the bundled anchor. */
    public static void clearOverride() {
        File pem = overrideFile();
        if (pem != null && pem.exists() && !pem.delete()) {
            Log.w(TAG, "ca-override: could not delete " + pem);
        }
        HttpClients.invalidate();
    }

    /**
     * What the Settings CA row shows: which anchor is in force, and its details.
     *
     * Reads the override when there is one and the bundled {@code res/raw} PEM
     * otherwise, so the row describes what the next request will actually use rather
     * than what the build intended.
     */
    public static String describeActiveAnchor(String env) {
        try {
            if (QRConfig.isOnPrem(env) && hasOverride()) {
                try (InputStream in = new FileInputStream(overrideFile())) {
                    List<X509Certificate> certs = certificatesFrom(in);
                    if (!certs.isEmpty()) {
                        return "operator override (imported on this device)\n"
                            + describe(certs.get(0));
                    }
                }
            }
            try (InputStream in = MyApplication.app.getResources()
                    .openRawResource(com.lfi.p3hd.demo.R.raw.cbuae_root_ca)) {
                List<X509Certificate> certs = certificatesFrom(in);
                if (!certs.isEmpty()) {
                    return "bundled in the APK, scoped to rcbdc.digitaldirham.gov.ae\n"
                        + describe(certs.get(0));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "could not describe the active trust anchor", e);
        }
        return "unavailable";
    }

    private static File overrideFile() {
        try {
            return new File(MyApplication.app.getFilesDir(), OVERRIDE_FILE);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        in.close();
        return buffer.toByteArray();
    }
}
