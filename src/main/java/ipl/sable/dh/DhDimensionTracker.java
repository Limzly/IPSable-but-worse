package ipl.sable.dh;

/**
 * Tracks whether the ipl_sable:sublevels hosting dimension is currently
 * being loaded. Used by IplDhSkipHostingDimensionMixin to cancel DhLevel
 * creation for the hosting dimension.
 *
 * The flag is set with a timeout (default 1 second) and auto-clears after
 * that time. This ensures the flag doesn't stay set forever if the DhLevel
 * creation hook doesn't fire for some reason.
 */
public final class DhDimensionTracker {

    private static volatile boolean hostingDimensionLoading = false;
    private static volatile long deadline = 0;

    private DhDimensionTracker() {}

    /**
     * Set the flag. Auto-clears after the given timeout (in milliseconds).
     */
    public static void setHostingDimensionLoading(boolean loading, long timeoutMs) {
        hostingDimensionLoading = loading;
        if (loading) {
            deadline = System.currentTimeMillis() + timeoutMs;
        }
    }

    /**
     * Set the flag with a default 1-second timeout.
     */
    public static void setHostingDimensionLoading(boolean loading) {
        setHostingDimensionLoading(loading, 1000);
    }

    public static boolean isHostingDimensionLoading() {
        if (hostingDimensionLoading) {
            if (System.currentTimeMillis() > deadline) {
                hostingDimensionLoading = false;
                return false;
            }
            return true;
        }
        return false;
    }
}
