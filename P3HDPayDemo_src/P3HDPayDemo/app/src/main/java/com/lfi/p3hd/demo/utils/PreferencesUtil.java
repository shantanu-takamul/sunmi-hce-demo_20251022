package com.lfi.p3hd.demo.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.qr.QRConfig;

public class PreferencesUtil {
    private static final String PREF_NAME = "p3hd_pref";
    private static final String KEY_ENV = "auth:env";
    private static final String KEY_WALLET_ID = "wallet_id";
    private static final String KEY_LFI_API_KEY = "lfi_api_key";
    private static final String KEY_LFI_API_KEY_EXPIRY = "lfi_api_key_expiry";
    private static final String KEY_LFI_API_KEY_MANUAL = "lfi_api_key_manual";
    private static final String KEY_NFC_WALLET_ID    = "nfc_wallet_id";
    private static final String KEY_NFC_WALLET_TYPE  = "nfc_wallet_type";
    private static final String KEY_NFC_MERCHANT_NAME = "nfc_merchant_name";

    private static SharedPreferences prefs() {
        return MyApplication.app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String getEnv() {
        return prefs().getString(KEY_ENV, "qa");
    }

    public static void setEnv(String env) {
        prefs().edit().putString(KEY_ENV, env).apply();
        // Clients are cached per env and carry that env's trust configuration, so a
        // stale one would keep talking to the old environment's rules.
        HttpClients.invalidate();
    }

    public static String getWalletId() {
        return prefs().getString(KEY_WALLET_ID, "");
    }

    public static void setWalletId(String walletId) {
        prefs().edit().putString(KEY_WALLET_ID, walletId).apply();
    }

    /** The gateway credential. Keystore-encrypted where the device allows it. */
    public static String getLfiApiKey() {
        return SecurePrefs.getString(prefs(), KEY_LFI_API_KEY, "");
    }

    public static void setLfiApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            SecurePrefs.remove(prefs(), KEY_LFI_API_KEY);
            return;
        }
        SecurePrefs.putString(prefs(), KEY_LFI_API_KEY, apiKey);
    }

    /**
     * True when the key was pasted in Settings rather than fetched from the portal.
     * A manual key must never be silently replaced by the login+regen flow —
     * on envs where the POS account cannot mint keys, it is the only working key.
     */
    public static boolean isLfiApiKeyManual() {
        return prefs().getBoolean(KEY_LFI_API_KEY_MANUAL, false);
    }

    public static void setLfiApiKeyManual(boolean manual) {
        prefs().edit().putBoolean(KEY_LFI_API_KEY_MANUAL, manual).apply();
    }

    public static String getLfiApiKeyExpiry() {
        return prefs().getString(KEY_LFI_API_KEY_EXPIRY, "");
    }

    public static void setLfiApiKeyExpiry(String expiry) {
        prefs().edit().putString(KEY_LFI_API_KEY_EXPIRY, expiry).apply();
    }

    public static String getNfcWalletId() {
        return prefs().getString(KEY_NFC_WALLET_ID, "");
    }

    public static void setNfcWalletId(String walletId) {
        prefs().edit().putString(KEY_NFC_WALLET_ID, walletId).apply();
    }

    public static String getNfcWalletType() {
        return prefs().getString(KEY_NFC_WALLET_TYPE, "MERCHANT_COLLECTION");
    }

    public static void setNfcWalletType(String walletType) {
        prefs().edit().putString(KEY_NFC_WALLET_TYPE, walletType).apply();
    }

    public static String getNfcMerchantName() {
        return prefs().getString(KEY_NFC_MERCHANT_NAME, "");
    }

    public static void setNfcMerchantName(String merchantName) {
        prefs().edit().putString(KEY_NFC_MERCHANT_NAME, merchantName).apply();
    }

    // -------------------------------------------------------------------------
    // On-premise environment configuration
    //
    // The cloud environments are compiled in; these are not. Their hosts, acquirer
    // id and operator credentials are assigned by CB infrastructure and can be
    // reassigned, and nobody outside CB can test a change to them — so all of it is
    // field-editable, and none of it has a usable hardcoded value.
    //
    // Keys are suffixed per environment rather than shared: bootstrap and sit are
    // different boxes with different credentials, and one silently clobbering the
    // other on an env switch is not a failure anyone would diagnose quickly.
    // -------------------------------------------------------------------------

    private static final String SUFFIX_BASE_URL        = "_base_url";
    private static final String SUFFIX_LFI_ID          = "_lfi_id";
    private static final String SUFFIX_PORTAL_USERNAME = "_portal_username";
    private static final String SUFFIX_PORTAL_PASSWORD = "_portal_password";

    /** e.g. {@code bootstrap_base_url}, {@code sit_portal_username}. */
    private static String envKey(String env, String suffix) {
        return env + suffix;
    }

    /** The operator's base URL for an on-prem env, or the compiled-in default. */
    public static String getOnPremBaseUrl(String env) {
        String stored = prefs().getString(envKey(env, SUFFIX_BASE_URL), "");
        return stored.isEmpty() ? QRConfig.defaultOnPremBaseUrl(env) : stored;
    }

    /** Stores a base URL; an empty value restores the compiled-in default. */
    public static void setOnPremBaseUrl(String env, String baseUrl) {
        prefs().edit().putString(envKey(env, SUFFIX_BASE_URL), baseUrl).apply();
        // Changing the host is exactly when a CA override starts or stops applying.
        HttpClients.invalidate();
    }

    /** True when the operator has overridden this env's compiled-in base URL. */
    public static boolean hasOnPremBaseUrlOverride(String env) {
        return !prefs().getString(envKey(env, SUFFIX_BASE_URL), "").isEmpty();
    }

    /** The operator's X-LFI-ID for an on-prem env, or the compiled-in default. */
    public static String getOnPremLfiId(String env) {
        String stored = prefs().getString(envKey(env, SUFFIX_LFI_ID), "");
        return stored.isEmpty() ? QRConfig.ON_PREM_LFI_ID_DEFAULT : stored;
    }

    public static void setOnPremLfiId(String env, String lfiId) {
        prefs().edit().putString(envKey(env, SUFFIX_LFI_ID), lfiId).apply();
    }

    // -------------------------------------------------------------------------
    // Portal operator credentials
    //
    // Used for exactly one thing: minting this terminal's API key through the
    // portal's login + inbound-api-config/regenerate flow, on an on-prem
    // environment, with an account a CB operator typed in here. There is no default
    // and there must never be one — see the hardcoded cloud constants in
    // ApiKeyManager and the guard that keeps them away from these environments.
    // -------------------------------------------------------------------------

    public static String getPortalUsername(String env) {
        return prefs().getString(envKey(env, SUFFIX_PORTAL_USERNAME), "");
    }

    public static String getPortalPassword(String env) {
        return SecurePrefs.getString(prefs(), envKey(env, SUFFIX_PORTAL_PASSWORD), "");
    }

    public static void setPortalCredentials(String env, String username, String password) {
        prefs().edit().putString(envKey(env, SUFFIX_PORTAL_USERNAME), username).apply();
        if (password == null || password.isEmpty()) {
            SecurePrefs.remove(prefs(), envKey(env, SUFFIX_PORTAL_PASSWORD));
        } else {
            SecurePrefs.putString(prefs(), envKey(env, SUFFIX_PORTAL_PASSWORD), password);
        }
    }

    /** True when both halves of a usable portal login are stored for this env. */
    public static boolean hasPortalCredentials(String env) {
        return !getPortalUsername(env).isEmpty() && !getPortalPassword(env).isEmpty();
    }

    public static void clearPortalCredentials(String env) {
        prefs().edit().remove(envKey(env, SUFFIX_PORTAL_USERNAME)).apply();
        SecurePrefs.remove(prefs(), envKey(env, SUFFIX_PORTAL_PASSWORD));
    }
}
