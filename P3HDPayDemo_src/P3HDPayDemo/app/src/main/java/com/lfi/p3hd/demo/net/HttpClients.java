package com.lfi.p3hd.demo.net;

import android.util.Log;

import com.lfi.p3hd.demo.utils.PreferencesUtil;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * The single owner of every OkHttpClient this app uses.
 *
 * Before this class existed, six activities and {@code ApiKeyManager} each held a
 * {@code final OkHttpClient} field built at construction. {@code ApiKeyManager} is a
 * static singleton, so its client was frozen at class load: an environment switch in
 * Settings never reached it, and neither would any per-environment TLS configuration.
 * That is the bug this seam closes — resolve the client per use, key it by env, and
 * throw the cache away when the env or the trust material changes.
 *
 * <h3>One base, many variants</h3>
 * Every client is derived from {@link #BASE} through {@code newBuilder()}, which copies
 * the dispatcher and connection pool by reference. Building clients with
 * {@code new OkHttpClient()} instead would give each one its own thread pool and its own
 * idle connections — on a terminal that polls every two seconds that is real waste.
 *
 * <h3>Redirects</h3>
 * Off, deliberately. Every {@code *.takamul.cc} backend sits behind Cloudflare Access,
 * and a device without WARP gets a 302 to the Access login page. Following it would
 * return HTML with a 200, which fails JSON parsing and looks like a malformed gateway
 * response. Leaving the 302 intact lets the caller report the real cause. No gateway API
 * legitimately redirects.
 */
public final class HttpClients {

    private static final String TAG = "HttpClients";

    /**
     * Supplies the trust manager an environment's client must use.
     *
     * Returning {@code null} — the normal case — means "use the platform default", i.e.
     * the system trust store as narrowed by {@code res/xml/network_security_config.xml}.
     * That NSC is where the bundled {@code cbuae-CBDC-CA} anchor is scoped to the
     * on-prem domain; nothing here needs to know about it. A non-null trust manager is
     * the operator override path: a CA PEM imported onto the device at runtime, for
     * which no build-time NSC entry can exist.
     */
    public interface TrustProvider {
        X509TrustManager trustFor(String env);
    }

    /** Everything else is a {@code newBuilder()} of this. */
    private static final OkHttpClient BASE = new OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build();

    private static final Map<String, OkHttpClient> CACHE = new HashMap<>();

    /** Replaced in {@code MyApplication} with the operator-override reader. */
    private static TrustProvider trustProvider = env -> null;

    private HttpClients() {}

    /**
     * Installs the trust provider and drops every cached client.
     *
     * Called once at startup. Exists as a seam rather than a hard dependency so the
     * scope test can drive {@link #forEnv} from a plain JVM with no Android runtime.
     */
    public static synchronized void setTrustProvider(TrustProvider provider) {
        trustProvider = provider == null ? env -> null : provider;
        CACHE.clear();
    }

    /** The client for whatever environment Settings currently points at. */
    public static OkHttpClient forCurrentEnv() {
        return forEnv(PreferencesUtil.getEnv());
    }

    /**
     * The client for one environment, built once and reused.
     *
     * Environments that need no override share a single instance: identical
     * configuration, one connection pool. An environment with an operator CA override
     * gets its own instance carrying that trust manager, and only that one.
     */
    public static synchronized OkHttpClient forEnv(String env) {
        OkHttpClient cached = CACHE.get(env);
        if (cached != null) return cached;

        X509TrustManager override = null;
        try {
            override = trustProvider.trustFor(env);
        } catch (Exception e) {
            // A broken override must not take the terminal offline; the platform
            // trust store still covers every bundled anchor.
            Log.e(TAG, "trust provider failed for env=" + env + ", using platform trust", e);
        }

        OkHttpClient client = override == null ? BASE : withTrust(override);
        CACHE.put(env, client);
        return client;
    }

    /**
     * Drops every cached client.
     *
     * Call after anything that changes which client an env should get: the env itself,
     * an imported or cleared CA override.
     */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    private static OkHttpClient withTrust(X509TrustManager trustManager) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            // keyManagers stays null: this terminal presents no client certificate.
            // The platform's require-client-cert flag is dormant today; when mTLS is
            // switched on, the POS key material goes in this first parameter and
            // nothing else about this class changes.
            ctx.init(null /* keyManagers: mTLS later */,
                     new TrustManager[]{ trustManager },
                     null);
            return BASE.newBuilder()
                .sslSocketFactory(ctx.getSocketFactory(), trustManager)
                .build();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "could not install the CA override, using platform trust", e);
            return BASE;
        }
    }
}
