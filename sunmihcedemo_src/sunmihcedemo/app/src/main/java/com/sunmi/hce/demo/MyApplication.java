package com.sunmi.hce.demo;

import android.app.Application;
import android.util.Log;

import com.sunmi.hce.demo.util.Utility;
import com.sunmi.pay.hardware.wrapper.HCEManagerV2Wrapper;

import sunmi.paylib.SunmiPayKernel;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";
    public static MyApplication app;

    public HCEManagerV2Wrapper hceManagerV2;               // HCE操作模块
    private boolean connectPaySDK;//是否已连接PaySDK

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        bindPaySDKService();
    }

    public boolean isConnectPaySDK() {
        return connectPaySDK;
    }

    /** bind PaySDK service */
    public void bindPaySDKService() {
        final SunmiPayKernel payKernel = SunmiPayKernel.getInstance();
        payKernel.initPaySDK(this, new SunmiPayKernel.ConnectCallback() {
            @Override
            public void onConnectPaySDK() {
                Log.e(TAG, "onConnectPaySDK...");
                hceManagerV2 = payKernel.mHCEManagerV2Wrapper;
                connectPaySDK = true;
            }

            @Override
            public void onDisconnectPaySDK() {
                Log.e(TAG, "onDisconnectPaySDK...");
                connectPaySDK = false;
                hceManagerV2 = null;
                Utility.showToast("connect SDK failed");
            }
        });
    }
}
