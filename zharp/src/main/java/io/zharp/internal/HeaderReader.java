package io.zharp.internal;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every header of a request or response, whatever server made it — read by shape, since the
 * agent's instrumentations each hand over their own type: Servlet's HttpServletRequest/Response,
 * Tomcat's coyote Request/Response, Netty's HttpRequest/HttpResponse (Spring WebFlux, Reactor
 * Netty), Jetty's Request/Response, Undertow's exchange, Ktor's ApplicationRequest/Response, and
 * anything holding a Map of headers. Only public methods are called.
 */
public final class HeaderReader {
    private static final int MAX_HEADERS = 40;
    private static final Object[] NONE = new Object[0];
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Method MISSING;

    static {
        try {
            MISSING = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private HeaderReader() {}

    /** Name (lower case) and value pairs, at most 40. Empty when the shape is unknown. */
    public static List<String[]> read(Object source, boolean request) {
        List<String[]> out = new ArrayList<>();
        try {
            collect(source, request, out, 0);
        } catch (Throwable ignored) {
            // A shape we can't read: no headers rather than a broken request.
        }
        return out;
    }

    private static void collect(Object o, boolean request, List<String[]> out, int depth) throws Exception {
        if (o == null || depth > 3) return;
        if (o instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) add(out, e.getKey(), e.getValue());
            return;
        }
        Class<?> c = o.getClass();
        // Servlet (and Jakarta Servlet): names, then each name's values.
        Method names = method(c, "getHeaderNames");
        if (names != null) {
            Object list = names.invoke(o);
            Method values = method(c, "getHeaders", String.class);
            for (Object name : iterate(list)) {
                if (out.size() >= MAX_HEADERS) return;
                Object v = values != null ? values.invoke(o, name) : null;
                add(out, name, v);
            }
            return;
        }
        // Tomcat's coyote request/response.
        Method mime = method(c, "getMimeHeaders");
        if (mime != null) {
            Object headers = mime.invoke(o);
            Method size = method(headers.getClass(), "size");
            Method name = method(headers.getClass(), "getName", int.class);
            Method value = method(headers.getClass(), "getValue", int.class);
            if (size != null && name != null && value != null) {
                int n = (Integer) size.invoke(headers);
                for (int i = 0; i < n && out.size() < MAX_HEADERS; i++) add(out, name.invoke(headers, i), value.invoke(headers, i));
            }
            return;
        }
        // Undertow's exchange.
        Method side = method(c, request ? "getRequestHeaders" : "getResponseHeaders");
        if (side != null) {
            collect(side.invoke(o), request, out, depth + 1);
            return;
        }
        // Netty (headers()), Jetty and Ktor (getHeaders()), Ktor's response headers (allValues()).
        for (String accessor : new String[] {"headers", "getHeaders", "allValues"}) {
            Method m = method(c, accessor);
            if (m != null) {
                Object headers = m.invoke(o);
                if (headers != null && headers != o) {
                    if (headers instanceof Iterable || headers instanceof Map) {
                        entries(headers, out);
                    } else {
                        collect(headers, request, out, depth + 1);
                    }
                    return;
                }
            }
        }
        // Ktor's Headers (StringValues): entries().
        Method entries = method(c, "entries");
        if (entries != null) {
            entries(entries.invoke(o), out);
            return;
        }
        if (o instanceof Iterable) {
            entries(o, out);
            return;
        }
        // A wrapper (OpenTelemetry's HttpRequestAndChannel, a record holding the request).
        Method inner = method(c, request ? "request" : "response");
        if (inner != null) collect(inner.invoke(o), request, out, depth + 1);
    }

    /** An iterable of header entries: Map.Entry (Netty, Ktor), Jetty's HttpField, Undertow's HeaderValues. */
    private static void entries(Object headers, List<String[]> out) throws Exception {
        if (headers instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) headers).entrySet()) add(out, e.getKey(), e.getValue());
            return;
        }
        for (Object item : iterate(headers)) {
            if (out.size() >= MAX_HEADERS) return;
            if (item instanceof Map.Entry) {
                add(out, ((Map.Entry<?, ?>) item).getKey(), ((Map.Entry<?, ?>) item).getValue());
                continue;
            }
            Class<?> c = item.getClass();
            Method name = method(c, "getName");
            Method value = method(c, "getValue");
            if (name != null && value != null) {
                add(out, name.invoke(item), value.invoke(item));
                continue;
            }
            Method headerName = method(c, "getHeaderName");
            if (headerName != null && item instanceof Iterable) add(out, headerName.invoke(item), item);
        }
    }

    private static Iterable<?> iterate(Object o) {
        if (o instanceof Iterable) return (Iterable<?>) o;
        if (o instanceof Enumeration) {
            List<Object> list = new ArrayList<>();
            Enumeration<?> e = (Enumeration<?>) o;
            while (e.hasMoreElements() && list.size() < 200) list.add(e.nextElement());
            return list;
        }
        if (o instanceof Object[]) return java.util.Arrays.asList((Object[]) o);
        return java.util.Collections.emptyList();
    }

    private static void add(List<String[]> out, Object name, Object value) {
        if (name == null || out.size() >= MAX_HEADERS) return;
        String n = name.toString().toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.startsWith(":")) return;
        String v = text(value);
        if (v == null) return;
        for (String[] existing : out) {
            if (existing[0].equals(n)) {
                existing[1] = existing[1] + ", " + v;
                return;
            }
        }
        out.add(new String[] {n, v});
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        if (value instanceof Collection || value instanceof Enumeration || value instanceof Iterable || value instanceof Object[]) {
            StringBuilder b = new StringBuilder();
            for (Object v : iterate(value)) {
                if (v == null) continue;
                if (b.length() > 0) b.append(", ");
                b.append(v);
            }
            return b.toString();
        }
        return value.toString();
    }

    /** A public method callable on {@code c}'s instances, from a public class or interface it has. */
    private static Method method(Class<?> c, String name, Class<?>... params) {
        String key = c.getName() + '#' + name + '/' + params.length + '@' + System.identityHashCode(c.getClassLoader());
        Method cached = METHODS.get(key);
        if (cached != null) return cached == MISSING ? null : cached;
        Method found = find(c, name, params);
        METHODS.put(key, found == null ? MISSING : found);
        return found;
    }

    private static Method find(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            if (Modifier.isPublic(k.getModifiers())) {
                try {
                    Method m = k.getMethod(name, params);
                    if (Modifier.isPublic(m.getDeclaringClass().getModifiers()) && !Modifier.isStatic(m.getModifiers())) return m;
                } catch (NoSuchMethodException ignored) {
                    // Keep looking.
                }
            }
            for (Class<?> i : allInterfaces(k)) {
                if (!Modifier.isPublic(i.getModifiers())) continue;
                try {
                    return i.getMethod(name, params);
                } catch (NoSuchMethodException ignored) {
                    // Keep looking.
                }
            }
        }
        return null;
    }

    private static List<Class<?>> allInterfaces(Class<?> c) {
        List<Class<?>> out = new ArrayList<>();
        List<Class<?>> queue = new ArrayList<>(java.util.Arrays.asList(c.getInterfaces()));
        for (int i = 0; i < queue.size(); i++) {
            Class<?> next = queue.get(i);
            if (out.contains(next)) continue;
            out.add(next);
            queue.addAll(java.util.Arrays.asList(next.getInterfaces()));
        }
        return out;
    }

    /** Unused by the readers; kept for symmetry with iterators handed over directly. */
    static Iterator<?> iterator(Object o) {
        return iterate(o).iterator();
    }
}
