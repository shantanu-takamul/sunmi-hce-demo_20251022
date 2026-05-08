package com.lfi.p3hd.demo.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.lfi.p3hd.demo.qr.QRConfig;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Manages the LFI API key lifecycle.
 *
 * State machine:
 *   IDLE ──► FETCHING ──► READY
 *                    └──► FAILED
 *
 * All callbacks are delivered on the main thread.
 * Multiple callers calling ensureReady() while a fetch is in flight are
 * queued and notified together when the fetch settles.
 */
public class ApiKeyManager {

    public interface OnReadyCallback {
        void onReady();
    }

    public interface OnErrorCallback {
        void onError(String message);
    }

    private enum State { IDLE, FETCHING, READY, FAILED }

    private static final String TAG = "ApiKeyManager";
    private static final String LFI_AUTH_USERNAME = "test-global-admin";
    private static final String LFI_AUTH_PASSWORD = "12345";
    private static final String LFI_AUTH_REALM    = "cbuae";

    private static final ApiKeyManager INSTANCE = new ApiKeyManager();

    public static ApiKeyManager get() {
        return INSTANCE;
    }

    private final OkHttpClient httpClient = new OkHttpClient();
    private final Handler mainHandler    = new Handler(Looper.getMainLooper());

    private State state = State.IDLE;
    private String lastError = "";
    private final List<OnReadyCallback>  pendingReady  = new ArrayList<>();
    private final List<OnErrorCallback>  pendingError  = new ArrayList<>();

    private ApiKeyManager() {}

    /**
     * Ensure a valid API key is available, then call onReady (main thread).
     *
     * - Key already in prefs  → onReady immediately
     * - Fetch in progress      → queued; fired when fetch settles
     * - Last fetch failed      → onError immediately (caller can retry via fetch())
     * - No key, state IDLE     → starts a fresh fetch
     */
    public synchronized void ensureReady(OnReadyCallback onReady, OnErrorCallback onError) {
        if (!PreferencesUtil.getLfiApiKey().isEmpty()) {
            state = State.READY;
            mainHandler.post(onReady::onReady);
            return;
        }

        if (state == State.FAILED) {
            // Reset and retry — don't cache failure across user-initiated taps.
            state = State.IDLE;
            lastError = "";
        }

        pendingReady.add(onReady);
        pendingError.add(onError);

        if (state == State.FETCHING) return;

        state = State.FETCHING;
        startLogin();
    }

    /**
     * Force a fresh fetch regardless of current state.
     * Used by Settings when the env changes or the user taps "Refresh Key".
     */
    public synchronized void fetch(OnReadyCallback onReady, OnErrorCallback onError) {
        PreferencesUtil.setLfiApiKey("");
        state = State.IDLE;
        lastError = "";
        pendingReady.clear();
        pendingError.clear();
        ensureReady(onReady, onError);
    }

    private void startLogin() {
        try {
            JSONObject body = new JSONObject();
            body.put("username", LFI_AUTH_USERNAME);
            body.put("password", LFI_AUTH_PASSWORD);
            body.put("realm",    LFI_AUTH_REALM);

            Request req = new Request.Builder()
                .url(QRConfig.getBaseUrl() + QRConfig.AUTH_ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverError("No connection to payment gateway");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() == null ? "" : response.body().string();
                    Log.d(TAG, "login [" + response.code() + "]");
                    if (!response.isSuccessful()) {
                        deliverError("Gateway auth failed (HTTP " + response.code() + ")");
                        return;
                    }
                    try {
                        String token = new JSONObject(respBody).getString("access_token");
                        startRegen(token);
                    } catch (Exception e) {
                        deliverError("Gateway auth response invalid");
                    }
                }
            });
        } catch (Exception e) {
            deliverError("Failed to reach payment gateway: " + e.getMessage());
        }
    }

    private void startRegen(String accessToken) {
        try {
            JSONObject body = new JSONObject();
            body.put("keyType",    "PRIMARY");
            body.put("expiryDays", 90);

            Request req = new Request.Builder()
                .url(QRConfig.getBaseUrl() + QRConfig.getRegenEndpoint())
                .addHeader("Authorization",     "Bearer " + accessToken)
                .addHeader("X-LFI-ID",          QRConfig.getXLfiId())
                .addHeader("X-Idempotency-Key", UUID.randomUUID().toString())
                .addHeader("Content-Type",      "application/json")
                .post(RequestBody.create(MediaType.parse("application/json"), body.toString()))
                .build();

            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deliverError("No connection to payment gateway");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() == null ? "" : response.body().string();
                    Log.d(TAG, "regen [" + response.code() + "]");
                    if (!response.isSuccessful()) {
                        deliverError("Gateway key setup failed (HTTP " + response.code() + ")");
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(respBody);
                        String apiKey    = json.getString("apiKey");
                        String expiresAt = json.optString("expiresAt", "");
                        PreferencesUtil.setLfiApiKey(apiKey);
                        PreferencesUtil.setLfiApiKeyExpiry(expiresAt);
                        deliverReady();
                    } catch (Exception e) {
                        deliverError("Gateway key setup response invalid");
                    }
                }
            });
        } catch (Exception e) {
            deliverError("Failed to reach payment gateway: " + e.getMessage());
        }
    }

    private synchronized void deliverReady() {
        state = State.READY;
        List<OnReadyCallback> callbacks = new ArrayList<>(pendingReady);
        pendingReady.clear();
        pendingError.clear();
        for (OnReadyCallback cb : callbacks) {
            mainHandler.post(cb::onReady);
        }
    }

    private synchronized void deliverError(String message) {
        state = State.FAILED;
        lastError = message;
        List<OnErrorCallback> callbacks = new ArrayList<>(pendingError);
        pendingReady.clear();
        pendingError.clear();
        for (OnErrorCallback cb : callbacks) {
            mainHandler.post(() -> cb.onError(message));
        }
    }
}
