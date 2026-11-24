package io.zharp.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.SpanKind;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A desktop app ({@code ZharpOptions.desktop(true)}): where it keeps its outbox and device id, the
 * session (one per launch), the OS and device on every span and log, and the screen — each window
 * shown, by its title — on everything that happens while it is in front.
 */
public final class Desktop {
    static final AttributeKey<String> SCREEN_NAME = AttributeKey.stringKey("screen.name");
    static final AttributeKey<String> SCREEN_TYPE = AttributeKey.stringKey("screen.type");

    private static volatile DesktopSender sender;
    private static volatile ZharpCore core;
    private static volatile String screen;

    private Desktop() {}

    /** The per-user folder this app's Zharp data lives in. */
    static File dataDir(Settings s) {
        if (s.dataDir != null) return new File(s.dataDir);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        File base;
        if (os.startsWith("windows")) {
            String local = Settings.env("LOCALAPPDATA");
            base = new File(local != null ? local : home, "Zharp");
        } else if (os.startsWith("mac")) {
            base = new File(home, "Library/Caches/Zharp");
        } else {
            String xdg = Settings.env("XDG_CACHE_HOME");
            base = new File(xdg != null ? xdg : home + "/.cache", "zharp");
        }
        return new File(base, safe(s.service));
    }

    private static String safe(String name) {
        String n = name == null ? "app" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() ? "app" : n;
    }

    /** The sender every exporter uses: the outbox is in place before the SDK's first export. */
    static synchronized DesktopSender sender(Settings s) {
        if (sender == null) sender = new DesktopSender(s.endpoint, s.key, new Outbox(new File(dataDir(s), "outbox")));
        return sender;
    }

    /** The OS and device, on the resource every span and log carries. */
    static Attributes resource(Settings s) {
        AttributesBuilder b = Attributes.builder();
        String osName = System.getProperty("os.name", "");
        String lower = osName.toLowerCase(Locale.ROOT);
        String type = lower.startsWith("windows") ? "windows" : lower.startsWith("mac") ? "darwin" : lower.contains("linux") ? "linux" : lower;
        b.put("os.type", type);
        b.put("os.name", lower.startsWith("mac") ? "macOS" : osName);
        b.put("os.version", System.getProperty("os.version", ""));
        b.put("host.arch", System.getProperty("os.arch", ""));
        b.put("process.runtime.name", System.getProperty("java.runtime.name", "Java"));
        b.put("process.runtime.version", System.getProperty("java.runtime.version", System.getProperty("java.version", "")));
        String device = deviceId(s);
        if (device != null) b.put("device.id", device);
        return b.build();
    }

    /** A random id kept in the data folder: the same install is the same device. */
    static String deviceId(Settings s) {
        File file = new File(dataDir(s), "device-id");
        try {
            if (file.isFile()) {
                String id = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) return id;
            }
            String id = UUID.randomUUID().toString().replace("-", "");
            file.getParentFile().mkdirs();
            Files.write(file.toPath(), id.getBytes(StandardCharsets.UTF_8));
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    /** Once the SDK runs: the session, what the last run left unsent, and the windows. */
    static void start(ZharpCore c) {
        core = c;
        c.store.setTag("session.id", UUID.randomUUID().toString().replace("-", ""));
        DesktopSender s = sender;
        if (s != null) s.drainLater();
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless()) DesktopWindows.install();
        } catch (Throwable noAwt) {
            // A runtime without java.desktop, or no display: errors and crashes are still reported.
        }
    }

    /** A window came to the front: it is the screen from now on, and one screen view is recorded. */
    static void screen(String name) {
        if (name == null || name.trim().isEmpty() || name.equals(screen)) return;
        screen = name;
        ZharpCore c = core;
        if (c == null) return;
        c.store.setTag(SCREEN_NAME.getKey(), name);
        c.store.setTag(SCREEN_TYPE.getKey(), "window");
        try {
            if (c.settings.tracing && c.sdk() != null) {
                long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
                c.sdk().getTracer("io.zharp.desktop", Version.VERSION).spanBuilder("screen " + name)
                        .setNoParent()
                        .setSpanKind(SpanKind.INTERNAL)
                        .setStartTimestamp(now, TimeUnit.NANOSECONDS)
                        .setAttribute(SCREEN_NAME, name)
                        .setAttribute(SCREEN_TYPE, "window")
                        .startSpan()
                        .end(now, TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException ignored) {
            // Never break the app's UI.
        }
    }

    /**
     * An uncaught exception on this thread ends the app: the main thread, or the event thread of a
     * Compose window (whose default handler closes the window and rethrows there).
     */
    static boolean endsApp(Thread thread) {
        if ("main".equals(thread.getName())) return true;
        if (!thread.getName().startsWith("AWT-EventQueue")) return false;
        try {
            Class.forName("androidx.compose.ui.window.WindowExceptionHandler", false, Desktop.class.getClassLoader());
            return true;
        } catch (Throwable notCompose) {
            return false;
        }
    }
}
