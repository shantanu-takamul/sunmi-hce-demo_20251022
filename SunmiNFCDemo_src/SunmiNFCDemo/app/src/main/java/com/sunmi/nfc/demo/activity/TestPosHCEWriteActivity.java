package com.sunmi.nfc.demo.activity;

import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sunmi.nfc.demo.R;
import com.sunmi.nfc.demo.util.ByteUtil;
import com.sunmi.nfc.demo.util.SystemPropertiesUtil;
import com.sunmi.nfc.demo.wrapper.CheckCardCallbackV2Wrapper;
import com.sunmi.pay.hardware.aidl.AidlConstants.CardType;
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2;

import java.util.Arrays;
import java.util.Locale;

import sunmi.paylib.SunmiPayKernel;

public class TestPosHCEWriteActivity extends BaseAppCompatActivity {
    private static final String NFC4_DATA_1 = "App market\n\n" +
            "Sunmi has an internal App market and the partner can distribute his/her own App in large scale to Sunmi devices via App market. Partial partners can have an App market managed by himself/herself. Their default own device users can only install App for the device via App market.";
    private static final String NFC4_DATA_2 = "Platform partners\n\n" +
            "They can develop App matching Sunmi devices and manage their own App markets. They can also control the App market to enable it to only display App wanted by themselves, and upload the App, which can appear in their own App markets without Sunmi review. By default, Sunmi can review to decide whether certain App be downloaded in another App market. The platform partner can also set their own Apps to enable them not to appear in other App markets.";
    private static final String NFC4_DATA_3 = "渠道合作伙伴\n\n" +
            "可以为旗下的设备维护一个自己独有的应用市场，选择应用市场中只出现自己想要的应用,不能上传应用。\n\n" +
            "每个商米合作伙伴都可以在商米官网注册商米合作伙伴帐号，有一个自己的操作后台，原则上每一台商米的设备在卖出去的时候都会和一个合作伙伴账号绑定，商米会以合作伙伴为粒度提供部分功能和权限的控制服务。";
    private RadioGroup rdgNfcType;
    private RadioGroup rdgNfcData;
    private TextView tvInfo;
    private EditText edtNdefData;
    private volatile boolean connectPaySDK;
    private ReadCardOptV2 readCardOptV2;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_write_hce);
        initView();
        bindPaySDK();
    }

    private void initView() {
        rdgNfcType = findViewById(R.id.rdg_nfc_type);
        rdgNfcType.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
        findViewById(R.id.rdo_type2).setEnabled(false);
        rdgNfcData = findViewById(R.id.rdg_nfc_data);
        rdgNfcData.setOnCheckedChangeListener((group, checkedId) -> {
            edtNdefData.setText(getNfcData());
        });
        edtNdefData = findViewById(R.id.edt_ndef_text);
        findViewById(R.id.btn_write_ndef_data).setOnClickListener(v -> testHceWriteNdefDataByPOS());
        tvInfo = findViewById(R.id.tv_info);
        rdgNfcType.check(R.id.rdo_type4);
        rdgNfcData.check(R.id.rdo_data_1);
    }

    private String getNfcData() {
        int typeId = rdgNfcType.getCheckedRadioButtonId();
        int dataId = rdgNfcData.getCheckedRadioButtonId();
        if (typeId == R.id.rdo_type4) {
            if (dataId == R.id.rdo_data_1) {
                return NFC4_DATA_1;
            } else if (dataId == R.id.rdo_data_2) {
                return NFC4_DATA_2;
            } else if (dataId == R.id.rdo_data_3) {
                return NFC4_DATA_3;
            }
        }
        return null;
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
            }

            @Override
            public void onDisconnectPaySDK() {
                Log.e(TAG, "onDisconnectPaySDK...");
                connectPaySDK = false;
            }
        });
    }

    /** 在POS机上写入HCE NDEF数据 */
    private void testHceWriteNdefDataByPOS() {
        try {
            if (!connectPaySDK) {
                showToast("Connecting to PaySDK service, please wait..");
                return;
            }
            String dataStr = edtNdefData.getText().toString();
            showToast("Tap device to HCE device");
            readCardOptV2.checkCard(CardType.NFC.getValue(), new CheckCardCallbackV2Wrapper() {
                @Override
                public void findRFCardEx(Bundle info) {
                    Log.e(TAG, "findRFCardEx(), info:" + info.toString());
                    writeNdefDataByTransmitApdu(dataStr);
                }
            }, 60);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 通过透传APDU接口读取NDEF数据 */
    private void writeNdefDataByTransmitApdu(final String dataStr) {
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
            String languageCode = Locale.CHINA.getLanguage();
            NdefRecord record = NdefRecord.createTextRecord(languageCode, dataStr);
            NdefMessage msg = new NdefMessage(record);
            byte[] rdata = msg.toByteArray();
            //5.Write the NLEN field of the NDEF file
            int NLEN = rdata.length;
            sendStr = formatStr("00D6000002%04X", NLEN);
            send = ByteUtil.hexString2Bytes(sendStr);
            recv = transmitApdu(send, buffer);
            recvStr = ByteUtil.bytes2HexString(recv);
            Log.e(TAG, "send:>> " + sendStr);
            Log.e(TAG, "recv:<< " + recvStr);
            if (!checkReceive(recv)) {
                log = "Write the NLEN field of the NDEF file failed";
                showToast(log);
                Log.e(TAG, log);
                return;
            }
            //6.Write data to the NDEF File
            int MLcV = ByteUtil.unsignedShort2IntBE(MLc, 0);
            if (NLEN <= MLcV) {//NDEF data length less than or equal to MLc value
                sendStr = formatStr("00D60002%02X%s", NLEN, ByteUtil.bytes2HexString(rdata));
                send = ByteUtil.hexString2Bytes(sendStr);
                recv = transmitApdu(send, buffer);
                recvStr = ByteUtil.bytes2HexString(recv);
                Log.e(TAG, "send:>> " + sendStr);
                Log.e(TAG, "recv:<< " + recvStr);
                if (!checkReceive(recv)) {
                    log = "Write data to the NDEF File";
                    showToast(log);
                    Log.e(TAG, log);
                    return;
                }
            } else {//NDEF data length great than MLc values
                for (int index = 2, size = NLEN + 2; index < size; index += MLcV) {
                    int writeLen = Math.min(MLcV, size - index);
                    send = new byte[5 + writeLen];
                    send[0] = 0x00;
                    send[1] = (byte) 0xD6;
                    send[2] = (byte) (index >> 8);
                    send[3] = (byte) index;
                    send[4] = (byte) writeLen;
                    System.arraycopy(rdata, index - 2, send, 5, writeLen);
                    sendStr = ByteUtil.bytes2HexString(send);
                    recv = transmitApdu(send, buffer);
                    recvStr = ByteUtil.bytes2HexString(recv);
                    Log.e(TAG, "send:>> " + sendStr);
                    Log.e(TAG, "recv:<< " + recvStr);
                    if (!checkReceive(recv)) {
                        log = "Write Data to the NDEF File failed";
                        showToast(log);
                        Log.e(TAG, log);
                        return;
                    }
                }
            }
            showToast("Write HCE NDEF data success");
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

    private boolean checkReceive(byte[] recv) {
        if (recv == null || recv.length < 2) {
            return false;
        }
        final byte[] stateSuccess = {(byte) 0x90, 0x00};
        byte[] stateWord = Arrays.copyOfRange(recv, recv.length - 2, recv.length);
        return Arrays.equals(stateWord, stateSuccess);
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
