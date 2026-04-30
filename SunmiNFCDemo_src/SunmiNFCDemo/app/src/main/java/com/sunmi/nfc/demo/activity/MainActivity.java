package com.sunmi.nfc.demo.activity;

import android.os.Bundle;

import com.sunmi.nfc.demo.R;

public class MainActivity extends BaseAppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
    }

    private void initView() {
        findViewById(R.id.btn_test_nfc).setOnClickListener((v) -> testNFC());
        findViewById(R.id.btn_test_mifare).setOnClickListener((v) -> testMifare());
        findViewById(R.id.btn_test_phone_hce_read).setOnClickListener((v) -> testPhoneHceReadNdefMessage());
        findViewById(R.id.btn_test_phone_hce_write).setOnClickListener((v) -> testPhoneHceWriteNdefMessage());
        findViewById(R.id.btn_test_pos_hce_read).setOnClickListener((v) -> testPosHceReadNdefMessage());
        findViewById(R.id.btn_test_pos_hce_write).setOnClickListener((v) -> testPosHceWriteNdefMessage());
    }

    private void testNFC() {
        openActivity(TestNFCActivity.class);
    }

    private void testMifare() {
        openActivity(TestMifareActivity.class);
    }

    private void testPhoneHceReadNdefMessage() {
        openActivity(TestPhoneHCEReadActivity.class);
    }

    private void testPhoneHceWriteNdefMessage() {
        openActivity(TestPhoneHCEWriteActivity.class);
    }

    private void testPosHceReadNdefMessage() {
        openActivity(TestPosHCEReadActivity.class);
    }

    private void testPosHceWriteNdefMessage() {
        openActivity(TestPosHCEWriteActivity.class);
    }

}