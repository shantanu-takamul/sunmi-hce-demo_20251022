package com.lfi.p3hd.demo.qr;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class PaymentSuccessActivity extends BaseAppCompatActivity {
    public static final String EXTRA_AMOUNT_AED  = "amountAed";
    public static final String EXTRA_REQUEST_ID  = "requestId";
    public static final String EXTRA_EMV_PAYLOAD = "emvPayload";

    private String amountAed;
    private String requestId;
    private String emvPayload;
    private String dateTimeStr;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment_success);

        amountAed  = getIntent().getStringExtra(EXTRA_AMOUNT_AED);
        requestId  = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        emvPayload = getIntent().getStringExtra(EXTRA_EMV_PAYLOAD);
        dateTimeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());

        initView();
    }

    private void initView() {
        initActionbar(R.string.payment_success_title);
        ((TextView) findViewById(R.id.tv_amount)).setText("AED " + amountAed);
        String shortRef = requestId != null && requestId.length() >= 8
            ? requestId.substring(requestId.length() - 8) : requestId;
        ((TextView) findViewById(R.id.tv_ref)).setText("Ref: " + shortRef);
        ((TextView) findViewById(R.id.tv_date_time)).setText(dateTimeStr);
        findViewById(R.id.btn_print).setOnClickListener(v -> new Thread(this::printReceipt).start());
        findViewById(R.id.btn_return).setOnClickListener(v -> finish());
    }

    private void printReceipt() {
        try {
            com.sunmi.pay.hardware.aidlv2.print.PrinterOptV2 printer = MyApplication.app.printerOptV2;
            if (printer == null) {
                showToast("Printer not connected");
                return;
            }
            int status = printer.getPrinterStatus();
            if (status < 0) {
                showToast("Printer error: " + status);
                return;
            }

            // Printer paper width: 384 dots (58mm at 8dpi)
            final int PAPER_WIDTH = 384;

            // Build receipt as a bitmap, then print row by row
            Bitmap receiptBmp = buildReceiptBitmap(PAPER_WIDTH);
            if (receiptBmp == null) {
                showToast("Failed to render receipt");
                return;
            }

            printer.printOpen();

            int rows = receiptBmp.getHeight();
            byte[] rowData = new byte[PAPER_WIDTH / 8];
            for (int y = 0; y < rows; y++) {
                for (int byteIdx = 0; byteIdx < rowData.length; byteIdx++) {
                    byte b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = byteIdx * 8 + bit;
                        int pixel = receiptBmp.getPixel(x, y);
                        // Black pixel = print dot (bit = 1), white = no dot
                        if (Color.red(pixel) < 128) {
                            b |= (byte) (0x80 >> bit);
                        }
                    }
                    rowData[byteIdx] = b;
                }
                printer.printPointLine(rowData);
            }

            // Feed paper at end
            printer.printFeedPaper(60);
            printer.printClose();

            showToast("Printed!");
        } catch (Exception e) {
            Log.e(TAG, "printReceipt error", e);
            showToast("Print failed: " + e.getMessage());
        }
    }

    private Bitmap buildReceiptBitmap(int width) {
        // Estimate height (will be trimmed)
        int estimatedHeight = 800;
        Bitmap bmp = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        int y = 20;

        // Header — center, bold large
        paint.setTextSize(32);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("LFI Payment", width / 2f, y, paint);
        y += 40;
        paint.setTextSize(26);
        canvas.drawText("Payment Receipt", width / 2f, y, paint);
        y += 36;

        // Divider
        paint.setStrokeWidth(2);
        canvas.drawLine(10, y, width - 10, y, paint);
        y += 20;

        // Fields — left aligned, normal
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(24);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("Amount:  AED " + amountAed, 16, y, paint); y += 36;
        canvas.drawText("Ref:     " + requestId, 16, y, paint); y += 36;
        canvas.drawText("Date:    " + dateTimeStr, 16, y, paint); y += 36;
        canvas.drawText("Status:  PAID", 16, y, paint); y += 36;

        // Divider
        canvas.drawLine(10, y, width - 10, y, paint);
        y += 20;

        // QR code centered
        Bitmap qrBmp = QRDisplayActivity.buildQRBitmap(emvPayload, 200, 200);
        if (qrBmp != null) {
            int qrX = (width - 200) / 2;
            canvas.drawBitmap(qrBmp, qrX, y, null);
            y += 220;
        }

        // Trim to actual content height
        return Bitmap.createBitmap(bmp, 0, 0, width, Math.min(y + 20, estimatedHeight));
    }
}
