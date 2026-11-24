package io.zharp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A desktop app's sending: on disk first, deleted once delivered, kept while offline, sent on the next launch. */
class DesktopSenderTest {
    @TempDir File dir;

    private static List<LogRecordData> crash() {
        return Collections.singletonList(TestLogRecordData.builder()
                .setResource(Resource.empty())
                .setInstrumentationScopeInfo(InstrumentationScopeInfo.create(Keys.SCOPE_ERRORS))
                .setSeverity(Severity.FATAL)
                .setBody("IllegalStateException: boom")
                .setAttributes(Attributes.of(Keys.EXCEPTION_TYPE, "java.lang.IllegalStateException"))
                .setTimestamp(1, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build());
    }

    private static HttpServer server(int status, List<String> got) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            got.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization") + " " + exchange.getRequestHeaders().getFirst("Content-Encoding"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void offlineKeepsTheBatchAndTheNextLaunchSendsIt() throws Exception {
        Outbox outbox = new Outbox(new File(dir, "outbox"));
        // Nothing listens on this port: offline.
        DesktopSender offline = new DesktopSender("http://127.0.0.1:1", "zpk_test", outbox);
        assertTrue(new DesktopSender.Logs(offline).export(crash()).join(15, java.util.concurrent.TimeUnit.SECONDS).isDone());
        assertEquals(1, outbox.pending().size());
        assertTrue(outbox.pending().get(0).getName().endsWith(".logs"));

        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer server = server(200, got);
        try {
            DesktopSender next = new DesktopSender("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/otlp", "zpk_test", new Outbox(new File(dir, "outbox")));
            assertTrue(next.drain());
            assertEquals(Collections.singletonList("/api/v1/otlp/v1/logs Bearer zpk_test gzip"), got);
            assertTrue(outbox.pending().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deliveredBatchesLeaveNothingBehind() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        HttpServer server = server(200, got);
        try {
            Outbox outbox = new Outbox(new File(dir, "outbox"));
            DesktopSender sender = new DesktopSender("http://127.0.0.1:" + server.getAddress().getPort(), "zpk_test", outbox);
            assertTrue(new DesktopSender.Logs(sender).export(crash()).isSuccess());
            assertEquals(1, got.size());
            assertTrue(outbox.pending().isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusedForGoodIsNotKeptAndServerErrorsAre() throws Exception {
        for (int status : new int[] {400, 503}) {
            List<String> got = new CopyOnWriteArrayList<>();
            HttpServer server = server(status, got);
            try {
                Outbox outbox = new Outbox(new File(dir, "outbox-" + status));
                DesktopSender sender = new DesktopSender("http://127.0.0.1:" + server.getAddress().getPort(), "zpk_test", outbox);
                new DesktopSender.Logs(sender).export(crash());
                assertEquals(status == 400 ? 0 : 1, outbox.pending().size(), "HTTP " + status);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void outboxDropsTheOldestOverItsCap() throws Exception {
        Outbox outbox = new Outbox(new File(dir, "capped"), 3, 1 << 20, 60_000);
        for (int i = 0; i < 5; i++) outbox.save(Outbox.LOGS, new byte[] {(byte) i});
        List<File> left = outbox.pending();
        assertEquals(3, left.size());
        assertEquals(2, Files.readAllBytes(left.get(0).toPath())[0]);
    }

    @Test
    void desktopDataDirAndDeviceIdStay() {
        System.setProperty("zharp.data.dir", new File(dir, "data").getPath());
        try {
            Settings s = Settings.from(Settings.of(Collections.singletonMap("zharp.desktop", "true")));
            assertTrue(s.desktop);
            String id = Desktop.deviceId(s);
            assertEquals(id, Desktop.deviceId(s));
            assertEquals(32, id.length());
        } finally {
            System.clearProperty("zharp.data.dir");
        }
    }
}
