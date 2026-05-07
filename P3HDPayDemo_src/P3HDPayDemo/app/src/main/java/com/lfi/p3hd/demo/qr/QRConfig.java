package com.lfi.p3hd.demo.qr;

import com.lfi.p3hd.demo.utils.PreferencesUtil;

public class QRConfig {
    // Non-env-specific constants
    public static final String QR_TYPE                = "DYNAMIC";
    public static final String CURRENCY               = "AED";
    public static final String TERMINAL_ID            = "TERM001";
    public static final String TRADING_LICENSE_NUMBER = "TL-123456789";
    public static final String MERCHANT_CATEGORY_CODE = "5812";

    public static final long QR_TIMEOUT_MS    = 300_000L;
    public static final long POLL_INTERVAL_MS = 5_000L;

    // Endpoints (no LFI ID embedded)
    public static final String QR_ENDPOINT        = "/lfi-gateway/api/v1/qr/generate";
    public static final String QR_STATUS_ENDPOINT = "/lfi-gateway/api/v1/transactions/history";
    public static final String RETURN_ENDPOINT    = "/lfi-gateway/api/v1/transactions/return/";
    public static final String AUTH_ENDPOINT       = "/web/api/v1/auth/login";

    // -------------------------------------------------------------------------
    // Per-environment values
    // -------------------------------------------------------------------------

    public static String getXLfiId() {
        return getXLfiId(PreferencesUtil.getEnv());
    }

    public static String getXLfiId(String env) {
        if ("staging".equals(env)) return "acq-NEOPAY";
        if ("demo".equals(env))    return "acq-NEOPAY";
        return "lfi-ADCB";
    }

    public static String getDefaultWalletId() {
        return getDefaultWalletId(PreferencesUtil.getEnv());
    }

    public static String getDefaultWalletId(String env) {
        if ("staging".equals(env)) return "NEOP979F8901FC";
        if ("demo".equals(env))    return "NEOP15B3159B17";
        return "ADCB1920276ECD";
    }

    /** Regen endpoint embeds the LFI ID. */
    public static String getRegenEndpoint() {
        return "/web/api/v1/lfis/" + getXLfiId() + "/inbound-api-config/regenerate";
    }

    public static String getBaseUrl() {
        switch (PreferencesUtil.getEnv()) {
            case "dev":   return "https://mithril-dev-backend.takamul.cc";
            case "qa":
            case "test":  return "https://mithril-qa-backend.takamul.cc";
            case "local": return "http://localhost:3000";
            case "demo":  return "https://mithril-demo-backend.takamul.cc";
            case "staging":
            default:      return "https://mithril-staging-backend.takamul.cc";
        }
    }
}
