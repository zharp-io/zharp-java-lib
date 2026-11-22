package io.zharp.internal;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * The build ID Zharp's Gradle or Maven plugin wrote into the app ({@value #RESOURCE}), under which
 * it uploaded the build's sources. Sent on every error as {@code zharp.build_id}, so the error
 * shows the code of the build that ran.
 */
public final class BuildInfo {
    public static final String RESOURCE = "META-INF/zharp/build.properties";
    public static final String PROPERTY = "io.zharp.build.id";

    private static volatile String buildId;
    private static volatile boolean searchedContext;

    private BuildInfo() {}

    /** The build ID, or null. Looked up once at startup and once more from the first app class loader asked. */
    public static String buildId() {
        String id = buildId;
        if (id != null) return id;
        if (!searchedContext) {
            searchedContext = true;
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null) buildId = fromLoader(context);
        }
        return buildId;
    }

    /** Look on the classpath, and inside a Spring Boot jar the JVM was started with. */
    public static void init(ClassLoader loader) {
        String id = Settings.pick(System.getProperty("zharp.build.id"), Settings.env("ZHARP_BUILD_ID"));
        if (id == null && loader != null) id = fromLoader(loader);
        if (id == null) id = fromLoader(ClassLoader.getSystemClassLoader());
        if (id == null) id = fromStartedJar();
        if (id != null) buildId = id;
    }

    static String fromLoader(ClassLoader loader) {
        try {
            Enumeration<URL> urls = loader.getResources(RESOURCE);
            while (urls.hasMoreElements()) {
                try (InputStream in = urls.nextElement().openStream()) {
                    String id = read(in);
                    if (id != null) return id;
                }
            }
        } catch (Exception ignored) {
            // Nothing to read.
        }
        return null;
    }

    /** {@code java -jar app.jar} with a Spring Boot (or WAR) layout: the app's classes are a folder in the jar. */
    static String fromStartedJar() {
        String command = System.getProperty("sun.java.command");
        if (command == null) return null;
        String first = command.trim().split("\\s+")[0];
        if (!first.endsWith(".jar") && !first.endsWith(".war")) return null;
        File file = new File(first);
        if (!file.isFile()) return null;
        try (JarFile jar = new JarFile(file)) {
            for (String prefix : new String[] {"BOOT-INF/classes/", "WEB-INF/classes/", ""}) {
                ZipEntry entry = jar.getEntry(prefix + RESOURCE);
                if (entry != null) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        String id = read(in);
                        if (id != null) return id;
                    }
                }
            }
        } catch (Exception ignored) {
            // Not a jar we can read.
        }
        return null;
    }

    static String read(InputStream in) throws java.io.IOException {
        Properties properties = new Properties();
        properties.load(in);
        String id = properties.getProperty(PROPERTY);
        return id == null || id.trim().isEmpty() ? null : id.trim();
    }

    /** For tests. */
    static void set(String id) {
        buildId = id;
    }
}
