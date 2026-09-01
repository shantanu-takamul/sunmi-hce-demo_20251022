package com.lfi.p3hd.demo;

import android.app.Application;
import android.util.Log;

import com.lfi.p3hd.demo.net.HttpClients;
import com.lfi.p3hd.demo.net.TrustStore;
import com.lfi.p3hd.demo.utils.ApiKeyManager;
import com.sunmi.pay.hardware.aidlv2.print.PrinterOptV2;
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2;
import com.sunmi.pay.hardware.wrapper.HCEManagerV2Wrapper;

import sunmi.paylib.SunmiPayKernel;

public class MyApplication extends Application {
    private static final String TAG = "MyApplication";
    public static MyApplication app;

    public HCEManagerV2Wrapper hceManagerV2;
    public ReadCardOptV2 readCardOptV2;
    public PrinterOptV2 printerOptV2;
    private boolean connectPaySDK;

    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        // Before anything can make a request: tell HttpClients where an
        // operator-imported CA override lives. Without this the on-prem clients see
        // only the anchor bundled in the APK.
        HttpClients.setTrustProvider(TrustStore::operatorOverrideFor);
        // Fetch-if-absent: an on-prem terminal handed over with portal credentials
        // already configured should be able to take its first sale, rather than
        // failing it and sending someone into Settings. Refuses in every other case,
        // including when a key is already stored — see mintOnStartupIfNeeded.
        ApiKeyManager.get().mintOnStartupIfNeeded();
        bindPaySDKService();
    }

    public boolean isConnectPaySDK() {
        return connectPaySDK;
    }

    public void bindPaySDKService() {
        final SunmiPayKernel payKernel = SunmiPayKernel.getInstance();
        payKernel.initPaySDK(this, new SunmiPayKernel.ConnectCallback() {
            @Override
            public void onConnectPaySDK() {
                Log.d(TAG, "onConnectPaySDK");
                hceManagerV2 = payKernel.mHCEManagerV2Wrapper;
                readCardOptV2 = payKernel.mReadCardOptV2;
                printerOptV2 = payKernel.mPrinterOptV2;
                connectPaySDK = true;
            }

            @Override
            public void onDisconnectPaySDK() {
                Log.d(TAG, "onDisconnectPaySDK");
                connectPaySDK = false;
                hceManagerV2 = null;
                readCardOptV2 = null;
                printerOptV2 = null;
            }
        });
    }
}
