package com.lfi.p3hd.demo.qr;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.lfi.p3hd.demo.R;

public class QRExpiredActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_expired);
        // Suppress back navigation — user must tap Return to QR to continue.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { /* intentionally suppressed */ }
        });
        findViewById(R.id.btn_return).setOnClickListener(v -> finish());
    }
}
