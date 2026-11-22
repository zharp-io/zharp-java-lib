package io.zharp.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StampTest {
    private static final String T = "0123456789abcdef0123456789abcdef";
    private static final String S = "0123456789abcdef";

    @Test
    void textLineEndsWithTheIds() {
        assertEquals("INFO hello trace_id=" + T + " span_id=" + S + "\n", Stamp.stamp("INFO hello\n", T, S));
        assertEquals("INFO hello trace_id=" + T + " span_id=" + S + "\r\n", Stamp.stamp("INFO hello\r\n", T, S));
    }

    @Test
    void aStackTraceGetsOneStampAtItsEnd() {
        String record = "ERROR failed\njava.lang.IllegalStateException: x\n\tat a.B.c(B.java:1)\n";
        assertEquals("ERROR failed\njava.lang.IllegalStateException: x\n\tat a.B.c(B.java:1) trace_id=" + T + " span_id=" + S + "\n", Stamp.stamp(record, T, S));
    }

    @Test
    void jsonLineGetsFields() {
        assertEquals("{\"msg\":\"hi\",\"trace_id\":\"" + T + "\",\"span_id\":\"" + S + "\"}\n", Stamp.stamp("{\"msg\":\"hi\"}\n", T, S));
    }

    @Test
    void alreadyStampedOrZharpLinesAreLeftAlone() {
        assertEquals("x trace_id=abc\n", Stamp.stamp("x trace_id=abc\n", T, S));
        assertEquals("[Zharp] active\n", Stamp.stamp("[Zharp] active\n", T, S));
        assertEquals("\n", Stamp.stamp("\n", T, S));
    }
}
