package com.lfi.p3hd.demo.hce;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HCEActivity extends BaseAppCompatActivity {
    private static final String NFC2_DATA_1 = "Explore the Android development";
    private static final String NFC2_DATA_2 = "Learn about the latest release and revi";
    private static final String NFC2_DATA_3 = "NFC read/write card emulation mode demo";
    private static final String NFC2_DATA_4 = "https://www.sunmi.com";
    private static final String NFC4_DATA_1 = "Explore the Android development landscape. Learn about the latest release and review details of earlier releases. Discover the device ecosystem, modern Android development, and training courses.";
    private static final String NFC4_DATA_2 = "Great! You changed the text, but it introduces you as Android, which is probably not your name. Next, you will personalize it to introduce you with your name!";
    private static final String NFC4_DATA_3 = "NFC communication modes: Reader/writer mode, Card Emulation Mode, P2P mode. In reader mode, data is stored in the NFC chip.";
    private static final String NFC4_DATA_4 = NFC2_DATA_4;

    private RadioGroup rdgNfcType;
    private RadioGroup rdgNfcData;
    private EditText edtNdefData;
    private TextView tvInfo;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hce);
        initView();
    }

    private void initView() {
        initActionbar(R.string.hce_title);
        edtNdefData = findViewById(R.id.edt_ndef_text);
        tvInfo = findViewById(R.id.tv_info);
        rdgNfcType = findViewById(R.id.rdg_nfc_type);
        rdgNfcData = findViewById(R.id.rdg_nfc_data);

        rdgNfcType.setOnCheckedChangeListener((g, id) -> edtNdefData.setText(getNfcData()));
        rdgNfcData.setOnCheckedChangeListener((g, id) -> edtNdefData.setText(getNfcData()));

        rdgNfcType.check(R.id.rdo_type4);
        rdgNfcData.check(R.id.rdo_data_1);

        findViewById(R.id.btn_open_hce).setOnClickListener(this);
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_read_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_close_hce).setOnClickListener(this);
    }

    private String getNfcData() {
        int typeId = rdgNfcType.getCheckedRadioButtonId();
        int dataId = rdgNfcData.getCheckedRadioButtonId();
        if (typeId == R.id.rdo_type2) {
            if (dataId == R.id.rdo_data_1) return NFC2_DATA_1;
            if (dataId == R.id.rdo_data_2) return NFC2_DATA_2;
            if (dataId == R.id.rdo_data_3) return NFC2_DATA_3;
            if (dataId == R.id.rdo_data_4) return NFC2_DATA_4;
        } else {
            if (dataId == R.id.rdo_data_1) return NFC4_DATA_1;
            if (dataId == R.id.rdo_data_2) return NFC4_DATA_2;
            if (dataId == R.id.rdo_data_3) return NFC4_DATA_3;
            if (dataId == R.id.rdo_data_4) return NFC4_DATA_4;
        }
        return null;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View v) {
        if (!MyApplication.app.isConnectPaySDK()) {
            MyApplication.app.bindPaySDKService();
            showToast(R.string.sdk_not_connected);
            return;
        }
        int id = v.getId();
        if (id == R.id.btn_open_hce) openHce();
        else if (id == R.id.btn_write_ndef_data) writeNdefData();
        else if (id == R.id.btn_read_ndef_data) readNdefData();
        else if (id == R.id.btn_close_hce) closeHce(true);
    }

    private void openHce() {
        try {
            int nfcType = AidlConstants.CardType.NFC.getValue();
            if (rdgNfcType.getCheckedRadioButtonId() == R.id.rdo_type2) {
                nfcType = AidlConstants.CardType.IC.getValue();
            }
            int code = MyApplication.app.hceManagerV2.hceOpen(nfcType, null);
            showToast("open hce " + (code == 0 ? "success" : "failed(" + code + ")"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeNdefData() {
        try {
            String dataStr = edtNdefData.getText().toString();
            if (TextUtils.isEmpty(dataStr)) {
                showToast("NDEF data shouldn't be empty");
                return;
            }
            NdefRecord record;
            if (isHttpUrl(dataStr)) {
                record = NdefRecord.createUri(Uri.parse(dataStr));
            } else {
                record = NdefRecord.createTextRecord(Locale.ENGLISH.getLanguage(), dataStr);
            }
            int code = MyApplication.app.hceManagerV2.hceNdefWrite(new NdefMessage(record));
            showToast("hceNdefWrite " + (code == 0 ? "success" : "failed(" + code + ")"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void readNdefData() {
        try {
            NdefMessage ndefMsg = MyApplication.app.hceManagerV2.hceNdefRead();
            if (ndefMsg == null) {
                showToast("hce read NdefMessage failed");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (NdefRecord record : ndefMsg.getRecords()) {
                String text = parseTextRecord(record);
                if (!TextUtils.isEmpty(text)) sb.append(text);
            }
            tvInfo.setText(sb);
            showToast("hceNdefRead success");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String parseTextRecord(NdefRecord record) {
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) return null;
        if (!Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) return null;
        try {
            byte[] payload = record.getPayload();
            String textEncoding = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
            int langLen = payload[0] & 0x3f;
            return new String(payload, langLen + 1, payload.length - langLen - 1, textEncoding);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void closeHce(boolean showToast) {
        try {
            if (MyApplication.app.hceManagerV2 != null) {
                int code = MyApplication.app.hceManagerV2.hceClose();
                if (showToast) showToast("close hce " + (code == 0 ? "success" : "failed(" + code + ")"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean isHttpUrl(String url) {
        String regex = "(((https|http)?://)?([a-z0-9]+[.])|(www.))"
            + "\\w+[.|\\/]([a-z0-9]{0,})?[[.]([a-z0-9]{0,})]+((/[\\S&&[^,;\u4E00-\u9FA5]]+)+)?([.][a-z0-9]{0,}+|/?)";
        Pattern pat = Pattern.compile(regex.trim());
        Matcher mat = pat.matcher(url.trim());
        return mat.matches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeHce(false);
    }
}
