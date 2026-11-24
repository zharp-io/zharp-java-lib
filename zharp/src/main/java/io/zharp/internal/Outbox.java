package io.zharp.internal;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A desktop app's batches on disk (gzipped OTLP, exactly what is POSTed), written before they are
 * sent and deleted once they arrive: a crash, a quit mid-send or being offline leaves them here,
 * and the next launch sends them. Capped by count, total size and age; when full, the oldest go.
 * The same format and limits as the Android SDK's outbox.
 */
final class Outbox {
    static final String LOGS = "logs";
    static final String TRACES = "traces";

    private final File dir;
    private final int maxFiles;
    private final long maxBytes;
    private final long maxAgeMillis;
    private final AtomicLong sequence = new AtomicLong();

    Outbox(File dir) {
        this(dir, 200, 8L * 1024 * 1024, 3L * 24 * 60 * 60 * 1000);
    }

    Outbox(File dir, int maxFiles, long maxBytes, long maxAgeMillis) {
        this.dir = dir;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
        this.maxAgeMillis = maxAgeMillis;
    }

    /** Save a batch (fsynced, then renamed into place); its file, or null when it couldn't be written. */
    synchronized File save(String signal, byte[] gzipped) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String name = String.format(Locale.ROOT, "%013d-%06d.%s", System.currentTimeMillis(), sequence.incrementAndGet() % 1_000_000, signal);
            File temp = new File(dir, name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(gzipped);
                out.getFD().sync();
            }
            File file = new File(dir, name);
            if (!temp.renameTo(file)) {
                temp.delete();
                return null;
            }
            trim();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    /** What's waiting, oldest first, with anything over the caps already deleted. */
    synchronized List<File> pending() {
        try {
            trim();
            return files();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    static String signalOf(File file) {
        String name = file.getName();
        if (name.endsWith("." + LOGS)) return LOGS;
        if (name.endsWith("." + TRACES)) return TRACES;
        return null;
    }

    void delete(File file) {
        if (file != null) file.delete();
    }

    boolean isEmpty() {
        return files().isEmpty();
    }

    private List<File> files() {
        File[] all = dir.listFiles();
        if (all == null) return Collections.emptyList();
        List<File> out = new ArrayList<>();
        for (File f : all) {
            if (f.isFile() && !f.getName().endsWith(".tmp") && signalOf(f) != null) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private void trim() {
        long now = System.currentTimeMillis();
        File[] all = dir.listFiles();
        if (all == null) return;
        // Leftovers of a write the process didn't live to finish.
        for (File f : Arrays.asList(all)) {
            if (f.getName().endsWith(".tmp") && now - f.lastModified() > 60_000) f.delete();
        }
        List<File> files = new ArrayList<>(files());
        for (java.util.Iterator<File> it = files.iterator(); it.hasNext(); ) {
            File f = it.next();
            long savedAt;
            try {
                savedAt = Long.parseLong(f.getName().substring(0, f.getName().indexOf('-')));
            } catch (RuntimeException e) {
                savedAt = 0;
            }
            if (now - savedAt > maxAgeMillis) {
                f.delete();
                it.remove();
            }
        }
        long total = 0;
        for (File f : files) total += f.length();
        while (!files.isEmpty() && (files.size() > maxFiles || total > maxBytes)) {
            File oldest = files.remove(0);
            total -= oldest.length();
            oldest.delete();
        }
    }
}
