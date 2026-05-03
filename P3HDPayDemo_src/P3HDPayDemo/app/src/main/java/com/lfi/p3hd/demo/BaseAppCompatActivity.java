package com.lfi.p3hd.demo;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public abstract class BaseAppCompatActivity extends AppCompatActivity implements View.OnClickListener {
    protected final String TAG = getClass().getSimpleName();

    /**
     * Wires the shared header_screen.xml include present in the activity's layout.
     * Sets the label (small all-caps) and title text, shows/hides the back arrow,
     * and wires the back arrow to finish() when shown.
     */
    protected void setupHeader(@Nullable String label, @NonNull String title, boolean showBack) {
        View btnBack = findViewById(R.id.btn_back);
        TextView tvLabel = findViewById(R.id.tv_header_label);
        TextView tvTitle = findViewById(R.id.tv_header_title);
        if (btnBack != null) {
            btnBack.setVisibility(showBack ? View.VISIBLE : View.GONE);
            btnBack.setOnClickListener(v -> finish());
        }
        if (tvLabel != null) {
            if (label != null) {
                tvLabel.setText(label);
                tvLabel.setVisibility(View.VISIBLE);
            } else {
                tvLabel.setVisibility(View.GONE);
            }
        }
        if (tvTitle != null) {
            tvTitle.setText(title);
        }
    }

    protected void setupHeader(int labelResId, int titleResId, boolean showBack) {
        setupHeader(getString(labelResId), getString(titleResId), showBack);
    }

    /** @deprecated Headers are now driven by setupHeader(). Left for call-site compatibility. */
    public void initActionbar(String title) { }

    public void initActionbar(int resId) { }

    public void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    public void showToast(int resId) {
        showToast(getString(resId));
    }

    protected String formatStr(String format, Object... args) {
        return String.format(Locale.ENGLISH, format, args);
    }

    protected void openActivity(Class<? extends Activity> clazz) {
        startActivity(new Intent(this, clazz));
    }

    protected void openActivity(Class<? extends Activity> clazz, boolean finishSelf) {
        startActivity(new Intent(this, clazz));
        if (finishSelf) finish();
    }

    protected void openActivity(Intent intent, boolean finishSelf) {
        startActivity(intent);
        if (finishSelf) finish();
    }

    @Override
    public void onClick(View v) {
    }
}
