package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;

/** Attribute names Zharp reads, the same in every Zharp SDK. */
public final class Keys {
    public static final AttributeKey<String> EXCEPTION_TYPE = AttributeKey.stringKey("exception.type");
    public static final AttributeKey<String> EXCEPTION_MESSAGE = AttributeKey.stringKey("exception.message");
    public static final AttributeKey<String> EXCEPTION_STACKTRACE = AttributeKey.stringKey("exception.stacktrace");
    public static final AttributeKey<String> HANDLED = AttributeKey.stringKey("zharp.handled");
    public static final AttributeKey<String> MECHANISM = AttributeKey.stringKey("zharp.mechanism");
    /** On an exception event the SDK reports itself (once, as an error log): the server skips it. */
    public static final AttributeKey<String> DEFERRED = AttributeKey.stringKey("zharp.deferred");
    public static final AttributeKey<String> BUILD_ID = AttributeKey.stringKey("zharp.build_id");

    /** Internal, never exported: which Throwables (the error and its causes) a record is about. */
    public static final AttributeKey<String> EIDS = AttributeKey.stringKey("zharp.eids");
    /** Internal, never exported: send the log line without its exception (already reported). */
    public static final AttributeKey<String> STRIP = AttributeKey.stringKey("zharp.strip");

    public static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");
    public static final AttributeKey<String> DB_STATEMENT = AttributeKey.stringKey("db.statement");
    public static final AttributeKey<String> DB_NAME = AttributeKey.stringKey("db.name");
    public static final AttributeKey<String> DB_ERROR_CODE = AttributeKey.stringKey("db.error.code");

    public static final AttributeKey<String> USER_ID = AttributeKey.stringKey("enduser.id");
    public static final AttributeKey<String> USER_EMAIL = AttributeKey.stringKey("enduser.email");
    public static final AttributeKey<String> USER_NAME = AttributeKey.stringKey("enduser.name");
    public static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("session.id");

    /** Scope of the error reports the SDK makes itself (request failures, failed queries, crashes). */
    public static final String SCOPE_ERRORS = "io.zharp.errors";
    /** Scope of Zharp.log and Zharp.captureError. */
    public static final String SCOPE_API = "io.zharp";
    /** Scope of the API's instructions to the agent (setUser, setTag, flush): handled, never sent. */
    public static final String SCOPE_CONTROL = "io.zharp.control";

    private Keys() {}
}
