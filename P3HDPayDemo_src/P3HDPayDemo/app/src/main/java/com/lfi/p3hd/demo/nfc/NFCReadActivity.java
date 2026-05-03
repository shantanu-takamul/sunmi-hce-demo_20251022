package com.lfi.p3hd.demo.nfc;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.lfi.p3hd.demo.BaseAppCompatActivity;
import com.lfi.p3hd.demo.MyApplication;
import com.lfi.p3hd.demo.R;
import com.lfi.p3hd.demo.utils.ByteUtil;
import com.lfi.p3hd.demo.wrapper.CheckCardCallbackV2Wrapper;
import com.sunmi.pay.hardware.aidl.AidlConstants.CardType;
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NFCReadActivity extends BaseAppCompatActivity {
    private TextView tvInfo;
    private ReadCardOptV2 readCardOptV2;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_read);
        initView();
        initSdk();
    }

    private void initView() {
        setupHeader(R.string.nfc_read_header_label, R.string.nfc_read_header_title, true);
        tvInfo = findViewById(R.id.tv_info);
        findViewById(R.id.btn_start_scan).setOnClickListener(v -> startScan());
        findViewById(R.id.btn_stop_scan).setOnClickListener(v -> stopScan());
    }

    private void initSdk() {
        if (MyApplication.app.isConnectPaySDK()) {
            readCardOptV2 = MyApplication.app.readCardOptV2;
        } else {
            MyApplication.app.bindPaySDKService();
            // readCardOptV2 will be available after SDK connects
        }
    }

    private void startScan() {
        readCardOptV2 = MyApplication.app.readCardOptV2;
        if (readCardOptV2 == null) {
            showToast(R.string.sdk_not_connected);
            return;
        }
        try {
            showToast("Tap NFC card to device");
            tvInfo.setText(R.string.nfc_read_waiting);
            readCardOptV2.checkCard(CardType.NFC.getValue(), new CheckCardCallbackV2Wrapper() {
                @Override
                public void findRFCardEx(Bundle info) {
                    Log.d(TAG, "findRFCardEx: " + info);
                    readNdefDataByApdu();
                }

                @Override
                public void onError(int code, String message) throws RemoteException {
                    runOnUiThread(() -> showInfo("Error " + code + ": " + message));
                }

                @Override
                public void onErrorEx(Bundle info) throws RemoteException {
                    runOnUiThread(() -> showInfo("Error: " + info));
                }
            }, 60);
        } catch (Exception e) {
            Log.e(TAG, "startScan failed", e);
        }
    }

    private void stopScan() {
        try {
            if (readCardOptV2 != null) {
                readCardOptV2.cancelCheckCard();
                readCardOptV2.cardOff(CardType.NFC.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void readNdefDataByApdu() {
        byte[] buffer = new byte[2048];
        try {
            // 1. Select NDEF Tag Application
            byte[] send = ByteUtil.hexString2Bytes("00A4040007D276000085010100");
            byte[] recv = transmitApdu(send, buffer);
            if (!checkReceive(recv)) { showInfo("Select NDEF App failed"); return; }

            // 2. Select Capability Container file
            send = ByteUtil.hexString2Bytes("00A4000C02E103");
            recv = transmitApdu(send, buffer);
            if (!checkReceive(recv)) { showInfo("Select CC file failed"); return; }

            // 3. Read CC binary
            send = ByteUtil.hexString2Bytes("00B000000F");
            recv = transmitApdu(send, buffer);
            if (!checkReceive(recv)) { showInfo("Read CC failed"); return; }

            byte[] data = Arrays.copyOf(recv, recv.length - 2);
            if (data.length < 0x0f) { showInfo("CC format error"); return; }

            byte[] MLe = Arrays.copyOfRange(data, 3, 5);
            byte[] id  = Arrays.copyOfRange(data, 9, 11);

            // 4. Select NDEF file
            send = ByteUtil.hexString2Bytes("00A4000C02" + ByteUtil.bytes2HexString(id));
            recv = transmitApdu(send, buffer);
            if (!checkReceive(recv)) { showInfo("Select NDEF file failed"); return; }

            // 5. Read NLEN
            send = ByteUtil.hexString2Bytes("00B0000002");
            recv = transmitApdu(send, buffer);
            if (!checkReceive(recv)) { showInfo("Read NLEN failed"); return; }

            int NLEN = ByteUtil.unsignedShort2IntBE(recv, 0);
            int MLeV = ByteUtil.unsignedShort2IntBE(MLe, 0);

            // 6. Read NDEF data (chunked if needed)
            if (NLEN <= MLeV) {
                send = ByteUtil.hexString2Bytes(String.format("00B00002%02X", NLEN));
                recv = transmitApdu(send, buffer);
                if (!checkReceive(recv)) { showInfo("Read NDEF data failed"); return; }
                data = Arrays.copyOf(recv, recv.length - 2);
            } else {
                List<byte[]> list = new ArrayList<>();
                byte[] chunk = new byte[5];
                chunk[0] = 0x00;
                chunk[1] = (byte) 0xB0;
                for (int index = 2, size = NLEN + 2; index < size; index += MLeV) {
                    int readLen = Math.min(MLeV, size - index);
                    chunk[2] = (byte) (index >> 8);
                    chunk[3] = (byte) index;
                    chunk[4] = (byte) readLen;
                    recv = transmitApdu(chunk, buffer);
                    if (!checkReceive(recv)) { showInfo("Read NDEF chunk failed"); return; }
                    list.add(Arrays.copyOf(recv, recv.length - 2));
                }
                data = ByteUtil.concatByteArrays(list);
            }

            // 7. Parse NDEF message
            NdefMessage ndefMsg = new NdefMessage(data);
            StringBuilder sb = new StringBuilder();
            for (NdefRecord record : ndefMsg.getRecords()) {
                String text = parseTextRecord(record);
                if (!TextUtils.isEmpty(text)) sb.append(text);
            }
            showInfo(sb.length() > 0 ? sb.toString() : "No text records found");

        } catch (Exception e) {
            Log.e(TAG, "readNdefDataByApdu failed", e);
            showInfo("Error: " + e.getMessage());
        }
    }

    private byte[] transmitApdu(byte[] send, byte[] buffer) {
        try {
            int len = readCardOptV2.transmitApdu(CardType.NFC.getValue(), send, buffer);
            if (len >= 2) return Arrays.copyOf(buffer, len);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return new byte[2];
    }

    private boolean checkReceive(byte[] recv) {
        if (recv == null || recv.length < 2) return false;
        byte[] ok = {(byte) 0x90, 0x00};
        return Arrays.equals(Arrays.copyOfRange(recv, recv.length - 2, recv.length), ok);
    }

    private String parseTextRecord(NdefRecord record) {
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) return null;
        if (!Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) return null;
        try {
            byte[] payload = record.getPayload();
            String enc = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
            int langLen = payload[0] & 0x3f;
            return new String(payload, langLen + 1, payload.length - langLen - 1, enc);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void showInfo(String msg) {
        runOnUiThread(() -> tvInfo.setText(msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (readCardOptV2 != null) {
                readCardOptV2.cancelCheckCard();
                readCardOptV2.cardOff(CardType.NFC.getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
