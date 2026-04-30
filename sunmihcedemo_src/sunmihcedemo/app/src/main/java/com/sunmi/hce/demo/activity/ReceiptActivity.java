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

import androidx.annotation.Nullable;

import com.sunmi.hce.demo.MyApplication;
import com.sunmi.hce.demo.R;
import com.sunmi.hce.demo.util.Utility;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceiptActivity extends BaseAppCompatActivity {
    private EditText edtUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt);
        initView();
    }

    private void initView() {
        initActionbar("Test Receipt");
        edtUrl = findViewById(R.id.edt_url);
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_close_hce).setOnClickListener(this);
        edtUrl.setText("https://p1.itc.cn/q_70/images03/20210206/a78e82713243448ab9d98c84c3f04c9c.jpeg");
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_write_ndef_data:
                writeNdefData();
                break;
            case R.id.btn_close_hce:
                closeHce(true);
                break;
        }
    }

    /** Write NDEF message */
    private void writeNdefData() {
        try {
            //1. check url
            String dataStr = edtUrl.getText().toString();
            if (TextUtils.isEmpty(dataStr)) {
                showToast("Receipt url shouldn't be empty");
                edtUrl.requestFocus();
                return;
            }
            if (!isHttpUrl(dataStr)) {
                showToast("Illegal receipt url, please check ");
                edtUrl.requestFocus();
                return;
            }
            //2. Open HCE
            int nfgType = AidlConstants.CardType.NFC.getValue();
            int code = MyApplication.app.hceManagerV2.hceOpen(nfgType);
            String log = "open hce " + Utility.getStateString(code);
            Log.e(TAG, log);

            //3. Write url to NdefMessage
            Uri uri = Uri.parse(dataStr);
            NdefRecord record = NdefRecord.createUri(uri);
            NdefMessage msg = new NdefMessage(record);
            Log.e(TAG, "hceNdefWrite(), NdefMessage: " + msg);
            code = MyApplication.app.hceManagerV2.hceNdefWrite(msg);
            log = "hceNdefWrite " + Utility.getStateString(code);
            showToast(log);
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

    /**
     * close HCE
     */
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeHce(false);
    }
}
