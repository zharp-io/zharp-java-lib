package e2e.multi.lib;

/** The library module: where the error is thrown. */
public final class Pricing {
    private Pricing() {}

    public static int total(int items) {
        if (items > 2) {
            throw new IllegalStateException("boom from the controller");
        }
        return items * 10;
    }
}
