package com.sunmi.hce.demo.activity;

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

import com.sunmi.hce.demo.MyApplication;
import com.sunmi.hce.demo.R;
import com.sunmi.hce.demo.util.Utility;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HCEActivity extends BaseAppCompatActivity {
    private static final String NFC2_DATA_1 = "Explore the Android development";
    private static final String NFC2_DATA_2 = "Learn about the latest release and revi";
    private static final String NFC2_DATA_3 = "读卡器模式、仿真卡模式";
    private static final String NFC2_DATA_4 = "https://www.sunmi.com";
    private static final String NFC4_DATA_1 = "Explore the Android development landscape. Learn about the latest release and review details of earlier releases. Discover the device ecosystem, modern Android development, and training courses.\n" +
            "Welcome to the Android developer guides. These documents teach you how to build Android apps using APIs in the Android framework and other libraries.\n" +
            "If you're brand new to Android and want to jump into code, start with the Build your first Android app tutorial.\n" +
            "And check out these other resources to learn Android development:";
    private static final String NFC4_DATA_2 = "Explore the Android development landscape. Learn about the latest release and rev\n" +
            "Great! You changed the text, but it introduces you as Android, which is probably not your name. Next, you will personalize it to introduce you with your name!\n" +
            "The GreetingPreview() function is a cool feature that lets you see what your composable looks like without having to build your entire app. To enable a preview of a composable, annotated with @Composable and @Preview. The @Preview annotation tells Android Studio that this composable should be shown in the design view of this file.\n" +
            "As you can see, the @Preview annotation takes in a parameter called showBackground. If showBackground is set to true, it will add a background to your composable preview.\n" +
            "Since Android Studio by default uses a light theme for the editor, it can be hard to see the difference between showBackground = true and showBackground = false. However, this is an example of what the difference looks like.";
    private static final String NFC4_DATA_3 = "1.4 NFC通信模式\n\n" +
            "读卡器模式（Reader/writer mode）、仿真卡模式(Card Emulation Mode)、点对点模式（P2P mode）。\n\n" +
            "读卡器模式\n" +
            "数据在NFC芯片中，可以简单理解成“刷标签”。本质上就是通过支持NFC的手机或其它电子设备从带有NFC芯片的标签、贴纸、名片等媒介中读写信息。通常NFC标签是不需要外部供电的。当支持NFC的外设向NFC读写数据时，它会发送某种磁场，而这个磁场会自动的向NFC标签供电。";
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
        initActionbar("Test HCE");
        findViewById(R.id.btn_open_hce).setOnClickListener(this);
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_read_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_close_hce).setOnClickListener(this);
        edtNdefData = findViewById(R.id.edt_ndef_text);
        rdgNfcType = findViewById(R.id.rdg_nfc_type);
        rdgNfcType.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
        rdgNfcData = findViewById(R.id.rdg_nfc_data);
        rdgNfcData.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
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
            } else if (dataId == R.id.rdo_data_4) {
                return NFC2_DATA_4;
            }
        } else if (typeId == R.id.rdo_type4) {
            if (dataId == R.id.rdo_data_1) {
                return NFC4_DATA_1;
            } else if (dataId == R.id.rdo_data_2) {
                return NFC4_DATA_2;
            } else if (dataId == R.id.rdo_data_3) {
                return NFC4_DATA_3;
            } else if (dataId == R.id.rdo_data_4) {
                return NFC4_DATA_4;
            }
        }
        return null;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View v) {
        if (!MyApplication.app.isConnectPaySDK()) {
            MyApplication.app.bindPaySDKService();
            showToast("connecting to SDK");
            return;
        }
        switch (v.getId()) {
            case R.id.btn_open_hce:
                openHce();
                break;
            case R.id.btn_write_ndef_data:
                writeNdefData();
                break;
            case R.id.btn_read_ndef_data:
                readNdefData();
                break;
            case R.id.btn_close_hce:
                closeHce(true);
                break;
        }
    }

    /** open HCE */
    private void openHce() {
        try {
            int nfgType = AidlConstants.CardType.NFC.getValue();
            if (rdgNfcType.getCheckedRadioButtonId() == R.id.rdo_type2) {
                nfgType = AidlConstants.CardType.IC.getValue();
            }
            int code = MyApplication.app.hceManagerV2.hceOpen(nfgType, null);
            String log = "open hce " + Utility.getStateString(code);
            showToast(log);
            Log.e(TAG, log);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Write NDEF message */
    private void writeNdefData() {
        try {
            String dataStr = edtNdefData.getText().toString();
            if (TextUtils.isEmpty(dataStr)) {
                showToast("ndef data shouldn't be empty");
                edtNdefData.requestFocus();
                return;
            }
            NdefRecord record = null;
            if (isHttpUrl(dataStr)) {
                Uri uri = Uri.parse(dataStr);
                record = NdefRecord.createUri(uri);
            } else {
                String languageCode = Locale.CHINA.getLanguage();
                record = NdefRecord.createTextRecord(languageCode, dataStr);
            }
            NdefMessage msg = new NdefMessage(record);
            Log.e(TAG, "hceNdefWrite(), NdefMessage: " + msg);
            int code = MyApplication.app.hceManagerV2.hceNdefWrite(msg);
            String log = "hceNdefWrite " + Utility.getStateString(code);
            showToast(log);
            Log.e(TAG, log);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Read NDEF message */
    private void readNdefData() {
        try {
            String log = null;
            NdefMessage ndefMsg = MyApplication.app.hceManagerV2.hceNdefRead();
            if (ndefMsg == null) {
                log = "hce read NdefMessage failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            StringBuilder sb = new StringBuilder();
            NdefRecord[] records = ndefMsg.getRecords();
            for (NdefRecord record : records) {
                String text = parseTextRecord(record);
                if (!TextUtils.isEmpty(text)) {
                    sb.append(text);
                }
            }
            tvInfo.setText(sb);
            log = sb.toString();
            showToast("hceNdefRead() success");
            Log.e(TAG, log);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Parse NdefRecord text data */
    private String parseTextRecord(NdefRecord record) {
        //check TNF(type name format)
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) {
            return null;
        }
        //判断可变的长度的类型
        if (!Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            return null;
        }
        try {
            //获得字节数组，然后进行分析
            byte[] payload = record.getPayload();
            //下面开始NDEF文本数据第一个字节，状态字节
            //判断文本是基于UTF-8还是UTF-16的，取第一个字节"位与"上16进制的80，16进制的80也就是最高位是1，
            //其他位都是0，所以进行"位与"运算后就会保留最高位
            String textEncoding = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
            //3f最高两位是0，第六位是1，所以进行"位与"运算后获得第六位
            int languageCodeLength = payload[0] & 0x3f;
            //下面开始NDEF文本数据第二个字节，语言编码
            //获得语言编码
            String languageCode = new String(payload, 1, languageCodeLength, StandardCharsets.US_ASCII);
            //下面开始NDEF文本数据后面的字节，解析出文本
            String textRecord = new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
            return textRecord;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /** close HCE */
    private void closeHce(boolean showToast) {
        try {
            int code = MyApplication.app.hceManagerV2.hceClose();
            String log = "close hce " + Utility.getStateString(code);
            if (showToast) {
                showToast(log);
            }
            Log.e(TAG, log);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 判断字符串是否为URL
     *
     * @param urls 需要判断的String类型url
     * @return true:是URL；false:不是URL
     */
    private boolean isHttpUrl(String urls) {
        //设置正则表达式
        String regex = "(((https|http)?://)?([a-z0-9]+[.])|(www.))"
                + "\\w+[.|\\/]([a-z0-9]{0,})?[[.]([a-z0-9]{0,})]+((/[\\S&&[^,;\u4E00-\u9FA5]]+)+)?([.][a-z0-9]{0,}+|/?)";
        //对比
        Pattern pat = Pattern.compile(regex.trim());
        Matcher mat = pat.matcher(urls.trim());
        //判断是否匹配
        return mat.matches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeHce(false);
    }
}
