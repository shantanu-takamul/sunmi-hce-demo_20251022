package com.lfi.p3hd.demo.qr;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.lfi.p3hd.demo.R;

public class QRExpiredActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_expired);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.qr_expired_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
        }
        findViewById(R.id.btn_return).setOnClickListener(v -> finish());
    }

    @Override
    public void onBackPressed() {
        // Suppressed intentionally
    }
}
