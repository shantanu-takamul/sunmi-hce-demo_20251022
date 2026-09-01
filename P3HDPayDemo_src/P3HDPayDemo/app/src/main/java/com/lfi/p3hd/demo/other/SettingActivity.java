package com.lfi.p3hd.demo.other;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.net.NetDiagnostics;
import com.lfi.p3hd.demo.net.TrustStore;
import com.lfi.p3hd.demo.qr.QRConfig;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.lfi.p3hd.demo.utils.PreferencesUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import okhttp3.Request;
import okhttp3.Response;

public class SettingActivity extends BaseAppCompatActivity {

    /** Request code for the CA-override document picker. */
    private static final int REQUEST_IMPORT_CA = 4101;

    private TextView tvEnvValue;
    private TextView tvApiKeyValue;
    private TextView tvWalletIdValue;
    private TextView tvNfcWalletIdValue;
    private TextView tvNfcWalletTypeValue;
    private TextView tvNfcMerchantNameValue;

    private LinearLayout layoutOnPrem;
    private TextView tvBaseUrlValue;
    private TextView tvLfiIdValue;
    private TextView tvPortalCredsValue;
    private TextView tvCaValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setting);
        initView();
    }

    private void initView() {
        setupHeader(R.string.setting_header_label, R.string.setting_header_title, true);
        tvEnvValue            = findViewById(R.id.tv_env_value);
        tvApiKeyValue         = findViewById(R.id.tv_api_key_value);
        tvWalletIdValue       = findViewById(R.id.tv_wallet_id_value);
        tvNfcWalletIdValue     = findViewById(R.id.tv_nfc_wallet_id_value);
        tvNfcWalletTypeValue   = findViewById(R.id.tv_nfc_wallet_type_value);
        tvNfcMerchantNameValue = findViewById(R.id.tv_nfc_merchant_name_value);

        layoutOnPrem       = findViewById(R.id.layout_onprem);
        tvBaseUrlValue     = findViewById(R.id.tv_base_url_value);
        tvLfiIdValue       = findViewById(R.id.tv_lfi_id_value);
        tvPortalCredsValue = findViewById(R.id.tv_portal_creds_value);
        tvCaValue          = findViewById(R.id.tv_ca_value);

        updateDisplay();

        findViewById(R.id.btn_change_env).setOnClickListener(v -> showEnvPicker());
        findViewById(R.id.btn_refresh_key).setOnClickListener(v -> showApiKeyOptions());
        findViewById(R.id.btn_edit_nfc_wallet).setOnClickListener(v -> showNfcWalletIdInput());
        findViewById(R.id.btn_edit_nfc_wallet_type).setOnClickListener(v -> showNfcWalletTypePicker());
        findViewById(R.id.btn_edit_nfc_merchant).setOnClickListener(v -> showNfcMerchantNameInput());

        findViewById(R.id.btn_edit_base_url).setOnClickListener(v -> showBaseUrlInput());
        findViewById(R.id.btn_edit_lfi_id).setOnClickListener(v -> showLfiIdInput());
        findViewById(R.id.btn_edit_portal_creds).setOnClickListener(v -> showPortalCredsInput());
        findViewById(R.id.btn_edit_ca).setOnClickListener(v -> showCaOptions());
        findViewById(R.id.btn_test_connection).setOnClickListener(v -> runConnectionTest());
        findViewById(R.id.btn_verify_key).setOnClickListener(v -> runKeyVerification());
    }

    private void updateDisplay() {
        String env = PreferencesUtil.getEnv();
        tvEnvValue.setText(env);
        String apiKey = PreferencesUtil.getLfiApiKey();
        tvApiKeyValue.setText(apiKey.isEmpty() ? "Not set" : maskApiKey(apiKey));
        String walletId = PreferencesUtil.getWalletId();
        tvWalletIdValue.setText(walletId.isEmpty() ? QRConfig.getDefaultWalletId() + " (default)" : walletId);
        String nfcWalletId = PreferencesUtil.getNfcWalletId();
        tvNfcWalletIdValue.setText(nfcWalletId.isEmpty() ? QRConfig.getDefaultWalletId() + " (default)" : nfcWalletId);
        String nfcWalletType = PreferencesUtil.getNfcWalletType();
        tvNfcWalletTypeValue.setText(nfcWalletType);
        String nfcMerchantName = PreferencesUtil.getNfcMerchantName();
        tvNfcMerchantNameValue.setText(nfcMerchantName.isEmpty() ? "CBDC Merchant (default)" : nfcMerchantName);

        updateOnPremDisplay(env);
    }

    /** The on-prem block is the only part of this screen that is conditional. */
    private void updateOnPremDisplay(String env) {
        boolean onPrem = QRConfig.isOnPrem(env);
        layoutOnPrem.setVisibility(onPrem ? View.VISIBLE : View.GONE);
        if (!onPrem) return;

        String baseUrl = QRConfig.getBaseUrl(env);
        tvBaseUrlValue.setText(PreferencesUtil.hasOnPremBaseUrlOverride(env)
            ? baseUrl : baseUrl + "  (default)");
        tvLfiIdValue.setText(QRConfig.getXLfiId(env));

        tvPortalCredsValue.setText(PreferencesUtil.hasPortalCredentials(env)
            ? PreferencesUtil.getPortalUsername(env) + " · password saved"
            : getString(R.string.setting_portal_creds_none));

        tvCaValue.setText(TrustStore.describeActiveAnchor(env));
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void showEnvPicker() {
        String[] envs = {"staging", "dev", "qa", "demo", "local", "bootstrap", "sit"};
        String currentEnv = PreferencesUtil.getEnv();
        int currentIdx = 0;
        for (int i = 0; i < envs.length; i++) {
            if (envs[i].equals(currentEnv)) { currentIdx = i; break; }
        }
        final int[] selected = {currentIdx};

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_env_title)
            .setSingleChoiceItems(envs, currentIdx, (d, which) -> selected[0] = which)
            .setPositiveButton(android.R.string.ok, (d, w) -> showWalletIdInput(envs[selected[0]]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showWalletIdInput(String selectedEnv) {
        EditText input = new EditText(this);
        String envDefault = QRConfig.getDefaultWalletId(selectedEnv);
        input.setHint(envDefault);
        input.setText(envDefault);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_wallet_id_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String walletId = input.getText().toString().trim();
                PreferencesUtil.setEnv(selectedEnv);
                PreferencesUtil.setWalletId(walletId.isEmpty() ? envDefault : walletId);
                updateDisplay();

                // On the cloud environments the portal path uses credentials compiled
                // into this APK, so firing it here is a convenience.
                //
                // On an on-premise environment the same line would be a credential
                // leak: it would POST test-global-admin/12345 straight at a Central
                // Bank server, because the operator changed a dropdown. Those
                // constants must never leave the device on these environments. Offer
                // the key dialog instead — which is also the flow that works there,
                // since the key is minted by a portal operator and handed over.
                if (QRConfig.isOnPrem(selectedEnv)) {
                    showApiKeyInput();
                } else {
                    fetchApiKey();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNfcWalletIdInput() {
        EditText input = new EditText(this);
        String current = PreferencesUtil.getNfcWalletId();
        String envDefault = QRConfig.getDefaultWalletId();
        input.setHint(envDefault);
        input.setText(current.isEmpty() ? envDefault : current);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_nfc_wallet_id_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String value = input.getText().toString().trim();
                PreferencesUtil.setNfcWalletId(value.isEmpty() ? envDefault : value);
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNfcWalletTypePicker() {
        String[] types = {"MERCHANT_COLLECTION", "MICRO_MERCHANT"};
        String current = PreferencesUtil.getNfcWalletType();
        int currentIdx = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i].equals(current)) { currentIdx = i; break; }
        }
        final int[] selected = {currentIdx};

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_nfc_wallet_type_title)
            .setSingleChoiceItems(types, currentIdx, (d, which) -> selected[0] = which)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                PreferencesUtil.setNfcWalletType(types[selected[0]]);
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showNfcMerchantNameInput() {
        EditText input = new EditText(this);
        String current = PreferencesUtil.getNfcMerchantName();
        input.setHint("CBDC Merchant");
        input.setText(current.isEmpty() ? "CBDC Merchant" : current);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_nfc_merchant_name_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String value = input.getText().toString().trim();
                PreferencesUtil.setNfcMerchantName(value.isEmpty() ? "CBDC Merchant" : value);
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // -------------------------------------------------------------------------
    // API key
    // -------------------------------------------------------------------------

    /**
     * The portal path (login + inbound-api-config/regenerate) needs an operator
     * account with LFI-admin rights. Where the POS account lacks them the call
     * returns 403, so allow pasting a key issued from the Business Portal.
     */
    private void showApiKeyOptions() {
        String[] options = {
            getString(R.string.setting_api_key_fetch),
            getString(R.string.setting_api_key_manual)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_api_key_title)
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    fetchApiKey();
                } else {
                    showApiKeyInput();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showApiKeyInput() {
        EditText input = new EditText(this);
        input.setHint("cbdc_...");
        // Deliberately NOT prefilled with the stored key. Prefilling puts a live
        // credential on screen in a room where that screen is being shared over VDI,
        // and it makes "cancel" and "save the same key back" indistinguishable. The
        // masked value is on the row behind this dialog; replacing it is a retype.
        String existing = PreferencesUtil.getLfiApiKey();

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_api_key_manual)
            .setMessage(existing.isEmpty()
                ? "Paste the API key issued for this terminal."
                : "Current key: " + maskApiKey(existing)
                    + "\n\nEnter a new key to replace it, or leave blank to keep it.")
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String key = input.getText().toString().trim();
                if (key.isEmpty()) {
                    // Blank means "keep what is there", not "clear it". Clearing has
                    // its own button so it can never happen by accident — on-prem
                    // that key is unrecoverable and reissuing it needs a CB operator.
                    showToast("API key unchanged");
                    return;
                }
                PreferencesUtil.setLfiApiKey(key);
                PreferencesUtil.setLfiApiKeyExpiry("");
                PreferencesUtil.setLfiApiKeyManual(true);
                updateDisplay();
                showToast("API key saved");
            })
            .setNeutralButton("Clear", (d, w) -> {
                PreferencesUtil.setLfiApiKey("");
                PreferencesUtil.setLfiApiKeyExpiry("");
                PreferencesUtil.setLfiApiKeyManual(false);
                updateDisplay();
                showToast("API key cleared");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void fetchApiKey() {
        showToast("Fetching API key...");
        // Explicit operator intent to use the portal path — drop any manual flag so
        // ApiKeyManager does not refuse in order to protect the stored key.
        //
        // Restored on failure. Clearing it up front and leaving it cleared when the
        // mint failed left a stored key the app no longer believed was
        // operator-chosen, so the next automatic 401 recovery would mint over it —
        // on exactly the environments where minting is forbidden and that key was the
        // only working credential.
        boolean wasManual = PreferencesUtil.isLfiApiKeyManual();
        PreferencesUtil.setLfiApiKeyManual(false);
        // operatorInitiated: someone is standing here and chose this. The automatic
        // 401-recovery path is refused on-prem precisely because nobody is.
        ApiKeyManager.get().fetch(
            true,
            () -> {
                if (isFinishing()) return;
                updateDisplay();
                showToast("API key ready (" + PreferencesUtil.getEnv() + ")");
            },
            errorMsg -> {
                PreferencesUtil.setLfiApiKeyManual(wasManual);
                if (isFinishing()) return;
                updateDisplay();
                showToast(errorMsg);
            }
        );
    }

    // -------------------------------------------------------------------------
    // On-premise configuration
    // -------------------------------------------------------------------------

    private void showBaseUrlInput() {
        String env = PreferencesUtil.getEnv();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(QRConfig.defaultOnPremBaseUrl(env));
        input.setText(QRConfig.getBaseUrl(env));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(R.string.setting_base_url_title)
            .setMessage("Host only, https, no trailing slash.\n\nDefault: "
                + QRConfig.defaultOnPremBaseUrl(env))
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)   // replaced below, to validate
            .setNeutralButton("Reset", (d, w) -> {
                PreferencesUtil.setOnPremBaseUrl(env, "");
                updateDisplay();
                showToast("Base URL reset to the built-in default");
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();

        // The positive button is rebound after show() so a rejected URL leaves the
        // dialog open with the text still in it. Dismissing and toasting would make
        // the operator retype an entire host name to fix a trailing slash.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                String problem = QRConfig.validateBaseUrl(url);
                if (problem != null) {
                    input.setError(problem);
                    return;
                }
                PreferencesUtil.setOnPremBaseUrl(env, url);
                updateDisplay();
                showToast("Base URL saved");
                dialog.dismiss();
            }));
        dialog.show();
    }

    private void showLfiIdInput() {
        String env = PreferencesUtil.getEnv();
        EditText input = new EditText(this);
        input.setHint(QRConfig.ON_PREM_LFI_ID_DEFAULT);
        input.setText(QRConfig.getXLfiId(env));

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_lfi_id_title)
            .setMessage("The acquirer this terminal's API key belongs to.\n\nDefault: "
                + QRConfig.ON_PREM_LFI_ID_DEFAULT)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                PreferencesUtil.setOnPremLfiId(env, input.getText().toString().trim());
                updateDisplay();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showPortalCredsInput() {
        String env = PreferencesUtil.getEnv();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        EditText username = new EditText(this);
        username.setHint(R.string.setting_portal_username_hint);
        username.setText(PreferencesUtil.getPortalUsername(env));

        EditText password = new EditText(this);
        password.setHint(R.string.setting_portal_password_hint);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // Never prefilled, for the same reason the API key is not.

        box.addView(username);
        box.addView(password);

        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_portal_creds_title)
            .setMessage(getString(R.string.setting_portal_creds_hint)
                + "\n\nA CB-admin portal account for " + env
                + ".\nLeave the password blank to keep the stored one.")
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String user = username.getText().toString().trim();
                String pass = password.getText().toString();
                if (pass.isEmpty()) pass = PreferencesUtil.getPortalPassword(env);
                PreferencesUtil.setPortalCredentials(env, user, pass);
                updateDisplay();
                showToast("Portal login saved");
            })
            .setNeutralButton("Clear", (d, w) -> {
                PreferencesUtil.clearPortalCredentials(env);
                updateDisplay();
                showToast(getString(R.string.setting_portal_creds_cleared));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // -------------------------------------------------------------------------
    // CA trust
    // -------------------------------------------------------------------------

    private void showCaOptions() {
        String[] options = {
            getString(R.string.setting_ca_import),
            getString(R.string.setting_ca_use_bundled)
        };
        new AlertDialog.Builder(this)
            .setTitle(R.string.setting_ca_title)
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    pickCaFile();
                } else {
                    TrustStore.clearOverride();
                    updateDisplay();
                    showToast(getString(R.string.setting_ca_cleared));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Opens the system document picker for a PEM.
     *
     * The realistic route inside CB is a USB-OTG stick: there is no network path onto
     * a terminal in there, and a QR-encoded PEM does not fit in a QR code.
     */
    private void pickCaFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_CA);
        } catch (Exception e) {
            showToast("No file picker available on this device");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_CA || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("could not open the selected file");
            String described = TrustStore.importOverride(in);
            updateDisplay();
            // A dialog, not a toast: the operator's job at this moment is to compare
            // the fingerprint against a printed value before trusting the terminal.
            new AlertDialog.Builder(this)
                .setTitle(R.string.setting_ca_imported)
                .setMessage("Check this fingerprint against the printed one before"
                    + " using this terminal:\n\n" + described)
                .setPositiveButton(R.string.setting_close, null)
                .show();
        } catch (Exception e) {
            showToast("Could not import that file: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    //
    // There is no adb and no logcat inside CB. These two buttons and the screen they
    // print to are the whole diagnostic surface, so they answer the two questions
    // separately: can this terminal reach the gateway at all, and is its credential
    // accepted. Collapsing them into one would leave an operator unable to tell a
    // firewall from a bad key.
    // -------------------------------------------------------------------------

    /** Unauthenticated reachability probe: GET /actuator/health. */
    private void runConnectionTest() {
        String env = PreferencesUtil.getEnv();
        String baseUrl = QRConfig.getBaseUrl(env);
        showToast(getString(R.string.setting_test_connection_running));

        // Sampled on the UI thread: ConnectivityManager wants a Context, and the
        // answer is about the device, not about the request.
        final String transport = NetDiagnostics.activeTransport(this);

        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            out.append("URL: ").append(baseUrl).append("\n");
            out.append("Network: ").append(transport).append("\n\n");

            Request request = new Request.Builder()
                .url(baseUrl + "/actuator/health")
                .get()
                .build();

            long startedAt = System.currentTimeMillis();
            try (Response response = HttpClients.forEnv(env).newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                out.append("TLS: ok\n");
                out.append("HTTP ").append(response.code()).append("\n");

                Date serverDate = response.headers().getDate("Date");
                if (serverDate != null) {
                    long skew = NetDiagnostics.clockSkewSeconds(serverDate.getTime());
                    out.append("Clock: ").append(NetDiagnostics.describeSkew(skew)).append("\n");
                } else {
                    out.append("Clock: no Date header, skew unknown\n");
                }

                out.append("Round trip: ")
                   .append(System.currentTimeMillis() - startedAt).append(" ms\n\n");

                if (QRConfig.looksLikeHtmlPage(body)) {
                    out.append(QRConfig.errorTextOf(body, response));
                } else {
                    out.append("Body: ")
                       .append(body.length() > 200 ? body.substring(0, 200) + "…" : body);
                }
            } catch (IOException e) {
                out.append("FAILED\n").append(NetDiagnostics.classify(e));
            }

            String report = out.toString();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showReport(getString(R.string.setting_test_connection_title), report);
            });
        }).start();
    }

    /**
     * Authenticated probe: the smallest real gateway call there is.
     *
     * A health check proves the network. Only a call carrying X-LFI-ID and
     * X-LFI-API-KEY proves the credential, and an operator needs to know which of the
     * two is wrong before a queue forms at the till.
     */
    private void runKeyVerification() {
        String env = PreferencesUtil.getEnv();
        String walletId = PreferencesUtil.getWalletId();
        if (walletId.isEmpty()) walletId = QRConfig.getDefaultWalletId(env);
        final String apiKey = PreferencesUtil.getLfiApiKey();

        if (apiKey.isEmpty()) {
            showReport(getString(R.string.setting_verify_key_title),
                "No API key is stored.\n\nEnter one under API Key, or store portal"
                    + " credentials so the terminal can mint one.");
            return;
        }

        showToast(getString(R.string.setting_verify_key_running));
        final String wallet = walletId;

        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            out.append("LFI: ").append(QRConfig.getXLfiId(env)).append("\n");
            out.append("Key: ").append(maskApiKey(apiKey)).append("\n");
            out.append("Wallet: ").append(wallet).append("\n\n");

            Request request = new Request.Builder()
                .url(QRConfig.getBaseUrl(env) + QRConfig.QR_STATUS_ENDPOINT
                    + "?walletId=" + wallet + "&limit=1")
                .addHeader("X-LFI-ID", QRConfig.getXLfiId(env))
                .addHeader("X-LFI-API-KEY", apiKey)
                .get()
                .build();

            try (Response response = HttpClients.forEnv(env).newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                int code = response.code();
                if (code == 200) {
                    out.append("HTTP 200 — the key is accepted for this LFI and wallet.");
                } else if (code == 401) {
                    out.append("HTTP 401 — the key was rejected.\n\n")
                       .append("It is wrong, expired, in the other slot, or it was")
                       .append(" rotated on the platform. Re-enter it under API Key.");
                } else {
                    out.append(NetDiagnostics.classifyHttp(code)).append("\n\n")
                       .append(QRConfig.errorTextOf(body, response));
                }
            } catch (IOException e) {
                out.append("FAILED\n").append(NetDiagnostics.classify(e));
            }

            String report = out.toString();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showReport(getString(R.string.setting_verify_key_title), report);
            });
        }).start();
    }

    /** A dialog rather than a toast: this output is read, and often photographed. */
    private void showReport(String title, String body) {
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(R.string.setting_close, null)
            .show();
    }
}
