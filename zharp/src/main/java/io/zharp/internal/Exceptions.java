package io.zharp.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Errors as Zharp reads them: {@code exception.type} (the class's binary name), {@code
 * exception.message}, and {@code exception.stacktrace} in the JVM's own
 * {@code at pkg.Class.method(File.java:12)} form, causes included — which the backend parses as
 * Java and Kotlin frames.
 */
public final class Exceptions {
    /** A cap on one stack, so a recursion overflow can't fill a record. */
    private static final int MAX_STACK = 64 * 1024;

    /**
     * Lines of a database error that carry the row's values (Postgres's "Detail: Key (email)=(…)
     * already exists."). The statement, the constraint and the code say enough.
     */
    private static final Pattern PRIVATE_LINES = Pattern.compile("[ \\t]*(?:Detail|DETAIL):[^\\n]*\\n?");

    /** PostgreSQL's SQLSTATE codes people meet, by the names Postgres gives them (as Node names them). */
    private static final Map<String, String> SQLSTATE = new HashMap<>();

    static {
        String[] pairs = {
            "23505", "UniqueViolation", "23503", "ForeignKeyViolation", "23502", "NotNullViolation", "23514", "CheckViolation",
            "23P01", "ExclusionViolation", "22001", "StringDataRightTruncation", "22003", "NumericValueOutOfRange",
            "22P02", "InvalidTextRepresentation", "22007", "InvalidDatetimeFormat", "22008", "DatetimeFieldOverflow", "22012", "DivisionByZero",
            "42P01", "UndefinedTable", "42703", "UndefinedColumn", "42883", "UndefinedFunction", "42601", "SyntaxError", "42501", "InsufficientPrivilege",
            "40001", "SerializationFailure", "40P01", "DeadlockDetected", "55P03", "LockNotAvailable", "57014", "QueryCanceled",
            "53300", "TooManyConnections", "08006", "ConnectionFailure", "08001", "ConnectionRefused", "28P01", "InvalidPassword", "3D000", "InvalidCatalogName",
        };
        for (int i = 0; i < pairs.length; i += 2) SQLSTATE.put(pairs[i], pairs[i + 1]);
    }

    private Exceptions() {}

    /** What a Throwable is sent as. */
    public static final class Info {
        public final String type;
        public final String message;
        public final String stacktrace;
        /** The ids of this Throwable and its causes, comma-separated (see {@link #ids}). */
        public final String eids;
        /** The SQLSTATE of the first SQLException in its chain, or null. */
        public final String sqlState;

        Info(String type, String message, String stacktrace, String eids, String sqlState) {
            this.type = type;
            this.message = message;
            this.stacktrace = stacktrace;
            this.eids = eids;
            this.sqlState = sqlState;
        }
    }

    public static Info describe(Throwable t) {
        String type = t.getClass().getName();
        String message = t.getMessage() == null ? "" : t.getMessage();
        String stack = stackOf(t);
        SQLException sql = sqlException(t);
        if (sql != null) {
            message = scrub(message);
            stack = scrub(stack);
        }
        return new Info(type, message, stack, ids(t), sql != null ? sql.getSQLState() : null);
    }

    public static String stackOf(Throwable t) {
        StringWriter out = new StringWriter();
        try (PrintWriter writer = new PrintWriter(out)) {
            t.printStackTrace(writer);
        } catch (RuntimeException e) {
            return t.toString();
        }
        // One line ending everywhere: the server reads frames line by line.
        String stack = out.toString().replace("\r\n", "\n");
        return stack.length() > MAX_STACK ? stack.substring(0, MAX_STACK) : stack;
    }

    public static String scrub(String text) {
        return text == null ? null : PRIVATE_LINES.matcher(text).replaceAll("").replaceAll("\\s+$", "");
    }

    static SQLException sqlException(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable c = t; c != null && seen.add(c); c = c.getCause()) {
            if (c instanceof SQLException) return (SQLException) c;
        }
        return null;
    }

    /** The type a failed query is grouped under: Postgres's name for its code (23505 is UniqueViolation), else the driver's exception. */
    public static String dbErrorType(String system, String code, String exceptionType) {
        if (code != null && ("postgresql".equals(system) || system == null) && SQLSTATE.containsKey(code)) return SQLSTATE.get(code);
        if (exceptionType != null && !exceptionType.isEmpty()) return exceptionType;
        return "DatabaseError";
    }

    // ── identity ──────────────────────────────────────────────────────────

    private static final Map<Throwable, Long> IDS = new WeakHashMap<>();
    private static final AtomicLong NEXT = new AtomicLong(1);

    /**
     * Ids of {@code t} and each of its causes, joined with commas: two records are about the same
     * failure when their ids meet — a ServletException and the IllegalStateException it wraps, a
     * Spring DataIntegrityViolationException and the PSQLException under it.
     */
    public static String ids(Throwable t) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        StringBuilder out = new StringBuilder();
        synchronized (IDS) {
            for (Throwable c = t; c != null && seen.add(c) && seen.size() < 20; c = c.getCause()) {
                Long id = IDS.get(c);
                if (id == null) {
                    id = NEXT.getAndIncrement();
                    IDS.put(c, id);
                }
                if (out.length() > 0) out.append(',');
                out.append(id);
            }
        }
        return out.toString();
    }

    public static Set<Long> parseIds(String eids) {
        Set<Long> out = new LinkedHashSet<>();
        if (eids == null || eids.isEmpty()) return out;
        for (String part : eids.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // Not one of ours.
            }
        }
        return out;
    }
}
