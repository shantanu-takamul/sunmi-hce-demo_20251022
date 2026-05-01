package com.lfi.p3hd.demo.utils;

import android.text.TextUtils;

import java.io.UnsupportedEncodingException;
import java.util.List;

public final class ByteUtil {
    public static final String TAG = "hardware_v3";
    public static final byte[] PREFIX_SEND = ">>".getBytes();
    public static final byte[] PREFIX_RECV = "<<".getBytes();

    private ByteUtil() {
        throw new AssertionError("create instance of ByteUtil is prohibited");
    }

    public static String byte2PrintHex(byte[] raw, int offset, int len) {
        return byte2PrintHex(null, raw, offset, len);
    }

    public static String byte2PrintHex(byte[] prefix, byte[] raw, int offset, int len) {
        int end = offset + len;
        if (raw == null || raw.length == 0 || offset < 0 || len <= 0 || end > raw.length) {
            return "";
        }
        int prefixLen = prefix == null ? 0 : prefix.length;
        byte[] buffer = new byte[prefixLen + len * 3];
        if (prefixLen > 0) {
            System.arraycopy(prefix, 0, buffer, 0, prefixLen);
        }
        for (int i = offset, j = prefixLen; i < end; i++) {
            buffer[j++] = (byte) int2HexChar(raw[i] >> 4 & 0x0f);
            buffer[j++] = (byte) int2HexChar(raw[i] & 0x0f);
            buffer[j++] = ' ';
        }
        return new String(buffer, 0, buffer.length - 1);
    }

    public static int unsignedShort2IntBE(byte[] src, int offset) {
        return (src[offset] & 0xff) << 8 | (src[offset + 1] & 0xff);
    }

    public static int unsignedShort2IntLE(byte[] src, int offset) {
        return (src[offset] & 0xff) | (src[offset + 1] & 0xff) << 8;
    }

    public static int unsignedByte2Int(byte[] src, int offset) {
        return src[offset] & 0xFF;
    }

    public static int unsignedInt2IntLE(byte[] src, int offset) {
        int value = 0;
        for (int i = offset; i < offset + 4; i++) {
            value |= (src[i] & 0xff) << (i - offset) * 8;
        }
        return value;
    }

    public static int unsignedInt2IntBE(byte[] src, int offset) {
        int result = 0;
        for (int i = offset; i < offset + 4; i++) {
            result |= (src[i] & 0xff) << (offset + 3 - i) * 8;
        }
        return result;
    }

    public static byte[] int2BytesLE(int src) {
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) (src >> i * 8);
        }
        return result;
    }

    public static byte[] int2BytesBE(int src) {
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) (src >> (3 - i) * 8);
        }
        return result;
    }

    public static byte[] short2BytesLE(short src) {
        byte[] result = new byte[2];
        for (int i = 0; i < 2; i++) {
            result[i] = (byte) (src >> i * 8);
        }
        return result;
    }

    public static byte[] short2BytesBE(short src) {
        byte[] result = new byte[2];
        for (int i = 0; i < 2; i++) {
            result[i] = (byte) (src >> (1 - i) * 8);
        }
        return result;
    }

    public static byte[] concatByteArrays(byte[]... param) {
        if (param == null || param.length == 0) {
            return new byte[0];
        }
        return concatByteArrays(param, 0, param.length);
    }

    public static byte[] concatByteArrays(List<byte[]> list) {
        if (list == null || list.isEmpty()) {
            return new byte[0];
        }
        byte[][] bArray = new byte[list.size()][];
        list.toArray(bArray);
        return concatByteArrays(bArray, 0, bArray.length);
    }

    public static byte[] concatByteArrays(byte[][] param, int offset, int len) {
        int end = offset + len;
        if (param == null || param.length == 0 || offset < 0 || len < 0 || end > param.length) {
            return new byte[0];
        }
        int totalLen = 0;
        for (int i = offset; i < end; i++) {
            if (param[i] == null) continue;
            totalLen += param[i].length;
        }
        byte[] buffer = new byte[totalLen];
        int index = 0;
        for (int i = offset; i < end; i++) {
            if (param[i] == null) continue;
            System.arraycopy(param[i], 0, buffer, index, param[i].length);
            index += param[i].length;
        }
        return buffer;
    }

    public static String concatStrings(String... src) {
        if (src == null || src.length == 0) {
            return "";
        }
        int totalLen = 0;
        for (String str : src) {
            if (str != null) totalLen += str.length();
        }
        char[] buffer = new char[totalLen];
        int index = 0;
        for (String str : src) {
            if (str == null) continue;
            char[] tmp = str.toCharArray();
            System.arraycopy(tmp, 0, buffer, index, tmp.length);
            index += tmp.length;
        }
        return new String(buffer);
    }

    public static String getHexCmd(int cmd) {
        return bytes2HexString((byte) (cmd >> 8), (byte) cmd);
    }

    public static String getDownloadHexCmd(byte cmd) {
        return bytes2HexString(cmd);
    }

    public static byte genLRC(byte[] data, int offset, int len) {
        int end = offset + len;
        if (data == null || data.length == 0 || offset < 0 || len < 0 || end > data.length) {
            return 0;
        }
        byte lrc = 0;
        for (int i = offset; i < end; i++) {
            lrc ^= data[i];
        }
        return lrc;
    }

    public static String bytes2HexString(byte... src) {
        if (src == null || src.length == 0) {
            return "";
        }
        return bytes2HexString(src, 0, src.length);
    }

    public static String bytes2HexString(byte[] src, int offset, int len) {
        int end = offset + len;
        if (src == null || src.length == 0 || offset < 0 || len < 0 || end > src.length) {
            return "";
        }
        byte[] buffer = new byte[len * 2];
        int h, l;
        for (int i = offset, j = 0; i < end; i++) {
            h = src[i] >> 4 & 0x0f;
            l = src[i] & 0x0f;
            buffer[j++] = (byte) (h > 9 ? h - 10 + 'A' : h + '0');
            buffer[j++] = (byte) (l > 9 ? l - 10 + 'A' : l + '0');
        }
        return new String(buffer);
    }

    public static byte[] hexString2Bytes(String hexStr) {
        if (TextUtils.isEmpty(hexStr)) {
            return new byte[0];
        }
        int length = hexStr.length() / 2;
        char[] chars = hexStr.toCharArray();
        byte[] b = new byte[length];
        for (int i = 0; i < length; i++) {
            b[i] = (byte) (hexChar2Int((byte) chars[i * 2]) << 4 | hexChar2Int((byte) chars[i * 2 + 1]));
        }
        return b;
    }

    public static byte[] string2SPBytes(String src) {
        byte[] in = (src == null ? new byte[0] : src.getBytes());
        byte[] out = new byte[in.length + 1];
        System.arraycopy(in, 0, out, 0, in.length);
        return out;
    }

    public static String spBytes2String(byte[] src) {
        return spBytes2String(src, "UTF-8");
    }

    public static String spBytes2String(byte[] src, String charsetName) {
        if (src == null || src.length == 0) return "";
        int index = src.length - 1;
        while (index >= 0 && src[index] == 0) index--;
        if (index < 0) return "";
        try {
            return new String(src, 0, index + 1, charsetName);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static String spBytes2HexString(byte[] src) {
        if (src == null || src.length == 0) return "";
        int index = src.length - 1;
        while (index >= 0 && src[index] == 0) index--;
        if (index < 0) return "";
        return bytes2HexString(src, 0, index + 1);
    }

    public static byte[] bcd2Ascii(byte[] src) {
        if (src == null || src.length == 0) return null;
        byte[] result = new byte[src.length * 2];
        for (int i = 0; i < src.length; i++) {
            result[i * 2] = (byte) int2HexChar(src[i] >> 4 & 0x0f);
            result[i * 2 + 1] = (byte) int2HexChar(src[i] & 0x0f);
        }
        return result;
    }

    public static byte[] ascii2bcd(byte[] src) {
        byte[] result = new byte[src.length / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (hexChar2Int(src[i * 2]) << 4 | hexChar2Int(src[i * 2 + 1]));
        }
        return result;
    }

    public static byte[] asciiStr2Bytes(String ascii) {
        byte[] dat = null;
        try {
            dat = ascii.getBytes("ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return dat;
    }

    public static String hexStr2AsciiStr(String hex) {
        String rec = null;
        try {
            rec = new String(ByteUtil.hexString2Bytes(hex), "ISO-8859-1");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return rec;
    }

    public static char int2HexChar(int value) {
        if (value > 9) {
            return (char) (value - 10 + 'A');
        }
        return (char) (value + '0');
    }

    public static int hexChar2Int(byte c) {
        if (c >= 'a') return (c - 'a' + 10) & 0x0f;
        if (c >= 'A') return (c - 'A' + 10) & 0x0f;
        return (c - '0') & 0x0f;
    }
}
