package com.lfi.p3hd.demo.print;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;
import android.view.View;

import com.sunmi.pay.hardware.aidl.AidlConstants;
import com.sunmi.pay.hardware.aidlv2.print.PrinterOptV2;

import java.util.Arrays;

/**
 * Turns a receipt view into dots on the P3H's 58 mm thermal head.
 *
 * The PayLib printer is as low level as a printer gets: {@link PrinterOptV2}
 * offers no text, no images and no layout — only {@code printPointLine}, which
 * burns one row of 384 dots. Everything above that (fonts, alignment, wrapping,
 * rules, margins) is Android's layout engine, rendered into a bitmap here and
 * handed to the head row by row.
 *
 * <h3>Row order</h3>
 * Rows go out top first, y ascending, and the first row printed is the one
 * furthest from the tear bar — the top of the receipt as the operator reads it.
 * This is not a preference; it is the only order that produces readable text.
 * A strip of paper can be looked at from two ends and no more, so the printed
 * image is either upright or rotated 180°. Emitting rows in reverse flips the
 * image vertically instead, and a vertical flip is not a rotation of anything:
 * seen from one end it reads like a reflection in water, and from the other end
 * it reads mirror-written. Neither end is readable, which is exactly the receipt
 * this class was written to stop printing.
 *
 * <h3>Resolution</h3>
 * The head has 384 dots across and no more, so the caller's view is measured at
 * exactly {@link #PAPER_WIDTH_DOTS} and drawn 1:1. Rendering at screen width and
 * scaling down — the P3H's receipt card is 666 px wide, a 0.58 factor — pushes
 * 13sp body text to about twelve dots tall and then asks a hard black/white
 * threshold to reconstruct glyph stems out of the grey mush bilinear filtering
 * leaves behind. Laying out against the paper's real width instead means every
 * text pixel the layout engine produces is a dot the head can actually burn.
 */
public final class ReceiptPrinter {

    private static final String TAG = "ReceiptPrinter";

    /** Dots across the 58 mm head. Fixed by the hardware. */
    public static final int PAPER_WIDTH_DOTS = 384;

    private static final int BYTES_PER_ROW = PAPER_WIDTH_DOTS / 8;

    /** Blank rows before the first content row, so the header is not on the tear. */
    private static final int TOP_MARGIN_DOTS = 24;

    /**
     * Rows fed after the last content row.
     *
     * The head sits roughly 15 mm behind the tear bar, so without this the last
     * few lines are still inside the printer when the operator tears. It also
     * becomes the leading margin of the next receipt.
     */
    private static final int TEAR_OFF_FEED_DOTS = 120;

    /**
     * Luminance at or above which a pixel is paper rather than ink.
     *
     * The print layout is pure black on white, so this only has to decide the
     * anti-aliased fringe around glyph edges — mid-grey keeps stems solid without
     * smearing the halo into them.
     */
    private static final int INK_THRESHOLD = 128;

    private ReceiptPrinter() {
    }

    /** A print could not be completed, carrying text fit to show an operator. */
    public static class PrintException extends Exception {
        public PrintException(String message) {
            super(message);
        }
    }

    /**
     * Measures and draws {@code content} at the paper's native width.
     *
     * The view need not be attached to a window — it is measured against an exact
     * 384 px width and an unbounded height, so the receipt is as long as its
     * content and never truncated by a screen.
     *
     * @return the receipt bitmap, or null if the view measured to nothing
     */
    public static Bitmap rasterize(View content) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(PAPER_WIDTH_DOTS, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );

        int height = content.getMeasuredHeight();
        if (height <= 0) {
            Log.w(TAG, "rasterize: view measured to zero height");
            return null;
        }

        content.layout(0, 0, PAPER_WIDTH_DOTS, height);

        Bitmap bitmap = Bitmap.createBitmap(PAPER_WIDTH_DOTS, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        content.draw(canvas);
        return bitmap;
    }

    /** Dot rows a receipt of this height occupies, margins included. */
    public static int totalDotRows(int bitmapHeight) {
        return TOP_MARGIN_DOTS + bitmapHeight + TEAR_OFF_FEED_DOTS;
    }

    /**
     * Prints {@code receipt} and feeds it clear of the tear bar.
     *
     * Blocking, and never to be called from the main thread: a full receipt is
     * several hundred binder round trips and the head prints them in real time.
     *
     * @throws PrintException if the printer refused the job, with a message that
     *                        names the reason the operator can act on
     */
    public static void print(PrinterOptV2 printer, Bitmap receipt) throws PrintException {
        if (printer == null) {
            throw new PrintException("Printer not connected");
        }
        if (receipt == null || receipt.getWidth() != PAPER_WIDTH_DOTS) {
            throw new PrintException("Receipt was not rendered for this paper width");
        }

        try {
            // Asked before opening so a jam or an empty roll is reported as itself
            // rather than as a print that silently burned nothing.
            String blocked = blockingCondition(printer.getPrinterStatus());
            if (blocked != null) {
                throw new PrintException(blocked);
            }

            int openCode = printer.printOpen();
            if (openCode < 0) {
                throw new PrintException("Printer unavailable (error " + openCode + ")");
            }

            try {
                printer.printFeedPaper(TOP_MARGIN_DOTS);
                printRows(printer, receipt);
                printer.printFeedPaper(TEAR_OFF_FEED_DOTS);
            } finally {
                printer.printClose();
            }
        } catch (PrintException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "print failed", e);
            throw new PrintException("Print failed: " + e.getMessage());
        }
    }

    /**
     * Packs the bitmap into dot rows and sends them, top row first.
     *
     * Pixels are read in one bulk transfer rather than through getPixel, which
     * would be a JNI call per dot — around 300,000 for a receipt of this length.
     */
    private static void printRows(PrinterOptV2 printer, Bitmap receipt) throws Exception {
        int width = receipt.getWidth();
        int height = receipt.getHeight();

        int[] pixels = new int[width * height];
        receipt.getPixels(pixels, 0, width, 0, 0, width, height);

        byte[] row = new byte[BYTES_PER_ROW];
        for (int y = 0; y < height; y++) {
            Arrays.fill(row, (byte) 0);
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                // Bit 7 of byte 0 is the leftmost dot on the head.
                if (isInk(pixels[rowStart + x])) {
                    row[x >> 3] |= (byte) (0x80 >> (x & 7));
                }
            }

            int result = printer.printPointLine(row);
            if (result < 0) {
                throw new PrintException("Printer stopped at row " + y + " (error " + result + ")");
            }
        }
    }

    /** True if this pixel should burn a dot. */
    private static boolean isInk(int pixel) {
        // Anything see-through is paper: a transparent pixel has no colour to weigh.
        if (Color.alpha(pixel) < 128) return false;
        // BT.601 luminance — the eye's weighting, and the one the head's output is
        // judged by, so a mid-grey rule reads as grey rather than as solid black.
        int luminance =
            (Color.red(pixel) * 77 + Color.green(pixel) * 151 + Color.blue(pixel) * 28) >> 8;
        return luminance < INK_THRESHOLD;
    }

    /**
     * Operator-facing reason this printer cannot print, or null if it can.
     *
     * Unknown and negative values are let through deliberately. getPrinterStatus
     * is documented to return an error code below zero, and refusing to print on
     * a status the SDK could not read would turn a reporting quirk into a
     * terminal that cannot issue receipts.
     */
    private static String blockingCondition(int status) {
        switch (status) {
            case AidlConstants.PrinterStatus.PAPERLESS:
                return "Out of paper — load a new roll";
            case AidlConstants.PrinterStatus.OVERTEMPERATURE:
                return "Printer is too hot — try again shortly";
            case AidlConstants.PrinterStatus.LOW_BATTERY_VOLTAGE:
                return "Battery too low to print";
            case AidlConstants.PrinterStatus.PRI_CAP_OPEN:
                return "Printer cover is open";
            default:
                return null;
        }
    }
}
