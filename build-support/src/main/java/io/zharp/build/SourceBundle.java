package io.zharp.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * The app's own Java and Kotlin sources as Zharp's {@code source-bundle} artifact — gzipped JSON,
 * {@code {"files": {"<path from the project root>": "<text>"}}}, the same format as the Android
 * Gradle plugin and {@code @zharp/cli} upload — and the build ID they're uploaded under.
 * Shared by the Gradle and Maven plugins.
 */
public final class SourceBundle {
    public static final String RESOURCE = "META-INF/zharp/build.properties";
    public static final String PROPERTY = "io.zharp.build.id";

    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 50L * 1024 * 1024;
    private static final int MAX_FILES = 20_000;

    private SourceBundle() {}

    /** The .java/.kt/.kts files under these folders, by path from {@code root}, within the size caps. */
    public static Map<String, String> collect(Collection<File> dirs, File root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        long total = 0;
        List<File> sorted = new ArrayList<>(dirs);
        sorted.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        for (File dir : sorted) {
            if (!dir.isDirectory()) continue;
            // Generated sources (build/, target/) are not the app's own code.
            String path = dir.getAbsolutePath().replace(File.separatorChar, '/');
            if (path.contains("/build/") || path.contains("/target/")) continue;
            List<File> files = new ArrayList<>();
            walk(dir, files);
            files.sort((a, b) -> a.getPath().compareTo(b.getPath()));
            for (File file : files) {
                long size = file.length();
                if (size > MAX_FILE_BYTES || total + size > MAX_TOTAL_BYTES || out.size() >= MAX_FILES) continue;
                String relative = relative(root, file);
                if (!out.containsKey(relative)) {
                    out.put(relative, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    total += size;
                }
            }
        }
        return out;
    }

    private static void walk(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) walk(child, out);
            else if (child.isFile() && (child.getName().endsWith(".java") || child.getName().endsWith(".kt") || child.getName().endsWith(".kts"))) out.add(child);
        }
    }

    static String relative(File root, File file) {
        String r = root.getAbsoluteFile().toPath().normalize().relativize(file.getAbsoluteFile().toPath().normalize()).toString();
        return r.replace(File.separatorChar, '/');
    }

    /** A build ID from the sources themselves: the same code gives the same ID, so builds stay cacheable. */
    public static String buildId(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
                digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return UUID.nameUUIDFromBytes(digest.digest()).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code io.zharp.build.id=…}, as written into the jar. */
    public static String properties(String buildId) {
        return "# Written by Zharp's build plugin: the build's sources are uploaded under this ID.\n" + PROPERTY + "=" + buildId + "\n";
    }

    public static byte[] gzippedJson(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json(files).getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    static String json(Map<String, String> files) {
        StringBuilder b = new StringBuilder("{\"files\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (!first) b.append(',');
            first = false;
            string(b, e.getKey());
            b.append(':');
            string(b, e.getValue());
        }
        return b.append("}}").toString();
    }

    private static void string(StringBuilder b, String value) {
        b.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < ' ') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
