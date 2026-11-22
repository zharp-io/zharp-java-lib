package e2e.multi;

import e2e.multi.lib.Pricing;

/** The app module: a job whose failure comes from the library. Uncaught on the main thread. */
public final class App {
    public static void main(String[] args) {
        System.out.println("total: " + Pricing.total(3));
    }
}
