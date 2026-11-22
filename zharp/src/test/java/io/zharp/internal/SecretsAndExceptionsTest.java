package io.zharp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class SecretsAndExceptionsTest {
    @Test
    void secretHeadersAreTheSameAsTheOtherSdks() {
        for (String h : Arrays.asList("authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token", "proxy-authorization", "x-session-id", "x-access-token")) {
            assertTrue(Secrets.isSecretHeader(h, Collections.emptyList()), h);
        }
        for (String h : Arrays.asList("user-agent", "content-type", "accept", "x-request-id", "traceparent")) {
            assertFalse(Secrets.isSecretHeader(h, Collections.emptyList()), h);
        }
        assertTrue(Secrets.isSecretHeader("x-tenant", Collections.singletonList("X_Tenant")));
    }

    @Test
    void urlsLoseTheirSecrets() {
        assertEquals("https://api.example.com/x?token=[Filtered]&page=2", Secrets.redactUrl("https://user:pw@api.example.com/x?token=abc&page=2"));
        assertEquals("key=[Filtered]&sig=[Filtered]&q=shoes", Secrets.redactQuery("key=1&sig=2&q=shoes"));
    }

    @Test
    void exceptionsAreWrittenInTheJvmForm() {
        Exceptions.Info info = Exceptions.describe(new IllegalStateException("boom"));
        assertEquals("java.lang.IllegalStateException", info.type);
        assertEquals("boom", info.message);
        assertTrue(info.stacktrace.startsWith("java.lang.IllegalStateException: boom\n\tat io.zharp.internal.SecretsAndExceptionsTest."), info.stacktrace);
        assertFalse(info.stacktrace.contains("\r"));
    }

    @Test
    void wrappersAreReportedAsWhatTheyWrap() {
        IllegalArgumentException cause = new IllegalArgumentException("inner");
        Exceptions.Info info = Exceptions.describe(new ExecutionException(cause));
        assertEquals("java.lang.IllegalArgumentException", info.type);
        assertEquals("inner", info.message);
    }

    @Test
    void aWrapperAndWhatItWrapsShareAnId() {
        RuntimeException cause = new RuntimeException("db");
        Set<Long> outer = Exceptions.parseIds(Exceptions.ids(new IllegalStateException("wrapped", cause)));
        Set<Long> inner = Exceptions.parseIds(Exceptions.ids(cause));
        assertEquals(2, outer.size());
        assertTrue(outer.containsAll(inner));
    }

    @Test
    void failedQueriesLoseTheRowsValuesAndKeepTheirCode() {
        SQLException e = new SQLException("ERROR: duplicate key value violates unique constraint \"users_email_key\"\n  Detail: Key (email)=(a@b.c) already exists.", "23505");
        Exceptions.Info info = Exceptions.describe(e);
        assertEquals("ERROR: duplicate key value violates unique constraint \"users_email_key\"", info.message);
        assertFalse(info.stacktrace.contains("a@b.c"));
        assertEquals("23505", info.sqlState);
        assertEquals("UniqueViolation", Exceptions.dbErrorType("postgresql", "23505", "org.postgresql.util.PSQLException"));
        assertEquals("java.sql.SQLException", Exceptions.dbErrorType("mysql", "23000", "java.sql.SQLException"));
    }

    @Test
    void settingsComeFromExplicitValuesFirst() {
        Map<String, String> values = new HashMap<>();
        values.put("zharp.key", "zpk_1");
        values.put("zharp.service", "orders");
        values.put("zharp.logs", "yes");
        values.put("zharp.endpoint", "http://localhost:3000/api/v1/otlp/");
        Settings s = Settings.from(Settings.of(values));
        assertEquals("zpk_1", s.key);
        assertEquals("orders", s.service);
        assertTrue(s.logs);
        assertTrue(s.tracing);
        assertEquals("http://localhost:3000/api/v1/otlp", s.endpoint);
        assertNull(Settings.flagOf("maybe"));
    }

    @Test
    void headersAreReadFromAMapOrServletShapes() {
        Map<String, List<String>> map = new HashMap<>();
        map.put("Content-Type", Collections.singletonList("text/plain"));
        map.put("X-Multi", Arrays.asList("a", "b"));
        List<String[]> read = HeaderReader.read(map, true);
        assertEquals(2, read.size());
        assertTrue(read.stream().anyMatch(h -> h[0].equals("x-multi") && h[1].equals("a, b")));
    }
}
