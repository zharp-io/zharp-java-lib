package io.zharp.internal;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.WindowEvent;

/**
 * Each AWT window that comes to the front — a Compose for Desktop window, a Swing JFrame or JDialog —
 * is the screen: its title, or its class's name when it has none. Kept apart from {@link Desktop}
 * so a runtime without {@code java.desktop} never loads it.
 */
final class DesktopWindows {
    private static volatile boolean installed;

    private DesktopWindows() {}

    static synchronized void install() {
        if (installed) return;
        installed = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            try {
                int id = event.getID();
                if (id == WindowEvent.WINDOW_ACTIVATED || id == WindowEvent.WINDOW_OPENED) {
                    Desktop.screen(name(((WindowEvent) event).getWindow()));
                }
            } catch (RuntimeException ignored) {
                // Never break the app's event loop.
            }
        }, AWTEvent.WINDOW_EVENT_MASK);
        // A window already showing (init called after the first window opened).
        for (Window w : Window.getWindows()) {
            if (w.isActive()) Desktop.screen(name(w));
        }
    }

    /** A frame or dialog's title (its class name when untitled); popups and tooltips aren't screens. */
    static String name(Window window) {
        if (!(window instanceof Frame) && !(window instanceof Dialog)) return null;
        String title = window instanceof Frame ? ((Frame) window).getTitle() : window instanceof Dialog ? ((Dialog) window).getTitle() : null;
        if (title != null && !title.trim().isEmpty()) return title.trim();
        return window.getClass().getSimpleName();
    }
}
