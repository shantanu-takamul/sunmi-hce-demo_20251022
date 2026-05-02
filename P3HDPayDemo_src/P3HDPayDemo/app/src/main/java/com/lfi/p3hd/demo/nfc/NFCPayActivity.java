package com.lfi.p3hd.demo.nfc;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.sunmi.pay.hardware.aidl.AidlConstants;

public class NFCPayActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED = "amountAed";

    // ddwallet:// URL scheme fields the iOS app reads
    private static final String DDWALLET_SCHEME     = "ddwallet";
    private static final String DDWALLET_HOST       = "nfc";
    private static final String PARAM_WALLET_ID     = "walletId";
    private static final String PARAM_MERCHANT_NAME = "merchantName";
    private static final String PARAM_WALLET_TYPE   = "walletType";
    private static final String PARAM_AMOUNT        = "amount";
    private static final String MERCHANT_NAME       = "CBDC Merchant";
    private static final String WALLET_TYPE         = "MICRO_MERCHANT";
    // Staging wallet — must exist in the backend the RN app validates against.
    // Critical Fact #17: HCEReceiptActivity uses this same wallet for the same reason.
    private static final String NFC_WALLET_ID       = "ADCB148E6BDC2C";

    private String amountAed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_pay);

        amountAed = getIntent().getStringExtra(EXTRA_AMOUNT_AED);

        initView();
        openHce();
    }

    private void initView() {
        initActionbar(R.string.nfc_pay_title);
        TextView tvAmount = findViewById(R.id.tv_amount);
        tvAmount.setText("AED " + amountAed);

        // Timer view is part of the shared layout; hide it — no timeout in this flow.
        TextView tvTimer = findViewById(R.id.tv_timer);
        tvTimer.setVisibility(android.view.View.GONE);

        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            closeHce();
            finish();
        });
    }

    /**
     * Writes a ddwallet://nfc URI to the HCE tag.
     *
     * URL format expected by the iOS ddwallet app:
     *   ddwallet://nfc?walletId=XXXX&merchantName=Coffee+Shop&walletType=MICRO_MERCHANT&amount=10.00
     *
     * - walletId      MANDATORY — merchant's wallet ID (from SharedPreferences or default)
     * - merchantName  OPTIONAL  — displayed on Transfer/Review screens
     * - walletType    OPTIONAL  — MICRO_MERCHANT (default) | MERCHANT
     * - amount        OPTIONAL  — pre-populated amount in AED (major units, decimal)
     *
     * NdefRecord.createUri() encodes custom schemes (ddwallet://) with NDEF URI
     * prefix code 0x00 (no compression), so the full URI is stored verbatim.
     * The iOS useNFCReader hook correctly handles prefix 0x00 by reading the full
     * payload bytes as the URI string.
     */
    private void openHce() {
        if (!MyApplication.app.isConnectPaySDK()) {
            showToast(R.string.sdk_not_connected);
            return;
        }
        try {
            Uri uri = new Uri.Builder()
                    .scheme(DDWALLET_SCHEME)
                    .authority(DDWALLET_HOST)
                    .appendQueryParameter(PARAM_WALLET_ID, NFC_WALLET_ID)
                    .appendQueryParameter(PARAM_MERCHANT_NAME, MERCHANT_NAME)
                    .appendQueryParameter(PARAM_WALLET_TYPE, WALLET_TYPE)
                    .appendQueryParameter(PARAM_AMOUNT, amountAed)
                    .build();

            int cardType = AidlConstants.CardType.NFC.getValue();
            MyApplication.app.hceManagerV2.hceOpen(cardType, null);
            NdefRecord record = NdefRecord.createUri(uri);
            MyApplication.app.hceManagerV2.hceNdefWrite(new NdefMessage(record));
            Log.d(TAG, "HCE opened with URL: " + uri);
        } catch (Exception e) {
            Log.e(TAG, "openHce failed", e);
        }
    }

    private void closeHce() {
        try {
            if (MyApplication.app.hceManagerV2 != null) {
                MyApplication.app.hceManagerV2.hceClose();
            }
        } catch (Exception e) {
            Log.e(TAG, "closeHce failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeHce();
    }
}
