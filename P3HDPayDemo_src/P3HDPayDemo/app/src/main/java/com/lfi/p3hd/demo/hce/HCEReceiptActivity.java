package com.lfi.p3hd.demo.hce;

import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HCEReceiptActivity extends BaseAppCompatActivity {
    private EditText edtUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hce_receipt);
        initView();
    }

    private void initView() {
        setupHeader(R.string.hce_receipt_url_label, R.string.hce_receipt_title, true);
        edtUrl = findViewById(R.id.edt_url);
        edtUrl.setText("ddwallet://nfc?walletId=ADCB148E6BDC2C&merchantName=SHANS+SHOP&walletType=MICRO_MERCHANT");
        findViewById(R.id.btn_enable_nfc).setOnClickListener(v -> enableNfcTap());
        findViewById(R.id.btn_close_hce).setOnClickListener(v -> closeHce(true));
    }

    private void enableNfcTap() {
        if (!MyApplication.app.isConnectPaySDK()) {
            MyApplication.app.bindPaySDKService();
            showToast(R.string.sdk_not_connected);
            return;
        }
        String urlStr = edtUrl.getText().toString().trim();
        if (TextUtils.isEmpty(urlStr)) {
            showToast("Receipt URL shouldn't be empty");
            return;
        }
        if (!isHttpUrl(urlStr) && !urlStr.startsWith("ddwallet://")) {
            showToast("Invalid URL");
            return;
        }
        try {
            int code = MyApplication.app.hceManagerV2.hceOpen(AidlConstants.CardType.NFC.getValue(), null);
            Log.d(TAG, "hceOpen: " + code);
            Uri uri = Uri.parse(urlStr);
            NdefRecord record = NdefRecord.createUri(uri);
            code = MyApplication.app.hceManagerV2.hceNdefWrite(new NdefMessage(record));
            showToast("NFC ready — tap phone to device " + (code == 0 ? "" : "(" + code + ")"));
        } catch (Exception e) {
            Log.e(TAG, "enableNfcTap failed", e);
        }
    }

    private void closeHce(boolean showToast) {
        try {
            if (MyApplication.app.hceManagerV2 != null) {
                int code = MyApplication.app.hceManagerV2.hceClose();
                if (showToast) showToast("HCE closed " + (code == 0 ? "successfully" : "(" + code + ")"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isHttpUrl(String url) {
        String regex = "(((https|http)?://)?([a-z0-9]+[.])|(www.))"
            + "\\w+[.|\\/]([a-z0-9]{0,})?[[.]([a-z0-9]{0,})]+((/[\\S&&[^,;\u4E00-\u9FA5]]+)+)?([.][a-z0-9]{0,}+|/?)";
        Matcher mat = Pattern.compile(regex.trim()).matcher(url.trim());
        return mat.matches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeHce(false);
    }
}
