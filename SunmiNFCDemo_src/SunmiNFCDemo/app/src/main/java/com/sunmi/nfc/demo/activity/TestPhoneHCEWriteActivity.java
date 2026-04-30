package com.sunmi.nfc.demo.activity;

import android.content.Intent;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sunmi.nfc.demo.R;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class TestPhoneHCEWriteActivity extends BaseAppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B;
    private static final String NFC2_DATA_1 = "SUNMI Technology";
    private static final String NFC2_DATA_2 = "Devices run SUNMIUI system";
    private static final String NFC2_DATA_3 = "上海商米科技有限公司";
    private static final String NFC4_DATA_1 = "SUNMI Technology\n\n" +
            "SUNMI, with its core value \"altruism\", is an IoT company that globally leads the innovation of intelligent hardware for business.We are dedicated to provide intelligent IoT devices and integrated solutions combining software and hardware to empower business owners andbuild an interconnected business world to finally achieve business 4.0.";
    private static final String NFC4_DATA_2 = "SUNMI OS\n\n" +
            "Sunmi devices run SUNMIUI system, which is an operating system deeply optimized & improved based on Android to support installing common App based on Android system.It is deeply customized especially for intelligent commercial scenarios, acting in cooperation with each other inside &outside in terms of operating experience, performance enhancements and product appearance design and bringing out the best in each other.It enables the user to enjoy more professional & systematic software service experience while using the hardware.\n\n" +
            "App market\n\n" +
            "Sunmi has an internal App market and the partner can distribute his/her own App in large scale to Sunmi devices via App market. Partial partners can have an App market managed by himself/herself. Their default own device users can only install App for the device via App market.\n\n" +
            "SUNMI partners\n\n" +
            "Sunmi will set a relevant role for the registrant when “Register Partner” is reviewed. The partner can contact Sunmi customer service with 400-6666-509.";
    private static final String NFC4_DATA_3 = "商米科技\n\n" +
            "上海商米科技有限公司专注于为客户提供智能商用设备及相应配套的“端、云”一体化服务，致力于在全球范围内推动智能设备与商业领域的深度融合，构建万物互联的商业世界，最终实现商业4.0。\n\n" +
            "BIoT\n\n" +
            "BIoT是连接B端商户经营者和最终消费者，提供智能化的产品和服务，旨在提升顾客消费体验和经营管理决策效率、实现供需精准匹配的智能服务系统，是连接消费需求侧和供给侧的桥梁和纽带。";
    private NfcAdapter nfcAdapter;
    private RadioGroup rdgNfcType;
    private RadioGroup rdgNfcData;
    private TextView tvInfo;
    private EditText edtNdefData;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_write_hce);
        initView();
        initNFC();
    }

    private void initView() {
        rdgNfcType = findViewById(R.id.rdg_nfc_type);
        rdgNfcType.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
        rdgNfcData = findViewById(R.id.rdg_nfc_data);
        rdgNfcData.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
        edtNdefData = findViewById(R.id.edt_ndef_text);
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(v -> onClickOK());
        tvInfo = findViewById(R.id.tv_info);
        rdgNfcType.check(R.id.rdo_type4);
        rdgNfcData.check(R.id.rdo_data_1);
    }

    private String getNfcData() {
        int typeId = rdgNfcType.getCheckedRadioButtonId();
        int dataId = rdgNfcData.getCheckedRadioButtonId();
        if (typeId == R.id.rdo_type2) {
            if (dataId == R.id.rdo_data_1) {
                return NFC2_DATA_1;
            } else if (dataId == R.id.rdo_data_2) {
                return NFC2_DATA_2;
            } else if (dataId == R.id.rdo_data_3) {
                return NFC2_DATA_3;
            }
        } else if (typeId == R.id.rdo_type4) {
            if (dataId == R.id.rdo_data_1) {
                return NFC4_DATA_1;
            } else if (dataId == R.id.rdo_data_2) {
                return NFC4_DATA_2;
            } else if (dataId == R.id.rdo_data_3) {
                return NFC4_DATA_3;
            }
        }
        return null;
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

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume...");
        //开启前台调度系统
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
            nfcAdapter.enableReaderMode(this, this, READER_FLAGS, null);
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
    public void onTagDiscovered(Tag tag) {
        try {
            dismissLoadingDialog();
            writeNFCToTag(edtNdefData.getText().toString(), tag);
        } catch (Exception e) {
            e.printStackTrace();
            dismissLoadingDialog();
            showInfo("write data exception:" + e);
        }
    }

    private void showInfo(String msg) {
        runOnUiThread(() -> {
            tvInfo.setText(msg);
        });
    }

    private void onClickOK() {
        if (TextUtils.isEmpty(edtNdefData.getText())) {
            showInfo("The input date should not be empty");
            edtNdefData.requestFocus();
            return;
        }
        showLoadingDialog("Please tap to HCE device");
    }

    /** Write data in NdefMessage format */
    private void writeNFCToTag(String text, Tag tag) throws IOException, FormatException {
        byte[] langBytes = Locale.CHINA.getLanguage().getBytes(StandardCharsets.US_ASCII);
        Charset utfEncoding = StandardCharsets.UTF_8;
        byte[] textBytes = text.getBytes(utfEncoding);
        int utfBit = 0;
        char status = (char) (utfBit + langBytes.length);
        byte[] data = new byte[1 + langBytes.length + textBytes.length];
        data[0] = (byte) status;
        System.arraycopy(langBytes, 0, data, 1, langBytes.length);
        System.arraycopy(textBytes, 0, data, 1 + langBytes.length, textBytes.length);
        NdefRecord record = new NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, new byte[0], data);
        NdefMessage ndefMessage = new NdefMessage(new NdefRecord[]{record});
        int size = ndefMessage.toByteArray().length;
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            ndef.connect();
            if (!ndef.isWritable()) {
                showInfo("ndef is not Writable");
                return;
            }
            Log.e(TAG, "ndef maxSize:" + ndef.getMaxSize());
            if (ndef.getMaxSize() < size) {
                showInfo("Input text too long to write");
                Log.e(TAG, "Input text too long to write");
                return;
            }
            ndef.writeNdefMessage(ndefMessage);
            showInfo("write success");
            Log.e(TAG, "write NDEF success");
        } else {
            //当我们买回来的NFC标签是没有格式化的，或者没有分区的执行此步
            //Ndef格式类
            NdefFormatable format = NdefFormatable.get(tag);
            //判断是否获得了NdefFormatable对象，有一些标签是只读的或者不允许格式化的
            if (format != null) {
                //连接
                format.connect();
                //格式化并将信息写入标签
                format.format(ndefMessage);
                Log.e(TAG, "格式化并写入NDEF成功");
            } else {
                Log.e(TAG, "格式化并写入NDEF失败");
            }
        }
    }

}
