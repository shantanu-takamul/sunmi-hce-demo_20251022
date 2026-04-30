package com.sunmi.nfc.demo.activity;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public abstract class BaseAppCompatActivity extends AppCompatActivity implements View.OnClickListener {
    protected static final String TAG = "SunmiNfcDemo";
    private AlertDialog loadingDlg;

    @Override
    public void onClick(View v) {

    }

    protected void showLoadingDialog(String msg) {
        runOnUiThread(() -> {
            if (loadingDlg == null) {
                loadingDlg = new AlertDialog.Builder(this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(msg)
                        .create();
            }
            loadingDlg.setMessage(msg);
            if (!loadingDlg.isShowing()) {
                loadingDlg.show();
            }
        });
    }

    protected void dismissLoadingDialog() {
        runOnUiThread(() -> {
            if (loadingDlg != null) {
                loadingDlg.dismiss();
            }
        });
    }

    protected String formatStr(String format, Object... params) {
        return String.format(Locale.ENGLISH, format, params);
    }

    protected void addText(TextView tv, String text) {
        CharSequence prev = tv.getText();
        if (TextUtils.isEmpty(prev)) {
            tv.setText(text);
        } else {
            tv.setText(TextUtils.concat(prev, "\n", text));
        }
    }

    protected void showToast(String msg) {
        runOnUiThread(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        });
    }

    protected void openActivity(Class<? extends Activity> clazz) {
        Intent intent = new Intent(this, clazz);
        openActivity(intent, false);
    }

    protected void openActivity(Class<? extends Activity> clazz, boolean finishSelf) {
        Intent intent = new Intent(this, clazz);
        openActivity(intent, finishSelf);
    }

    protected void openActivity(Intent intent, boolean finishSelf) {
        startActivity(intent);
        if (finishSelf) {
            finish();
        }
    }


}
