package ipl.sable.dh;

/**
 * Tracks whether the ipl_sable:sublevels hosting dimension is currently
 * being loaded. Used by IplDhSkipHostingDimensionMixin to cancel DhLevel
 * creation for the hosting dimension.
 *
 * The flag is set by a mixin on MinecraftServer or the dimension load
 * event, and cleared after the dimension load completes.
 */
public final class DhDimensionTracker {

    private static volatile boolean hostingDimensionLoading = false;

    private DhDimensionTracker() {}

    public static void setHostingDimensionLoading(boolean loading) {
        hostingDimensionLoading = loading;
    }

    public static boolean isHostingDimensionLoading() {
        return hostingDimensionLoading;
    }
}
