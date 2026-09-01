package com.lfi.p3hd.demo.utils;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import com.lfi.p3hd.demo.MyApplication;

/**
 * Keystore-backed storage for the two values on this terminal that are worth
 * stealing: the LFI API key and the portal operator's password.
 *
 * <h3>Why not just use the normal preferences file</h3>
 * {@code allowBackup} is now off, which stops {@code adb backup} carrying the prefs
 * XML away, but the file is still plaintext on disk. On a rooted or unlocked terminal
 * — and a POS device that lives in a shop is not physically secure — reading it is
 * trivial. On-prem the API key is worse than a password: it is unrecoverable
 * plaintext, minting a replacement needs a CB operator, and rotating the slot breaks
 * whatever else held it.
 *
 * <h3>Falling back rather than failing</h3>
 * Every access is guarded. The Android Keystore is not uniformly reliable across
 * vendor firmware, and Sunmi POS builds are exactly the kind of device where
 * {@code EncryptedSharedPreferences.create} can throw. A terminal that cannot take
 * payments is a worse outcome than one storing a key in plaintext, so an unavailable
 * keystore degrades to the plain preferences file and says so in the log.
 *
 * <h3>Reading through to plaintext, deliberately</h3>
 * A read falls back to the plain file and migrates what it finds. That is not only
 * for upgrading existing installs — {@code deploy-local.sh} and
 * {@code local-tls-rig.sh} seed {@code lfi_api_key} straight into the prefs XML over
 * adb, and they must keep working. A seeded key is picked up on first read and moved
 * into the encrypted store; the plaintext copy is deleted at that point, so it exists
 * for one read rather than forever.
 */
final class SecurePrefs {

    private static final String TAG = "SecurePrefs";
    private static final String SECURE_PREF_NAME = "p3hd_secure";

    private static SharedPreferences secure;
    private static boolean initialised;

    private SecurePrefs() {}

    private static synchronized SharedPreferences secure() {
        if (initialised) return secure;
        initialised = true;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            secure = EncryptedSharedPreferences.create(
                SECURE_PREF_NAME,
                masterKeyAlias,
                MyApplication.app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            Log.i(TAG, "secure-prefs: encrypted storage active");
        } catch (Throwable t) {
            // Throwable, not Exception: a missing or broken keystore provider can
            // surface as an Error, and taking the terminal down for it is not a trade
            // anyone would choose at a till.
            Log.e(TAG, "secure-prefs: unavailable, falling back to plain preferences", t);
            secure = null;
        }
        return secure;
    }

    /**
     * Reads a secret, migrating a plaintext value into the encrypted store if that is
     * where it still lives.
     */
    static String getString(SharedPreferences plain, String key, String defaultValue) {
        SharedPreferences encrypted = secure();
        if (encrypted != null) {
            String stored = encrypted.getString(key, "");
            if (!stored.isEmpty()) return stored;
        }

        String legacy = plain.getString(key, "");
        if (legacy.isEmpty()) return defaultValue;

        if (encrypted != null) {
            encrypted.edit().putString(key, legacy).apply();
            plain.edit().remove(key).apply();
            Log.i(TAG, "secure-prefs: migrated " + key + " out of plain preferences");
        }
        return legacy;
    }

    /** Writes a secret, and removes any plaintext copy of it. */
    static void putString(SharedPreferences plain, String key, String value) {
        SharedPreferences encrypted = secure();
        if (encrypted == null) {
            plain.edit().putString(key, value).apply();
            return;
        }
        encrypted.edit().putString(key, value).apply();
        plain.edit().remove(key).apply();
    }

    /** Removes a secret from both stores — the plaintext one may hold a seeded copy. */
    static void remove(SharedPreferences plain, String key) {
        SharedPreferences encrypted = secure();
        if (encrypted != null) encrypted.edit().remove(key).apply();
        plain.edit().remove(key).apply();
    }
}
