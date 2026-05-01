package com.lfi.p3hd.demo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.lfi.p3hd.demo.hce.HCEActivity;
import com.lfi.p3hd.demo.hce.HCEReceiptActivity;
import com.lfi.p3hd.demo.nfc.NFCReadActivity;
import com.lfi.p3hd.demo.other.SettingActivity;
import com.lfi.p3hd.demo.qr.QRPayActivity;

public class MainActivity extends AppCompatActivity {
    private TextView tvSdkStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSdkStatus();
    }

    private void initView() {
        tvSdkStatus = findViewById(R.id.tv_sdk_status);

        findViewById(R.id.card_qr_payment).setOnClickListener(v ->
            startActivity(new Intent(this, QRPayActivity.class)));

        // NFC Payment uses same QRPayActivity (mode selected there)
        findViewById(R.id.card_nfc_payment).setOnClickListener(v -> {
            Intent intent = new Intent(this, QRPayActivity.class);
            intent.putExtra("mode", "nfc");
            startActivity(intent);
        });

        findViewById(R.id.card_hce_emulate).setOnClickListener(v ->
            startActivity(new Intent(this, HCEActivity.class)));

        findViewById(R.id.card_hce_receipt).setOnClickListener(v ->
            startActivity(new Intent(this, HCEReceiptActivity.class)));

        findViewById(R.id.card_nfc_read).setOnClickListener(v ->
            startActivity(new Intent(this, NFCReadActivity.class)));

        findViewById(R.id.card_settings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingActivity.class)));
    }

    private void updateSdkStatus() {
        boolean connected = MyApplication.app != null && MyApplication.app.isConnectPaySDK();
        tvSdkStatus.setText(connected ? "SDK: Connected" : "SDK: Disconnected");
        tvSdkStatus.setTextColor(connected
            ? getColor(R.color.success)
            : getColor(R.color.accent));
    }
}
