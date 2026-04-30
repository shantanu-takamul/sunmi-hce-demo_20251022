package com.sunmi.nfc.demo.activity;

import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.sunmi.nfc.demo.R;
import com.sunmi.nfc.demo.util.ByteUtil;
import com.sunmi.nfc.demo.util.IOUtil;

import java.util.Arrays;

public class TestMifareActivity extends BaseAppCompatActivity {
    private static final String TAG = "TestMifareActivity";
    private static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_NFC_B
            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
    private EditText mEditSector1;
    private EditText mEditKeyA1;
    private EditText mEditKeyB1;
    private EditText mEditBlock0;
    private EditText mEditBlock1;
    private EditText mEditBlock2;
    private EditText mEditSector2;
    private EditText mEditBlock;
    private EditText mEditKeyA2;
    private EditText mEditKeyB2;
    private EditText mEditCost;
    private EditText mEditSector3;
    private EditText mEditBlock3;
    private EditText mEditKeyA3;
    private EditText mEditKeyB3;
    private TextView mTvBalance;

    private int block;
    private int sector;
    private int keyType;    //key type:0-keyA,1-keyB
    private byte[] keyBytes;

    private NfcAdapter nfcAdapter;
    private Tag tag = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e(TAG, "onCreate...");
        setContentView(R.layout.activity_test_mifare);
        initNFC();
        initView();
    }

    private void initNFC() {
        Log.e(TAG, "init...");
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            Log.e(TAG, "NFC not supported.");
        } else if (!nfcAdapter.isEnabled()) {
            Log.e(TAG, "NFC not enabled.");
            Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
            startActivity(intent);
        }
    }

    private void initView() {
        mEditSector1 = findViewById(R.id.edit_sector_1);
        mEditKeyA1 = findViewById(R.id.edit_keyA_1);
        mEditKeyB1 = findViewById(R.id.edit_keyB_1);
        mEditBlock0 = findViewById(R.id.edit_block_0);
        mEditBlock1 = findViewById(R.id.edit_block_1);
        mEditBlock2 = findViewById(R.id.edit_block_2);

        mEditSector2 = findViewById(R.id.edit_sector_2);
        mEditKeyA2 = findViewById(R.id.edit_keyA_2);
        mEditKeyB2 = findViewById(R.id.edit_keyB_2);
        mEditBlock = findViewById(R.id.edit_block);
        mEditCost = findViewById(R.id.edit_cost);

        mEditSector3 = findViewById(R.id.edit_sector_3);
        mEditBlock3 = findViewById(R.id.edit_block_3);
        mEditKeyA3 = findViewById(R.id.edit_keyA_3);
        mEditKeyB3 = findViewById(R.id.edit_keyB_3);

        mTvBalance = findViewById(R.id.tv_balance);

        findViewById(R.id.mb_read).setOnClickListener(this);
        findViewById(R.id.mb_write).setOnClickListener(this);
        findViewById(R.id.mb_init).setOnClickListener(this);
        findViewById(R.id.mb_balance).setOnClickListener(this);
        findViewById(R.id.mb_add).setOnClickListener(this);
        findViewById(R.id.mb_reduce).setOnClickListener(this);
        findViewById(R.id.mb_restore).setOnClickListener(this);
        showLoadingDialog("Please tap NFC card");
        mEditSector1.setText("0");
        mEditKeyB1.setText("ffffffffffff");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(TAG, "onResume...");
        //开启前台调度系统
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
            nfcAdapter.enableReaderMode(this, nfcReadCallback, READER_FLAGS, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.e(TAG, "onPause...");
        if (nfcAdapter != null) {
            nfcAdapter.disableReaderMode(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.e(TAG, "onStop...");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tag = null;
        Log.e(TAG, "onDestroy...");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.e(TAG, "onNewIntent...");
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        switch (id) {
            case R.id.mb_read:
                if (checkParams()) {
                    readAllSector();
                }
                break;
            case R.id.mb_write:
                if (checkParams()) {
                    writeAllSector();
                }
                break;
            case R.id.mb_init:
                if (checkWalletParam()) {
                    initWallet();
                }
                break;
            case R.id.mb_balance:
                if (checkWalletParam()) {
                    getBalanceWallet();
                }
                break;
            case R.id.mb_add:
                if (checkWalletParam()) {
                    increaseValueWallet();
                }
                break;
            case R.id.mb_reduce:
                if (checkWalletParam()) {
                    decreaseValueWallet();
                }
                break;
            case R.id.mb_restore:
                if (checkRestoreParam()) {
                    int code = restore(block);
                    showToast("restore " + (code == 0 ? "success" : "failed"));
                }
                break;
        }
    }

    private final NfcAdapter.ReaderCallback nfcReadCallback = tag -> {
        Log.e(TAG, "find nfc tag:" + tag);
        this.tag = tag;
        dismissLoadingDialog();
    };

    private void readAllSector() {
        int startBlockNo = sector * 4;
        byte[] outData = new byte[128];
        int res = m1ReadBlock(startBlockNo, outData);
        if (res >= 0 && res <= 16) {
            String hexStr = ByteUtil.bytes2HexString(Arrays.copyOf(outData, res));
            Log.e(TAG, "read outData:" + hexStr);
            mEditBlock0.setText(hexStr);
        } else {
            mEditBlock0.setText("fail");
        }
        outData = new byte[128];
        res = m1ReadBlock(startBlockNo + 1, outData);
        if (res >= 0 && res <= 16) {
            String hexStr = ByteUtil.bytes2HexString(Arrays.copyOf(outData, res));
            Log.e(TAG, "read outData:" + hexStr);
            mEditBlock1.setText(hexStr);
        } else {
            mEditBlock1.setText("fail");
        }

        outData = new byte[128];
        res = m1ReadBlock(startBlockNo + 2, outData);
        if (res >= 0 && res <= 16) {
            String hexStr = ByteUtil.bytes2HexString(Arrays.copyOf(outData, res));
            Log.e(TAG, "read outData:" + hexStr);
            mEditBlock2.setText(hexStr);
        } else {
            mEditBlock2.setText("fail");
        }
    }

    private void writeAllSector() {
        int startBlockNo = sector * 4;
//        boolean result = m1Auth(keyType, startBlockNo, keyBytes);
        String val = mEditBlock0.getText().toString();
        if (val.length() == 32) {
            byte[] inData = ByteUtil.hexString2Bytes(val);
            int res = m1WriteBlock(startBlockNo, inData);
            if (res == 0) {
                mEditBlock0.setText("");
            } else {
                mEditBlock0.setText("fail");
            }
        }

        val = mEditBlock1.getText().toString();
        if (val.length() == 32) {
            byte[] inData = ByteUtil.hexString2Bytes(val);
            int res = m1WriteBlock(startBlockNo + 1, inData);
            if (res == 0) {
                mEditBlock1.setText("");
            } else {
                mEditBlock1.setText("fail");
            }
        }

        val = mEditBlock2.getText().toString();
        if (val.length() == 32) {
            byte[] inData = ByteUtil.hexString2Bytes(val);
            int res = m1WriteBlock(startBlockNo + 2, inData);
            if (res == 0) {
                mEditBlock2.setText("");
            } else {
                mEditBlock2.setText("fail");
            }
        }
    }

    /**
     * init wallet format
     */
    private void initWallet() {
        byte[] inData = getInitFormatData(block);
        String hexStr = ByteUtil.bytes2HexString(inData);
        Log.e(TAG, "init wallet format inData:" + hexStr);
        int res = m1WriteBlock(block, inData);
        if (res == 0) {
            showToast("init wallet format success");
            getBalanceWallet();
        } else {
            showToast("init wallet format failed:" + res);
        }
    }

    /**
     * get wallet balance
     */
    private void getBalanceWallet() {
        byte[] outData = new byte[128];
        int res = m1ReadBlock(block, outData);
        if (res >= 0 && res <= 16) {
            String hexStr = ByteUtil.bytes2HexString(Arrays.copyOf(outData, res));
            Log.e(TAG, "get wallet balance outData:" + hexStr);
            int balance = ByteUtil.unsignedInt2IntLE(outData, 0);
            mTvBalance.setText("Balance: " + balance);
        } else {
            String error = "Get wallet balance failed: " + res;
            showToast(error);
        }
    }

    /**
     * increase wallet value
     */
    private void increaseValueWallet() {
        String costStr = mEditCost.getText().toString();
        int amount;
        try {
            amount = Integer.parseInt(costStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("cost amount error");
            return;
        }
        int code = m1IncValue(block, amount);
        if (code == 0) {
            getBalanceWallet();
        } else {
            showToast("Increase wallet value fail: " + code);
        }
    }

    /**
     * decrease wallet value
     */
    private void decreaseValueWallet() {
        String costStr = mEditCost.getText().toString();
        int amount;
        try {
            amount = Integer.parseInt(costStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("cost amount error");
            return;
        }
        int code = m1DecValue(block, amount);
        if (code == 0) {
            // showToast(R.string.card_wallet_dec_value_success);
            getBalanceWallet();
        } else {
            showToast("Decrease wallet value fail: " + code);
        }
    }

    /** Mifare restore */
    private int restore(int block) {
        MifareClassic mfc = null;
        try {
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return -1;
            }
            mfc = MifareClassic.get(tag);
            if (mfc == null) {
                Log.e(TAG, "get MifareClassic technology failed");
                showToast("get MifareClassic technology failed");
                return -1;
            }
            if (!mfc.isConnected()) {
                mfc.connect();
            }
            if (!m1Auth(mfc, keyType, block, keyBytes)) {
                return -1;
            }
            mfc.restore(block);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(mfc);
        }
        return -1;
    }

    private boolean checkParams() {
        String sectorStr = mEditSector1.getText().toString();
        String keyAStr = mEditKeyA1.getText().toString();
        String keyBStr = mEditKeyB1.getText().toString();
        try {
            sector = Integer.parseInt(sectorStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("sector error");
            mEditSector1.requestFocus();
            return false;
        }
        if (keyAStr.length() == 12) {
            keyType = 0;
            keyBytes = ByteUtil.hexString2Bytes(keyAStr);
        }
        if (keyBStr.length() == 12) {
            keyType = 1;
            keyBytes = ByteUtil.hexString2Bytes(keyBStr);
        }
        if (keyBytes == null) {
            showToast("key error!");
            return false;
        }
        return true;
    }

    private boolean checkWalletParam() {
        String sectorStr = mEditSector2.getText().toString();
        String blockStr = mEditBlock.getText().toString();
        String keyAStr = mEditKeyA2.getText().toString();
        String keyBStr = mEditKeyB2.getText().toString();
        try {
            sector = Integer.parseInt(sectorStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("sector error");
            mEditSector2.requestFocus();
            return false;
        }
        try {
            block = Integer.parseInt(blockStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("block error");
            mEditBlock.requestFocus();
            return false;
        }
        if (keyAStr.length() == 12) {
            keyType = 0;
            keyBytes = ByteUtil.hexString2Bytes(keyAStr);
        }
        if (keyBStr.length() == 12) {
            keyType = 1;
            keyBytes = ByteUtil.hexString2Bytes(keyBStr);
        }
        if (keyBytes == null) {
            showToast("key error!");
            return false;
        }
        // calculate block
        block = sector * 4 + block;
        return true;
    }

    private boolean checkRestoreParam() {
        String sectorStr = mEditSector3.getText().toString();
        String blockStr = mEditBlock3.getText().toString();
        String keyAStr = mEditKeyA3.getText().toString();
        String keyBStr = mEditKeyB3.getText().toString();
        try {
            sector = Integer.parseInt(sectorStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("sector error");
            mEditSector3.requestFocus();
            return false;
        }
        try {
            block = Integer.parseInt(blockStr);
        } catch (Exception e) {
            e.printStackTrace();
            showToast("block error");
            mEditBlock3.requestFocus();
            return false;
        }
        if (keyAStr.length() == 12) {
            keyType = 0;
            keyBytes = ByteUtil.hexString2Bytes(keyAStr);
        }
        if (keyBStr.length() == 12) {
            keyType = 1;
            keyBytes = ByteUtil.hexString2Bytes(keyBStr);
        }
        if (keyBytes == null) {
            showToast("key error!");
            return false;
        }
        // calculate block
        block = sector * 4 + block;
        return true;
    }

    /**
     * init wallet format data
     */
    private byte[] getInitFormatData(int blockIndex) {
        byte[] result = {
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
        };
        result[12] = (byte) (blockIndex & 0xFF);
        result[13] = (byte) ~(blockIndex & 0xFF);
        result[14] = (byte) (blockIndex & 0xFF);
        result[15] = (byte) ~(blockIndex & 0xFF);
        return result;
    }

    /**
     * m1 card auth
     */
    private boolean m1Auth(MifareClassic mfc, int keyType, int block, byte[] keyData) {
        boolean result = false;
        try {
            int sector = mfc.blockToSector(block);
            if (keyType == 0) {
                result = mfc.authenticateSectorWithKeyA(sector, keyData);
            } else if (keyType == 1) {
                result = mfc.authenticateSectorWithKeyB(sector, keyData);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.e(TAG, String.format("mifare auth block %d: %s", block, result ? "success" : "failed"));
        return result;
    }

    /**
     * m1 read block data
     */
    private int m1ReadBlock(int block, byte[] dataOut) {
        MifareClassic mfc = null;
        try {
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return -1;
            }
            mfc = MifareClassic.get(tag);
            if (mfc == null) {
                Log.e(TAG, "get MifareClassic technology failed");
                showToast("get MifareClassic technology failed");
                return -1;
            }
            if (!mfc.isConnected()) {
                mfc.connect();
            }
            if (!m1Auth(mfc, keyType, block, keyBytes)) {
                return -1;
            }
            byte[] data = mfc.readBlock(block);
            int len = Math.min(data.length, dataOut.length);
            System.arraycopy(data, 0, dataOut, 0, Math.min(data.length, dataOut.length));
            return len;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(mfc);
        }
        return -1;
    }

    /**
     * m1 write block data
     */
    private int m1WriteBlock(int block, byte[] dataIn) {
        MifareClassic mfc = null;
        try {
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return -1;
            }
            mfc = MifareClassic.get(tag);
            if (mfc == null) {
                Log.e(TAG, "get MifareClassic technology failed");
                showToast("get MifareClassic technology failed");
                return -1;
            }
            if (!mfc.isConnected()) {
                mfc.connect();
            }
            if (!m1Auth(mfc, keyType, block, keyBytes)) {
                return -1;
            }
            mfc.writeBlock(block, dataIn);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(mfc);
        }
        return -1;
    }

    /**
     * m1 increase value
     */
    private int m1IncValue(int block, int value) {
        MifareClassic mfc = null;
        try {
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return -1;
            }
            mfc = MifareClassic.get(tag);
            if (mfc == null) {
                Log.e(TAG, "get MifareClassic technology failed");
                showToast("get MifareClassic technology failed");
                return -1;
            }
            if (!mfc.isConnected()) {
                mfc.connect();
            }
            if (!m1Auth(mfc, keyType, block, keyBytes)) {
                return -1;
            }
            mfc.increment(block, value);
            mfc.transfer(block);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(mfc);
        }
        return -1;
    }

    /**
     * m1 decrease value
     */
    private int m1DecValue(int block, int value) {
        MifareClassic mfc = null;
        try {
            if (tag == null) {
                Log.e(TAG, "not detect NFC card");
                showToast("not detect NFC card");
                return -1;
            }
            mfc = MifareClassic.get(tag);
            if (mfc == null) {
                Log.e(TAG, "get MifareClassic technology failed");
                showToast("get MifareClassic technology failed");
                return -1;
            }
            if (!mfc.isConnected()) {
                mfc.connect();
            }
            if (!m1Auth(mfc, keyType, block, keyBytes)) {
                return -1;
            }
            mfc.decrement(block, value);
            mfc.transfer(block);
            return 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            IOUtil.close(mfc);
        }
        return -1;
    }

}
