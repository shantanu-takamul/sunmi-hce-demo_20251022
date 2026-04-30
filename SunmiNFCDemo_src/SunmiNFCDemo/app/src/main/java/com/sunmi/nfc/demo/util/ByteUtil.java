package com.sunmi.nfc.demo.util;

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

    /**
     * 将字节数组转换成16进制可打印内容(相邻字节间有空格)
     *
     * @param raw    源字节数组
     * @param offset 偏移量
     * @param len    数据长度
     * @return 转换后的字符串
     */
    public static String byte2PrintHex(byte[] raw, int offset, int len) {
        return byte2PrintHex(null, raw, offset, len);
    }

    /**
     * 将字节数组转换成16进制可打印内容(相邻字节间有空格)
     *
     * @param prefix 前缀
     * @param raw    源字节数组
     * @param offset 偏移量
     * @param len    数据长度
     * @return 转换后的字符串
     */
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

    /**
     * 将无符号short转换成int，大端模式(高位在前)
     */
    public static int unsignedShort2IntBE(byte[] src, int offset) {
        return (src[offset] & 0xff) << 8 | (src[offset + 1] & 0xff);
    }

    /**
     * 将无符号short转换成int，小端模式(低位在前)
     */
    public static int unsignedShort2IntLE(byte[] src, int offset) {
        return (src[offset] & 0xff) | (src[offset + 1] & 0xff) << 8;
    }

    /**
     * 将无符号byte转换成int
     */
    public static int unsignedByte2Int(byte[] src, int offset) {
        return src[offset] & 0xFF;
    }

    /**
     * 将字节数组转换成int,小端模式(低位在前)
     */
    public static int unsignedInt2IntLE(byte[] src, int offset) {
        int value = 0;
        for (int i = offset; i < offset + 4; i++) {
            value |= (src[i] & 0xff) << (i - offset) * 8;
        }
        return value;
    }

    /**
     * 将字节数组转换成int,大端模式(高位在前)
     */
    public static int unsignedInt2IntBE(byte[] src, int offset) {
        int result = 0;
        for (int i = offset; i < offset + 4; i++) {
            result |= (src[i] & 0xff) << (offset + 3 - i) * 8;
        }
        return result;
    }

    /**
     * 将int转换成byte数组，小端模式(低位在前)
     */
    public static byte[] int2BytesLE(int src) {
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) (src >> i * 8);
        }
        return result;
    }

    /**
     * 将int转换成byte数组，大端模式(高位在前)
     */
    public static byte[] int2BytesBE(int src) {
        byte[] result = new byte[4];
        for (int i = 0; i < 4; i++) {
            result[i] = (byte) (src >> (3 - i) * 8);
        }
        return result;
    }

    /**
     * 将short转换成byte数组，小端模式(低位在前)
     */
    public static byte[] short2BytesLE(short src) {
        byte[] result = new byte[2];
        for (int i = 0; i < 2; i++) {
            result[i] = (byte) (src >> i * 8);
        }
        return result;
    }

    /**
     * 将short转换成byte数组，大端模式(高位在前)
     */
    public static byte[] short2BytesBE(short src) {
        byte[] result = new byte[2];
        for (int i = 0; i < 2; i++) {
            result[i] = (byte) (src >> (1 - i) * 8);
        }
        return result;
    }

    /**
     * 将字节数组列表合并成单个字节数组
     *
     * @param param 字节数组列表
     * @return 合并后的字节数组
     */
    public static byte[] concatByteArrays(byte[]... param) {
        if (param == null || param.length == 0) {
            return new byte[0];
        }
        return concatByteArrays(param, 0, param.length);
    }

    /**
     * 将字节数组列表合并成单个字节数组
     *
     * @param list 字节数组列表
     * @return 合并后的字节数组
     */
    public static byte[] concatByteArrays(List<byte[]> list) {
        if (list == null || list.isEmpty()) {
            return new byte[0];
        }
        byte[][] bArray = new byte[list.size()][];
        list.toArray(bArray);
        return concatByteArrays(bArray, 0, bArray.length);
    }

    /**
     * 将字节数组列表合并成单个字节数组
     *
     * @param param  字节数组列表
     * @param offset 偏移量
     * @param len    长度
     * @return 合并后的字节数组
     */
    public static byte[] concatByteArrays(byte[][] param, int offset, int len) {
        int end = offset + len;
        if (param == null || param.length == 0 || offset < 0 || len < 0 || end > param.length) {
            return new byte[0];
        }
        int totalLen = 0;
        for (int i = offset; i < end; i++) {
            if (param[i] == null) {
                continue;
            }
            totalLen += param[i].length;
        }
        byte[] buffer = new byte[totalLen];
        int index = 0;
        for (int i = offset; i < end; i++) {
            if (param[i] == null) {
                continue;
            }
            System.arraycopy(param[i], 0, buffer, index, param[i].length);
            index += param[i].length;
        }
        return buffer;
    }

    /**
     * 拼接字符串
     *
     * @param src 源字符串数组
     * @return 拼接后的字符串数组
     */
    public static String concatStrings(String... src) {
        if (src == null || src.length == 0) {
            return "";
        }
        int totalLen = 0;
        for (String str : src) {
            if (str == null) {
                continue;
            }
            totalLen += str.length();
        }
        char[] buffer = new char[totalLen];
        int index = 0;
        char[] tmp = null;
        for (String str : src) {
            if (str == null) {
                continue;
            }
            tmp = str.toCharArray();
            System.arraycopy(tmp, 0, buffer, index, tmp.length);
            index += tmp.length;
        }
        return new String(buffer);
    }

    /**
     * 将命令码转换成16进制字符串(4位)
     */
    public static String getHexCmd(int cmd) {
        return bytes2HexString((byte) (cmd >> 8), (byte) cmd);
    }

    /**
     * 将命令码转换成16进制字符串(2位)
     */
    public static String getDownloadHexCmd(byte cmd) {
        return bytes2HexString(cmd);
    }

    /**
     * 获取LRC
     *
     * @param data   数据区
     * @param offset 偏移量
     * @param len    长度
     * @return LRC值，参数非法时返回0
     */
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

    /**
     * 将字节数组转换成16进制字符串
     *
     * @param src 源字节数组
     * @return 转换后的16进制字符串
     */
    public static String bytes2HexString(byte... src) {
        if (src == null || src.length == 0) {
            return "";
        }
        return bytes2HexString(src, 0, src.length);
    }

    /**
     * 将字节数组转换成16进制字符串
     *
     * @param src    源字节数组
     * @param offset 偏移量
     * @param len    数据长度
     * @return 转换后的16进制字符串
     */
    public static String bytes2HexString(byte[] src, int offset, int len) {
        int end = offset + len;
        if (src == null || src.length == 0 || offset < 0 || len < 0 || end > src.length) {
            return "";
        }
        byte[] buffer = new byte[len * 2];
        int h = 0, l = 0;
        for (int i = offset, j = 0; i < end; i++) {
            h = src[i] >> 4 & 0x0f;
            l = src[i] & 0x0f;
            buffer[j++] = (byte) (h > 9 ? h - 10 + 'A' : h + '0');
            buffer[j++] = (byte) (l > 9 ? l - 10 + 'A' : l + '0');
        }
        return new String(buffer);
    }

    /**
     * Hex字符串转成字节数组
     *
     * @param hexStr Hex字符串
     * @return 转换后的字节数组
     */
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

    /**
     * 将String转换为SP字节数组，增加结尾\0符
     *
     * @param src 源字符串
     * @return 转换后的字节数组
     */
    public static byte[] string2SPBytes(String src) {
        byte[] in = (src == null ? new byte[0] : src.getBytes());
        byte[] out = new byte[in.length + 1];//增加一个结尾符字节
        System.arraycopy(in, 0, out, 0, in.length);
        return out;
    }

    /**
     * 将SP字节数组转换成ASCII字符串
     *
     * @param src 源字节数组，以'\0'结尾
     * @return 转换后的字符串
     */
    public static String spBytes2String(byte[] src) {
        return spBytes2String(src, "UTF-8");
    }

    /**
     * 将SP字节数组转换成指定字符集的字符串
     *
     * @param src         源字节数组，以'\0'结尾
     * @param charsetName 字符集字符串， 如 "ISO-8859-5"
     * @return 转换后的字符串
     */
    public static String spBytes2String(byte[] src, String charsetName) {
        if (src == null || src.length == 0) {
            return "";
        }
        int index = src.length - 1;
        while (index >= 0 && src[index] == 0) {
            index--;
        }
        if (index < 0) {//所有字节全为0
            return "";
        }
        try {
            return new String(src, 0, index + 1, charsetName);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return "";
    }

    /**
     * 将SP字节数组转换成Hex字符串
     *
     * @param src 源字节数组，以'\0'结尾
     * @return 转换后的字符串
     */
    public static String spBytes2HexString(byte[] src) {
        if (src == null || src.length == 0) {
            return "";
        }
        int index = src.length - 1;
        while (index >= 0 && src[index] == 0) {
            index--;
        }
        if (index < 0) {//所有字节全为0
            return "";
        }
        return bytes2HexString(src, 0, index + 1);
    }

    /** 将BCD字节数据转换为ASCII字节数组 */
    public static byte[] bcd2Ascii(byte[] src) {
        if (src == null || src.length == 0) {
            return null;
        }
        byte[] result = new byte[src.length * 2];
        for (int i = 0; i < src.length; i++) {
            result[i * 2] = (byte) int2HexChar(src[i] >> 4 & 0x0f);
            result[i * 2 + 1] = (byte) int2HexChar(src[i] & 0x0f);
        }
        return result;
    }

    /** 将ASCII数组转换为BCD数组 */
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

    /** Int值转成16进制字符 */
    public static char int2HexChar(int value) {
        if (value > 9) {
            return (char) (value - 10 + 'A');
        }
        return (char) (value + '0');
    }

    /** 16进制字符转成Int值 */
    public static int hexChar2Int(byte c) {
        if (c >= 'a') {
            return (c - 'a' + 10) & 0x0f;
        }
        if (c >= 'A') {
            return (c - 'A' + 10) & 0x0f;
        }
        return (c - '0') & 0x0f;
    }

}
