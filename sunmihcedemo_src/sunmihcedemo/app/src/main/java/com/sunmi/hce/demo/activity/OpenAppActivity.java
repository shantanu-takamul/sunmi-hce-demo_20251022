package com.sunmi.hce.demo.activity;

import android.annotation.SuppressLint;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.Nullable;

import com.sunmi.hce.demo.MyApplication;
import com.sunmi.hce.demo.R;
import com.sunmi.hce.demo.util.Utility;
import com.sunmi.pay.hardware.aidl.AidlConstants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OpenAppActivity extends BaseAppCompatActivity {
    private EditText edtPkgName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_open_app);
        initView();
    }

    @SuppressLint({"NonConstantResourceId", "SetTextI18n"})
    private void initView() {
        initActionbar("Test Open App");
        edtPkgName = findViewById(R.id.edt_pkg_name);
        RadioGroup group = findViewById(R.id.rdg_app);
        group.setOnCheckedChangeListener((group1, checkedId) -> {
            switch (checkedId) {
                case R.id.rdo_app_wechat:
                    edtPkgName.setText("com.tencent.mm");
                    break;
                case R.id.rdo_app_alipay:
                    edtPkgName.setText("com.eg.android.AlipayGphone");
                    break;
                case R.id.rdo_app_douyin:
                    edtPkgName.setText("com.ss.android.ugc.aweme.lite");
                    break;
                case R.id.rdo_app_bili:
                    edtPkgName.setText("tv.danmaku.bili");
                    break;
                case R.id.rdo_app_tiktok:
                    edtPkgName.setText("com.ss.android.ugc.trill");
                    break;
                case R.id.rdo_app_facebook:
                    edtPkgName.setText("com.facebook.katana");
                    break;
            }
        });
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(this);
        findViewById(R.id.btn_close_hce).setOnClickListener(this);
        this.<RadioButton>findViewById(R.id.rdo_app_wechat).setChecked(true);
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
            String dataStr = edtPkgName.getText().toString();
            if (TextUtils.isEmpty(dataStr)) {
                showToast("Package name shouldn't be empty");
                edtPkgName.requestFocus();
                return;
            }
            //2. Open HCE
            int nfgType = AidlConstants.CardType.NFC.getValue();
            int code = MyApplication.app.hceManagerV2.hceOpen(nfgType);
            String log = "open hce " + Utility.getStateString(code);
            Log.e(TAG, log);

            NdefRecord record = NdefRecord.createApplicationRecord(dataStr);
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
