package com.lfi.p3hd.demo.qr;

import com.lfi.p3hd.demo.utils.PreferencesUtil;

public class QRConfig {
    public static final String X_LFI_ID               = "lfi-ADCB";
    public static final String QR_TYPE                 = "DYNAMIC";
    public static final String WALLET_ID               = "ADCBCA87DA2BF8";
    public static final String CURRENCY                = "AED";
    public static final String TERMINAL_ID             = "TERM001";
    public static final String TRADING_LICENSE_NUMBER  = "TL-123456789";
    public static final String MERCHANT_CATEGORY_CODE  = "5812";

    public static final long QR_TIMEOUT_MS    = 300_000L;
    public static final long POLL_INTERVAL_MS = 5_000L;

    public static final String QR_ENDPOINT        = "/lfi-gateway/api/v1/qr/generate";
    public static final String QR_STATUS_ENDPOINT = "/lfi-gateway/api/v1/transactions/history";
    public static final String AUTH_ENDPOINT       = "/web/api/v1/auth/login";
    public static final String REGEN_ENDPOINT      = "/web/api/v1/lfis/lfi-ADCB/inbound-api-config/regenerate";

    public static String getBaseUrl() {
        switch (PreferencesUtil.getEnv()) {
            case "dev":  return "https://mithril-dev-backend.takamul.cc";
            case "qa":
            case "test": return "https://mithril-qa-backend.takamul.cc";
            case "local": return "http://localhost:3000";
            case "demo": return "https://mithril-demo-backend.takamul.cc";
            case "staging":
            default:     return "https://mithril-staging-backend.takamul.cc";
        }
    }
}
