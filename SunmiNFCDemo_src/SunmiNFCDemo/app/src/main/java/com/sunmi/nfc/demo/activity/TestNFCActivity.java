package com.sunmi.nfc.demo.activity;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sunmi.nfc.demo.R;
import com.sunmi.nfc.demo.util.ByteUtil;
import com.sunmi.nfc.demo.util.IOUtil;

public class TestNFCActivity extends BaseAppCompatActivity {
    private static final String TAG = "TestNFCActivity";
    private static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_NFC_B
            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
    private EditText edtApdu;
    private TextView tvResult;
    private NfcAdapter nfcAdapter;
    private Tag tag = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(TAG, "onCreate...");
        setContentView(R.layout.activity_test_nfc);
        initNFC();
        initView();
    }

    private void initNFC() {
        Log.e(TAG, "init...");
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Log.e(TAG, "NFC not supported.");
        } else if (!nfcAdapter.isEnabled()) {
            Log.e(TAG, "NFC not enabled.");
            Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
            startActivity(intent);
        }
    }

    private void initView() {
        edtApdu = findViewById(R.id.edt_apdu);
        findViewById(R.id.mb_ok).setOnClickListener((v) -> transceiveApdu());
        tvResult = findViewById(R.id.tv_result);
        edtApdu.setText("00A404000E315041592E5359532E444446303100");
        showLoadingDialog("Please tap NFC card");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume...");
        //开启前台调度系统
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
            nfcAdapter.enableReaderMode(this, nfcReadCallback, READER_FLAGS, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.e(TAG, "onPause...");
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.e(TAG, "onStop...");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e(TAG, "onDestroy...");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.e(TAG, "onNewIntent...");
    }

    private final NfcAdapter.ReaderCallback nfcReadCallback = tag -> {
        Log.e(TAG, "find nfc tag:" + tag);
        this.tag = tag;
        dismissLoadingDialog();
    };

    private void transceiveApdu() {
        IsoDep isoDep = null;
        try {
            String apduStr = edtApdu.getText().toString();
            if (TextUtils.isEmpty(apduStr)) {
                Log.e(TAG, "APDU shouldn't be empty");
                showToast("APDU shouldn't be empty");
                return;
            }
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return;
            }
            isoDep = IsoDep.get(tag);
            if (isoDep == null) {
                Log.e(TAG, "get IsoDep technology failed");
                showToast("get IsoDep technology failed");
                return;
            }
            if (!isoDep.isConnected()) {
                isoDep.connect();
            }
            byte[] send = ByteUtil.hexString2Bytes(apduStr);
            byte[] recv = isoDep.transceive(send);
            addText(tvResult, "send: " + apduStr);
            addText(tvResult, "recv: " + ByteUtil.bytes2HexString(recv));
            Log.e(TAG, "send: " + apduStr);
            Log.e(TAG, "recv: " + ByteUtil.bytes2HexString(recv));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(isoDep);
        }
    }


}
