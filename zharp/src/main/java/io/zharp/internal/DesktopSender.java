package io.zharp.internal;

import io.opentelemetry.exporter.internal.marshal.Marshaler;
import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler;
import io.opentelemetry.exporter.internal.otlp.traces.TraceRequestMarshaler;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

/**
 * How a desktop app sends: each batch is encoded with OpenTelemetry's own OTLP encoder, gzipped,
 * written to the {@link Outbox} first, then POSTed — and deleted once Zharp has it (or turned it
 * down for good). What couldn't be sent stays for the next launch, which sends it in the
 * background before anything else.
 */
final class DesktopSender {
    static final int TIMEOUT_MILLIS = 10_000;

    enum Outcome { DELIVERED, DROPPED, FAILED }

    private final String endpoint;
    private final String key;
    private final Outbox outbox;
    private final AtomicBoolean draining = new AtomicBoolean();
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zharp-outbox");
        t.setDaemon(true);
        return t;
    });

    DesktopSender(String endpoint, String key, Outbox outbox) {
        this.endpoint = endpoint;
        this.key = key;
        this.outbox = outbox;
    }

    CompletableResultCode export(String signal, Marshaler marshaler) {
        byte[] body;
        try {
            body = gzip(marshaler);
        } catch (Exception e) {
            return CompletableResultCode.ofFailure();
        }
        File saved = outbox.save(signal, body);
        Outcome outcome = send(signal, body, TIMEOUT_MILLIS);
        if (outcome == Outcome.FAILED) return CompletableResultCode.ofFailure();
        outbox.delete(saved);
        drainLater();
        return CompletableResultCode.ofSuccess();
    }

    /** Send what the outbox holds, in the background, unless that's already happening. */
    void drainLater() {
        try {
            if (outbox.isEmpty()) return;
            background.execute(this::drain);
        } catch (Exception ignored) {
            // Shutting down.
        }
    }

    /** Oldest first, on this thread; stops at the first failure (still offline). */
    boolean drain() {
        if (!draining.compareAndSet(false, true)) return false;
        try {
            for (File file : outbox.pending()) {
                String signal = Outbox.signalOf(file);
                if (signal == null) continue;
                byte[] body;
                try {
                    body = Files.readAllBytes(file.toPath());
                } catch (Exception e) {
                    outbox.delete(file);
                    continue;
                }
                if (send(signal, body, TIMEOUT_MILLIS) == Outcome.FAILED) return false;
                outbox.delete(file);
            }
            return true;
        } finally {
            draining.set(false);
        }
    }

    /** One POST: delivered (2xx), dropped (Zharp refused it for good: 4xx but 408/429), or failed (try again later). */
    Outcome send(String signal, byte[] gzipped, int timeoutMillis) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint + "/v1/" + signal).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(timeoutMillis);
            c.setReadTimeout(timeoutMillis);
            c.setRequestProperty("Content-Type", "application/x-protobuf");
            c.setRequestProperty("Content-Encoding", "gzip");
            c.setRequestProperty("User-Agent", "zharp-java/" + Version.VERSION);
            if (key != null) c.setRequestProperty("Authorization", "Bearer " + key);
            c.setFixedLengthStreamingMode(gzipped.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(gzipped);
            }
            int status = c.getResponseCode();
            consume(status < 400 ? c.getInputStream() : c.getErrorStream());
            if (status >= 200 && status < 300) return Outcome.DELIVERED;
            if (status == 408 || status == 429 || status >= 500) return Outcome.FAILED;
            if (status == 401 || status == 403) ZharpCore.announce("Zharp refused the key (HTTP " + status + "): check ZHARP_KEY.");
            return Outcome.DROPPED;
        } catch (Exception offline) {
            return Outcome.FAILED;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    void shutdown() {
        background.shutdown();
    }

    private static void consume(InputStream in) {
        if (in == null) return;
        try (InputStream s = in) {
            byte[] buffer = new byte[1024];
            while (s.read(buffer) != -1) {
                // Drained so the connection can be reused.
            }
        } catch (Exception ignored) {
            // Nothing to read.
        }
    }

    private static byte[] gzip(Marshaler marshaler) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(marshaler.getBinarySerializedSize() / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            marshaler.writeBinaryTo(gz);
        }
        return bytes.toByteArray();
    }

    static final class Logs implements LogRecordExporter {
        private final DesktopSender sender;

        Logs(DesktopSender sender) {
            this.sender = sender;
        }

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            return sender.export(Outbox.LOGS, LogsRequestMarshaler.create(logs));
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    static final class Spans implements SpanExporter {
        private final DesktopSender sender;

        Spans(DesktopSender sender) {
            this.sender = sender;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return sender.export(Outbox.TRACES, TraceRequestMarshaler.create(spans));
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
