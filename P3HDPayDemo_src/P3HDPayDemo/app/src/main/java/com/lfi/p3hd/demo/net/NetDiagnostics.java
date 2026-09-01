package com.lfi.p3hd.demo.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * Turns a network failure into a sentence an operator can act on.
 *
 * <h3>Why this is worth a class of its own</h3>
 * Inside CB there is no adb and no logcat — engineers reach the terminal through a
 * VDI session and read the screen. A failure that shows up as "Network error" costs a
 * site visit; the same failure named as "the host did not resolve" versus "nothing
 * answered on port 443" points at DNS or at the firewall, and those are different
 * teams.
 *
 * The distinction that matters most on the CB network, verified 2026-09-01:
 * {@code bootstrap-api.rcbdc.digitaldirham.gov.ae} resolves to 10.40.44.140 from the
 * corporate network, but :443 is filtered — packets are dropped, not refused. So the
 * two failures an operator will actually hit look identical from the app's point of
 * view unless they are told apart deliberately:
 *
 * <ul>
 *   <li>name does not resolve → DNS, or the terminal is on the wrong network entirely
 *   <li>name resolves, connect times out → firewall between the POS subnet and the VIP
 * </ul>
 *
 * Those must never share a message. The rest of the classification exists for the same
 * reason: an expired certificate and an untrusted CA both surface as
 * {@link SSLHandshakeException}, and the fix for one (set the clock) has nothing to do
 * with the fix for the other (ship the CA).
 */
public final class NetDiagnostics {

    private NetDiagnostics() {}

    /**
     * A one-line classification of a transport failure.
     *
     * The cause chain is walked rather than the top-level type inspected, because the
     * informative exception is almost always wrapped: a trust failure arrives as an
     * SSLHandshakeException whose cause, three levels down, is a
     * CertPathValidatorException.
     */
    public static String classify(IOException e) {
        if (e == null) return "unknown failure";

        if (hasCause(e, UnknownHostException.class)) {
            return "no route: the host name did not resolve"
                + " — check DNS, Private DNS is off, and that this terminal is on the CB network";
        }
        if (hasCause(e, CertificateExpiredException.class)
                || hasCause(e, CertificateNotYetValidException.class)) {
            return "TLS: certificate expired or the device clock is wrong"
                + " — check the date and time on this terminal first";
        }
        if (hasCause(e, CertPathValidatorException.class)) {
            return "TLS: trust anchor not found"
                + " — this build does not trust the CA that signed the server";
        }
        if (hasCause(e, SSLPeerUnverifiedException.class)) {
            return "TLS: the certificate does not match the host name in the base URL";
        }
        if (hasCause(e, SSLHandshakeException.class)) {
            return "TLS handshake failed — protocol or cipher mismatch, or a non-TLS listener";
        }
        if (hasCause(e, SSLException.class)) {
            return "TLS error: " + shortMessage(e);
        }
        if (hasCause(e, ConnectException.class)) {
            // Refused is an answer: something is listening logic exists, it just said no.
            return "connection refused: the host resolved but nothing is listening on that port";
        }
        if (hasCause(e, NoRouteToHostException.class)) {
            return "no route to host: the address resolved but is unreachable from this network";
        }
        if (hasCause(e, SocketTimeoutException.class) || hasCause(e, InterruptedIOException.class)) {
            // Distinct from the DNS message above on purpose — see the class comment.
            return "connect timed out: the host resolved but nothing answered"
                + " — port 443 is most likely filtered between this subnet and the gateway";
        }
        return shortMessage(e);
    }

    /** A one-line classification of an unhelpful HTTP status. */
    public static String classifyHttp(int code) {
        if (code == 401) {
            return "HTTP 401 — the API key was rejected (wrong key, wrong slot, expired, or rotated)";
        }
        if (code == 403) {
            return "HTTP 403 — authorised but refused; the wallet may not belong to this LFI";
        }
        if (code >= 500) {
            return "HTTP " + code + " — the gateway is failing, not the terminal";
        }
        if (code >= 300 && code < 400) {
            return "HTTP " + code + " — an intermediary redirected the call";
        }
        return "HTTP " + code;
    }

    /**
     * The transport the device is actually using.
     *
     * Worth showing next to a connection result because of one specific trap: a
     * terminal with a SIM keeps a default route over mobile data, so it can look
     * online while every request to an internal-only host leaves via the wrong
     * interface and dies. "Connected, transport: cellular" explains a failure that
     * otherwise makes no sense.
     */
    public static String activeTransport(Context context) {
        try {
            ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network active = cm.getActiveNetwork();
                if (active == null) return "offline";
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps == null) return "unknown";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     return "Wi-Fi";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "ethernet";
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))      return "VPN";
                return "other";
            }

            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "offline";
            return info.getTypeName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Device clock minus the server's {@code Date} header, in seconds.
     *
     * The gateway's QR TTL is 180 s and its cancel window about 10 s, both compared
     * against timestamps the server issues, and there is no NTP configured anywhere in
     * the on-prem estate. A terminal minutes out of step produces QRs that look expired
     * on arrival and cancels that miss — failures that read as backend faults.
     */
    public static long clockSkewSeconds(long serverDateMillis) {
        return (System.currentTimeMillis() - serverDateMillis) / 1000L;
    }

    /** Formats a skew for display, naming the direction rather than a bare sign. */
    public static String describeSkew(long skewSeconds) {
        long abs = Math.abs(skewSeconds);
        if (abs <= 2)  return "in step with the server";
        String direction = skewSeconds > 0 ? "ahead of" : "behind";
        if (abs < 120) return abs + "s " + direction + " the server";
        return (abs / 60) + "m " + direction + " the server"
            + (abs > 300 ? " — outside the ±5 min tolerance, fix the clock" : "");
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static String shortMessage(Throwable t) {
        String msg = t.getMessage();
        String name = t.getClass().getSimpleName();
        if (msg == null || msg.isEmpty()) return name;
        return msg.length() > 120 ? name + ": " + msg.substring(0, 120) + "…" : name + ": " + msg;
    }
}
