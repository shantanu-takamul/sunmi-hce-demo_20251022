package com.sunmi.hce.demo.util;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.sunmi.hce.demo.MyApplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Utility {
    private Utility() {
        throw new AssertionError("Create instance of Utility is forbidden.");
    }

    /** Bundle对象转换成字符串 */
    public static String bundle2String(Bundle bundle) {
        return bundle2String(bundle, 1);
    }

    /**
     * 根据key排序后将Bundle内容拼接成字符串
     *
     * @param bundle 要处理的bundle
     * @param order  排序规则，0-不排序，1-升序，2-降序
     * @return 拼接后的字符串
     */
    public static String bundle2String(Bundle bundle, int order) {
        if (bundle == null || bundle.keySet().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<String> list = new ArrayList<>(bundle.keySet());
        if (order == 1) { //升序
            Collections.sort(list, String::compareTo);
        } else if (order == 2) {//降序
            Collections.sort(list, Collections.reverseOrder());
        }
        for (String key : list) {
            sb.append(key);
            sb.append(":");
            Object value = bundle.get(key);
            if (value instanceof byte[]) {
                sb.append(ByteUtil.bytes2HexStr((byte[]) value));
            } else {
                sb.append(value);
            }
            sb.append("\n");
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 将null转换成空串 */
    public static String null2String(String str) {
        return str == null ? "" : str;
    }

    public static String formatStr(String format, Object... params) {
        return String.format(Locale.ENGLISH, format, params);
    }

    /** check whether src is hex format */
    public static boolean checkHexValue(String src) {
        return Pattern.matches("[0-9a-fA-F]+", src);
    }

    /** 显示Toast */
    public static void showToast(final String msg) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> Toast.makeText(MyApplication.app, msg, Toast.LENGTH_SHORT).show());
    }

    /** 显示Toast */
    public static void showToast(int resId) {
        showToast(MyApplication.app.getString(resId));
    }

    /** 根据结果码获取成功失败信息 */
    public static String getStateString(int code) {
        return code == 0 ? "success" : "failed, code:" + code;
    }

    /** 根据结果状态获取成功失败信息 */
    public static String getStateString(boolean state) {
        return state ? "success" : "failed";
    }
}
