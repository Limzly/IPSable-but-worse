package ipl.sable.dim;

import java.util.function.Supplier;

/**
 * Wraps modded packet work in a {@link IplWorldFrameContext} deferred frame — see the
 * deferred-frame section there for the design. Applied by
 * {@code IplModdedPayloadFrameMixin} to everything scheduled through NeoForge's
 * {@code ServerPayloadContext.enqueueWork}, which is where every registrar-registered
 * handler's main-thread body runs (Veil's manager — Simulated, Aeronautics — registers
 * through the same registrar).
 *
 * <p>Cost when no ship is touched: two thread-local writes per packet and one
 * thread-local read per plot lookup. Kill switch:
 * {@code -Dipl.sable.packetFrameBridge=false}.
 */
public final class IplPacketFrameBridge {

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.packetFrameBridge", "true"));

    private IplPacketFrameBridge() {}

    public static Runnable wrap(Runnable task) {
        if (!ENABLED || task == null) return task;
        return () -> {
            IplWorldFrameContext.DeferredFrame frame = IplWorldFrameContext.beginDeferredFrame();
            try {
                task.run();
            } finally {
                IplWorldFrameContext.endDeferredFrame(frame);
            }
        };
    }

    public static <T> Supplier<T> wrap(Supplier<T> task) {
        if (!ENABLED || task == null) return task;
        return () -> {
            IplWorldFrameContext.DeferredFrame frame = IplWorldFrameContext.beginDeferredFrame();
            try {
                return task.get();
            } finally {
                IplWorldFrameContext.endDeferredFrame(frame);
            }
        };
    }
}
