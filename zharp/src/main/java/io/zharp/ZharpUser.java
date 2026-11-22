package io.zharp;

/** Who a request is for: {@code enduser.id}, {@code enduser.email} and {@code enduser.name}. */
public final class ZharpUser {
    final String id;
    final String email;
    final String name;

    public ZharpUser(String id, String email, String name) {
        this.id = id;
        this.email = email;
        this.name = name;
    }

    public static ZharpUser of(String id) {
        return new ZharpUser(id, null, null);
    }

    public ZharpUser withEmail(String email) {
        return new ZharpUser(id, email, name);
    }

    public ZharpUser withName(String name) {
        return new ZharpUser(id, email, name);
    }
}
