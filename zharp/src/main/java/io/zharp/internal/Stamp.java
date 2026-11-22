package io.zharp.internal;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.util.Map;

/**
 * The request's ids at the end of each log line written during it — {@code … trace_id=<32 hex>
 * span_id=<16 hex>} — so the Zharp agent, collecting the app's output, links the line to its
 * request (the same stamp as the Node, Python and Go SDKs). A record that spans many lines (a
 * stack trace) gets one stamp, at its end; a JSON line gets two fields instead.
 */
public final class Stamp {
    private Stamp() {}

    /** The ids from a logging event's MDC / context data, else the current span's. Null outside a span. */
    public static String[] ids(Map<String, ?> mdc) {
        if (mdc != null) {
            Object t = mdc.get("trace_id");
            Object s = mdc.get("span_id");
            if (t instanceof String && s instanceof String && ((String) t).length() == 32 && ((String) s).length() == 16) {
                return new String[] {(String) t, (String) s};
            }
        }
        try {
            SpanContext sc = Span.current().getSpanContext();
            if (sc.isValid()) return new String[] {sc.getTraceId(), sc.getSpanId()};
        } catch (Throwable ignored) {
            // No OpenTelemetry API here.
        }
        return null;
    }

    public static String stamp(String text, String[] ids) {
        if (text == null || ids == null || text.isEmpty()) return text;
        try {
            return stamp(text, ids[0], ids[1]);
        } catch (RuntimeException e) {
            return text;
        }
    }

    public static String stamp(String text, String traceId, String spanId) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        String body = text.substring(0, end);
        String tail = text.substring(end);
        int trimmed = body.length();
        while (trimmed > 0 && Character.isWhitespace(body.charAt(trimmed - 1))) trimmed--;
        String core = body.substring(0, trimmed);
        if (core.trim().isEmpty() || body.contains("trace_id") || body.contains("[Zharp]")) return text;
        if (core.endsWith("}") && core.trim().startsWith("{") && core.indexOf('\n') < 0) {
            int close = core.lastIndexOf('}');
            String inner = core.substring(core.indexOf('{') + 1, close).trim();
            String fields = "\"trace_id\":\"" + traceId + "\",\"span_id\":\"" + spanId + "\"";
            return core.substring(0, close) + (inner.isEmpty() ? "" : ",") + fields + "}" + body.substring(trimmed) + tail;
        }
        return core + " trace_id=" + traceId + " span_id=" + spanId + body.substring(trimmed) + tail;
    }

    /** Stamp a buffer in place (Log4j 2 lays events out into a StringBuilder). */
    public static void stamp(StringBuilder text, String[] ids) {
        if (text == null || ids == null || text.length() == 0) return;
        try {
            String before = text.toString();
            String after = stamp(before, ids[0], ids[1]);
            if (!after.equals(before)) {
                text.setLength(0);
                text.append(after);
            }
        } catch (RuntimeException ignored) {
            // An unstamped line is better than a lost one.
        }
    }
}
