package ipl.sable.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static counters + periodic summary log for the source-clip mixins. Lets us see
 * which render path (Vanilla vs Sodium) is firing at runtime, and how often the
 * straddle check actually produced a clip plane vs returned null.
 *
 * <p>One summary every 5 seconds; counters reset each emission.
 */
public final class SourceClipDiag {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-clip");

    public static volatile long vanillaCalls = 0;
    public static volatile long vanillaInstalls = 0;
    public static volatile long sodiumCalls = 0;
    public static volatile long sodiumInstalls = 0;

    private static volatile long lastReportNanos = 0L;

    private SourceClipDiag() {}

    public static void onVanillaCall(boolean installed) {
        vanillaCalls++;
        if (installed) vanillaInstalls++;
        maybeReport();
    }

    public static void onSodiumCall(boolean installed) {
        sodiumCalls++;
        if (installed) sodiumInstalls++;
        maybeReport();
    }

    private static void maybeReport() {
        long now = System.nanoTime();
        if (lastReportNanos == 0L) {
            lastReportNanos = now;
            return;
        }
        if (now - lastReportNanos >= 5_000_000_000L) {
            // Only emit if there was activity in this window; otherwise stay silent.
            if (vanillaCalls > 0 || sodiumCalls > 0) {
                LOG.info("[IPL-CLIP] last 5s: vanilla calls={} installs={}, sodium calls={} installs={}",
                    vanillaCalls, vanillaInstalls, sodiumCalls, sodiumInstalls);
            }
            vanillaCalls = 0;
            vanillaInstalls = 0;
            sodiumCalls = 0;
            sodiumInstalls = 0;
            lastReportNanos = now;
        }
    }
}
