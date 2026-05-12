package com.lfi.p3hd.demo.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.qr.QRConfig;

public class PreferencesUtil {
    private static final String PREF_NAME = "p3hd_pref";
    private static final String KEY_ENV = "auth:env";
    private static final String KEY_WALLET_ID = "wallet_id";
    private static final String KEY_LFI_API_KEY = "lfi_api_key";
    private static final String KEY_LFI_API_KEY_EXPIRY = "lfi_api_key_expiry";
    private static final String KEY_NFC_WALLET_ID    = "nfc_wallet_id";
    private static final String KEY_NFC_WALLET_TYPE  = "nfc_wallet_type";
    private static final String KEY_NFC_MERCHANT_NAME = "nfc_merchant_name";

    private static SharedPreferences prefs() {
        return MyApplication.app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String getEnv() {
        return prefs().getString(KEY_ENV, "demo");
    }

    public static void setEnv(String env) {
        prefs().edit().putString(KEY_ENV, env).apply();
    }

    public static String getWalletId() {
        return prefs().getString(KEY_WALLET_ID, "");
    }

    public static void setWalletId(String walletId) {
        prefs().edit().putString(KEY_WALLET_ID, walletId).apply();
    }

    public static String getLfiApiKey() {
        return prefs().getString(KEY_LFI_API_KEY, "");
    }

    public static void setLfiApiKey(String apiKey) {
        prefs().edit().putString(KEY_LFI_API_KEY, apiKey).apply();
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
}
