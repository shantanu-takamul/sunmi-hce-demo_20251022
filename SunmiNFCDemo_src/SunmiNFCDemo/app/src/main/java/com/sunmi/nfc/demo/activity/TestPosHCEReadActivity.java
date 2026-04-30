package com.sunmi.nfc.demo.activity;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sunmi.nfc.demo.R;
import com.sunmi.nfc.demo.util.ByteUtil;
import com.sunmi.nfc.demo.util.SystemPropertiesUtil;
import com.sunmi.nfc.demo.wrapper.CheckCardCallbackV2Wrapper;
import com.sunmi.pay.hardware.aidl.AidlConstants.CardType;
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sunmi.paylib.SunmiPayKernel;

public class TestPosHCEReadActivity extends BaseAppCompatActivity {
    private TextView tvInfo;
    private volatile boolean connectPaySDK;
    private ReadCardOptV2 readCardOptV2;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_hce_read);
        initView();
        bindPaySDK();
    }

    private void initView() {
        tvInfo = findViewById(R.id.tv_info);
    }

    private void bindPaySDK() {
        if (!SystemPropertiesUtil.getBoolean("ro.sunmi.pay.device", false)) {
            showToast("Not Sunmi finance device");
            return;
        }
        final SunmiPayKernel payKernel = SunmiPayKernel.getInstance();
        payKernel.initPaySDK(this, new SunmiPayKernel.ConnectCallback() {
            @Override
            public void onConnectPaySDK() {
                Log.e(TAG, "onConnectPaySDK...");
                connectPaySDK = true;
                readCardOptV2 = payKernel.mReadCardOptV2;
                testHceReadNdefDataOnPos();
            }

            @Override
            public void onDisconnectPaySDK() {
                Log.e(TAG, "onDisconnectPaySDK...");
                connectPaySDK = false;
            }
        });
    }

    /** 在POS机上读取HCE NDEF数据 */
    private void testHceReadNdefDataOnPos() {
        try {
            showToast("Tap device to HCE device");
            readCardOptV2.checkCard(CardType.NFC.getValue(), new CheckCardCallbackV2Wrapper() {
                @Override
                public void findRFCardEx(Bundle info) {
                    Log.e(TAG, "findRFCardEx(), info:" + info.toString());
                    readNdefDataByTransmitApdu();
                }
            }, 60);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 通过透传APDU接口读取NDEF数据 */
    private void readNdefDataByTransmitApdu() {
        String log = null;
        byte[] buffer = new byte[2048];
        try {
            //1.Select NDEF Tag Application
            String sendStr = "00A4040007D276000085010100";
            String recvStr = null;
            byte[] send = ByteUtil.hexString2Bytes(sendStr);
            byte[] recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "select NDEF Tag Application failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //2.select Capability Container(CC) file
            sendStr = "00A4000C02E103";
            send = ByteUtil.hexString2Bytes(sendStr);
            recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "select CC file failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //3.ReadBinary data from CC file
            sendStr = "00B000000F";
            send = ByteUtil.hexString2Bytes(sendStr);
            recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "ReadBinary data from CC file failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            byte[] data = Arrays.copyOf(recv, recv.length - 2);
            if (data.length < 0x0f) {
                log = "CC file format error";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //CCLEN= cc file length
            byte[] ccLen = Arrays.copyOf(data, 2);
            //Mapping Version
            String mappingVer = formatStr("%d.%d", (data[2] >> 4) & 0x0f, data[2] & 0xf);
            //MLe maximum 59 bytes R-APDU data size
            byte[] MLe = Arrays.copyOfRange(data, 3, 5);
            //MLc maximum 52 bytes C-APDU data size
            byte[] MLc = Arrays.copyOfRange(data, 5, 7);
            //NDEF File Control TLV
            byte[] ndefCtrTlv = Arrays.copyOfRange(data, 7, data.length);
            byte[] T = Arrays.copyOfRange(ndefCtrTlv, 0, 1);
            byte[] L = Arrays.copyOfRange(ndefCtrTlv, 1, 2);
            byte[] V = Arrays.copyOfRange(ndefCtrTlv, 2, ndefCtrTlv.length);
            //File Identifier of NDEF file(E104h)
            byte[] id = Arrays.copyOfRange(V, 0, 2);
            //Maximum NDEF file size of 50 bytes
            byte[] maxNdefFileSize = Arrays.copyOfRange(V, 2, 4);
            //Read access without any security
            byte[] readAccessCtrlFlag = Arrays.copyOfRange(V, 4, 5);
            //Write access without any security
            byte[] writeAccessCtrlFlag = Arrays.copyOfRange(V, 5, 6);
            Log.e(TAG, formatStr("CCLEN:%s, Mapping Version:%s, MLe:%s, MLc:%s, NDEF file control tlv:%s\n" +
                            "NDEF file control tlv-T:%s\n" +
                            "NDEF file control tlv-L:%s\n" +
                            "NDEF file control tlv-V:%s\n" +
                            "NDEF file identifier:%s\n" +
                            "Maximum NDEF file size:%s\n" +
                            "Read access flag:%s\n" +
                            "Write access flag:%s",
                    ByteUtil.bytes2HexString(ccLen), mappingVer, ByteUtil.bytes2HexString(MLe), ByteUtil.bytes2HexString(MLc), ByteUtil.bytes2HexString(ndefCtrTlv),
                    ByteUtil.bytes2HexString(T), ByteUtil.bytes2HexString(L), ByteUtil.bytes2HexString(V),
                    ByteUtil.bytes2HexString(id), ByteUtil.bytes2HexString(maxNdefFileSize),
                    ByteUtil.bytes2HexString(readAccessCtrlFlag), ByteUtil.bytes2HexString(writeAccessCtrlFlag)));
            //4.select NDEF file
            sendStr = "00A4000C02" + ByteUtil.bytes2HexString(id);
            send = ByteUtil.hexString2Bytes(sendStr);
            recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "select NDEF file failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //5.Read the NLEN field of the NDEF file
            sendStr = "00B0000002";
            send = ByteUtil.hexString2Bytes(sendStr);
            recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "Read the NLEN field of the NDEF file failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //6.Read Data from the NDEF File
            int NLEN = ByteUtil.unsignedShort2IntBE(recv, 0);
            Log.e(TAG, "NLEN:" + ByteUtil.bytes2HexString(recv, 0, 2));
            int MLeV = ByteUtil.unsignedShort2IntBE(MLe, 0);
            if (NLEN <= MLeV) {//NDEF data length less than or equal to MLe value
                sendStr = formatStr("00B00002%02X", NLEN);
                send = ByteUtil.hexString2Bytes(sendStr);
                recv = transmitApdu(send, buffer);
                recvStr = ByteUtil.bytes2HexString(recv);
                Log.e(TAG, "send:>> " + sendStr);
                Log.e(TAG, "recv:<< " + recvStr);
                if (!checkReceive(recv)) {
                    log = "Read Data from the NDEF File failed";
                    showToast(log);
                    Log.e(TAG, log);
                    return;
                }
                data = Arrays.copyOf(recv, recv.length - 2);//remove 9000
            } else { //NDEF data length great than MLe values
                List<byte[]> list = new ArrayList<>();
                send = new byte[5];
                send[0] = 0x00;
                send[1] = (byte) 0xB0;
                for (int index = 2, size = NLEN + 2; index < size; index += MLeV) {
                    int readLen = Math.min(MLeV, size - index);
                    send[2] = (byte) (index >> 8);
                    send[3] = (byte) index;
                    send[4] = (byte) readLen;
                    sendStr = ByteUtil.bytes2HexString(send);
                    recv = transmitApdu(send, buffer);
                    recvStr = ByteUtil.bytes2HexString(recv);
                    Log.e(TAG, "send:>> " + sendStr);
                    Log.e(TAG, "recv:<< " + recvStr);
                    if (!checkReceive(recv)) {
                        log = "Read Data from the NDEF File failed";
                        showToast(log);
                        Log.e(TAG, log);
                        return;
                    }
                    list.add(Arrays.copyOf(recv, recv.length - 2));//remove 9000
                }
                data = ByteUtil.concatByteArrays(list);
            }
            //7.Parse NDEF message, subtract text data
            StringBuilder sb = new StringBuilder();
            NdefMessage ndefMsg = new NdefMessage(data);
            NdefRecord[] records = ndefMsg.getRecords();
            for (NdefRecord record : records) {
                String text = parseTextRecord(record);
                if (!TextUtils.isEmpty(text)) {
                    sb.append(text);
                }
            }
            String msg = sb.toString();
            showInfo(msg);
            Log.e(TAG, msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Exchange data with HCE device by transmitApdu() */
    private byte[] transmitApdu(byte[] send, byte[] buffer) {
        try {
            int len = readCardOptV2.transmitApdu(CardType.NFC.getValue(), send, buffer);
            if (len >= 2) {
                return Arrays.copyOf(buffer, len);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return new byte[2];
    }

    /** Parse NdefRecord text data */
    private String parseTextRecord(NdefRecord record) {
        //判断TNF
        if (record.getTnf() != NdefRecord.TNF_WELL_KNOWN) {
            return null;
        }
        //判断可变的长度的类型
        if (!Arrays.equals(record.getType(), NdefRecord.RTD_TEXT)) {
            return null;
        }
        try {
            //获得字节数组，然后进行分析
            byte[] payload = record.getPayload();
            //下面开始NDEF文本数据第一个字节，状态字节
            //判断文本是基于UTF-8还是UTF-16的，取第一个字节"位与"上16进制的80，16进制的80也就是最高位是1，
            //其他位都是0，所以进行"位与"运算后就会保留最高位
            String textEncoding = ((payload[0] & 0x80) == 0) ? "UTF-8" : "UTF-16";
            //3f最高两位是0，第六位是1，所以进行"位与"运算后获得第六位
            int languageCodeLength = payload[0] & 0x3f;
            //下面开始NDEF文本数据第二个字节，语言编码
            //获得语言编码
            String languageCode = new String(payload, 1, languageCodeLength, StandardCharsets.US_ASCII);
            //下面开始NDEF文本数据后面的字节，解析出文本
            String textRecord = new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
            return textRecord;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private boolean checkReceive(byte[] recv) {
        if (recv == null || recv.length < 2) {
            return false;
        }
        final byte[] stateSuccess = {(byte) 0x90, 0x00};
        byte[] stateWord = Arrays.copyOfRange(recv, recv.length - 2, recv.length);
        return Arrays.equals(stateWord, stateSuccess);
    }

    private void showInfo(String msg) {
        runOnUiThread(() -> {
            tvInfo.setText(msg);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dispose();
    }

    private void dispose() {
        try {
            if (readCardOptV2 != null) {
                readCardOptV2.cancelCheckCard();
                readCardOptV2.cardOff(CardType.NFC.getValue());
            }
            SunmiPayKernel.getInstance().destroyPaySDK();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
