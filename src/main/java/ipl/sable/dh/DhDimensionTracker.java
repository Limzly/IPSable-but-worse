package ipl.sable.dh;

/**
 * Tracks whether the {@code ipl_sable:sublevels} hosting dimension is
 * currently being loaded. Used by {@link ipl.sable.mixin.IplDhSkipHostingDimensionMixin}
 * to cancel DhLevel creation for the hosting dimension.
 *
 * <p><b>Threading model:</b> research (bytecode disassembly of DH 3.2.0-b)
 * confirmed that the entire chain
 * {@code ServerLevel.<init>} → {@code LevelEvent.Load} →
 * {@code ServerApi.serverLevelLoadEvent} → {@code getOrLoadLevel} →
 * {@code new DhServerLevel} runs synchronously on the server thread,
 * in microseconds. The flag set by
 * {@link ipl.sable.mixin.IplServerLevelDhTrackerMixin} at
 * {@code ServerLevel.<init>} RETURN is therefore guaranteed to still be
 * set when {@link ipl.sable.mixin.IplDhSkipHostingDimensionMixin} fires
 * microseconds later.
 *
 * <p><b>The 1-second deadline is a safety net only.</b> It exists for the
 * unlikely case where a future DH version changes the call shape and
 * stops being synchronous — the deadline ensures the flag doesn't stay
 * set forever if the consuming mixin doesn't fire. In normal operation
 * the deadline never trips.
 *
 * <p>The flag is cleared lazily by {@link #isHostingDimensionLoading()}
 * on read — that method checks the deadline and clears the flag if it
 * has expired. There is no separate tick handler.
 */
public final class DhDimensionTracker {

    private static volatile boolean hostingDimensionLoading = false;
    private static volatile long deadline = 0;

    /** Default deadline: 1 second. See class javadoc for rationale. */
    private static final long DEFAULT_TIMEOUT_MS = 1000;

    private DhDimensionTracker() {}

    /**
     * Set the flag. Auto-clears after the given timeout (in milliseconds).
     * The timeout is a safety net — under normal synchronous DH creation
     * the flag is consumed within microseconds of being set.
     */
    public static void setHostingDimensionLoading(boolean loading, long timeoutMs) {
        hostingDimensionLoading = loading;
        if (loading) {
            deadline = System.currentTimeMillis() + timeoutMs;
        }
    }

    /**
     * Set the flag with the default 1-second timeout.
     */
    public static void setHostingDimensionLoading(boolean loading) {
        setHostingDimensionLoading(loading, DEFAULT_TIMEOUT_MS);
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
