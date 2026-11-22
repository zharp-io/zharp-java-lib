package io.zharp.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Values that never leave the process, with the same rules as the Node and browser SDKs
 * (@zharp/internal/bodies.ts and headers.ts): a key is secret when, compared without case, `-`
 * or `_`, it contains one of {@link #CONTAINS} or is one of {@link #EXACT}; a secret's value is
 * sent as {@code [Filtered]}.
 */
public final class Secrets {
    public static final String FILTERED = "[Filtered]";

    private static final List<String> CONTAINS = Arrays.asList(
            "password", "passwd", "passphrase", "secret", "token", "apikey", "accesskey", "privatekey",
            "authorization", "cookie", "creditcard", "cardnumber", "cvv", "cvc", "ssn", "iban", "sessionid",
            "signature", "credential");
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList("auth", "pin", "pwd", "otp", "card", "pan", "jwt", "sid", "codeverifier"));
    /** Also secret in a query string, where they are API keys and signed-URL signatures. */
    private static final Set<String> URL_EXACT = new HashSet<>(Arrays.asList("key", "sig", "code", "state", "session"));
    /** Headers that are secret whatever the key rules say. */
    private static final Set<String> SECRET_HEADERS = new HashSet<>(Arrays.asList(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token"));

    private Secrets() {}

    static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static boolean isSecretKey(String key, List<String> extra) {
        String k = normalize(key);
        if (k.isEmpty()) return false;
        if (EXACT.contains(k)) return true;
        for (String c : CONTAINS) {
            if (k.contains(c)) return true;
        }
        if (extra != null) {
            for (String e : extra) {
                if (normalize(e).equals(k)) return true;
            }
        }
        return false;
    }

    public static boolean isSecretHeader(String lowerName, List<String> extra) {
        return SECRET_HEADERS.contains(lowerName) || isSecretKey(lowerName, extra);
    }

    /** A query string (without `?`) with secret-looking values filtered. */
    public static String redactQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        String[] pairs = query.split("&", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                try {
                    key = java.net.URLDecoder.decode(key, "UTF-8");
                } catch (Exception ignored) {
                    // As written.
                }
                boolean secret = isSecretKey(key, null) || URL_EXACT.contains(normalize(key));
                if (secret && eq < pair.length() - 1) pair = pair.substring(0, eq) + "=" + FILTERED;
            }
            if (i > 0) out.append('&');
            out.append(pair);
        }
        return out.toString();
    }

    /** A URL with secret query values filtered and {@code user:password@} dropped. */
    public static String redactUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        String out = url.replaceFirst("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@]*@", "$1");
        int q = out.indexOf('?');
        if (q < 0) return out;
        int hash = out.indexOf('#', q);
        String query = hash < 0 ? out.substring(q + 1) : out.substring(q + 1, hash);
        String redacted = redactQuery(query);
        if (!redacted.equals(query)) out = out.substring(0, q + 1) + redacted + (hash < 0 ? "" : out.substring(hash));
        return out;
    }
}
