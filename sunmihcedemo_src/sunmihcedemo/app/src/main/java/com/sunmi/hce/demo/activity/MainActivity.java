package com.sunmi.hce.demo.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.sunmi.hce.demo.R;

public class MainActivity extends BaseAppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
    }

    private void initView() {
        findViewById(R.id.btn_test_hce).setOnClickListener(v -> openActivity(HCEActivity.class));
        findViewById(R.id.btn_test_receipt).setOnClickListener(v -> openActivity(ReceiptActivity.class));
        findViewById(R.id.btn_test_open_app).setOnClickListener(v -> openActivity(OpenAppActivity.class));
    }
}