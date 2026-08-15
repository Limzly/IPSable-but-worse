package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Thread-local "which parent dimension is this hosted code REALLY acting in" context.
 *
 * <p>Sable's Create-compat layer (drills, deployers, saws, harvesters) maps positions out of
 * a sub-level through {@code logicalPose()} into parent-frame world coordinates, then reads
 * and writes them through the block entity's own level. Pre-dim-agnostic that level WAS the
 * parent dimension, so plot reads and terrain reads hit the same world. Hosted, the BE's
 * level is {@code ipl_sable:sublevels} — empty at world coordinates — so every terrain
 * interaction silently sees air.
 *
 * <p>This context is set around each plot-chunk block entity tick (see
 * {@code IplHostedBeTickContextMixin}), resolving the ticking BE's plot → owner sub-level →
 * parent level. {@code IplHostedWorldFrameRouterMixin} then routes world-frame (non-plot)
 * block access on the hosting level to this parent. The pair is the mirror image of
 * {@code IplPlotDeferredLogicMixin}, which routes plot-frame access from parent levels INTO
 * the hosting level.
 */
public final class IplWorldFrameContext {

    private static final ThreadLocal<ServerLevel> CURRENT = new ThreadLocal<>();

    private IplWorldFrameContext() {}

    /** The parent level hosted BE code is conceptually acting in, or null outside a hosted BE tick. */
    @Nullable
    public static ServerLevel current() {
        return CURRENT.get();
    }

    /** Set the context, returning the previous value for restoration in a finally block. */
    @Nullable
    public static ServerLevel push(ServerLevel parent) {
        ServerLevel prev = CURRENT.get();
        CURRENT.set(parent);
        return prev;
    }

    public static void pop(@Nullable ServerLevel prev) {
        CURRENT.set(prev);
    }

    /**
     * For a block entity ticking at {@code pos} on {@code level}: if this is a plot-chunk BE
     * on the hosting dimension, the parent level of the owning sub-level. Null otherwise.
     */
    @Nullable
    public static ServerLevel resolveParentForPlotBe(Level level, BlockPos pos) {
        if (!IplDimAgnostic.isHostingLevel(level)) return null;
        if (!(level instanceof ServerLevel hosting)) return null;
        // The grid begins near 20M, but use the actual container occupancy rather than a
        // coarse magnitude gate: valid parent-world terrain near the world border must not
        // be mistaken for plot storage.

        SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
        if (container == null) return null;
        if (!container.inBounds(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        LevelPlot plot = container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null) return null;
        return IplDimAgnostic.getServerParentLevel(subLevel);
    }

    /**
     * For an INTERACTION (use / attack) at a plot position reached from ANY server level:
     * the parent level of the owning hosted sub-level. Unlike
     * {@link #resolveParentForPlotBe} the context level here is usually the PLAYER's level
     * — Sable maps ship clicks to plot coordinates, and the plot bridge resolves them from
     * every dimension — so the plot is looked up through the hosting container directly.
     */
    @Nullable
    public static ServerLevel resolveParentForPlotInteraction(ServerLevel contextLevel, BlockPos pos) {
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(contextLevel);
        if (hosting == null) return null;
        if (!hosting.inBounds(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        LevelPlot plot = hosting.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.isRemoved()) return null;
        return IplDimAgnostic.getServerParentLevel(subLevel);
    }

    // ------------------------------------------------------------------
    // Deferred frames — the universal packet bridge.
    // ------------------------------------------------------------------

    /**
     * A modded packet handler cannot name its sub-level at ingress, so it cannot be
     * armed like the BE/physics/interaction seams. But every such handler that touches a
     * ship resolves PLOT coordinates early (the payload carries the block's plot-space
     * pos — that is how it finds its block entity), and every plot resolution funnels
     * through {@code SubLevelContainer.getPlot} — which the plot bridge intercepts.
     *
     * <p>So: the payload dispatch seam opens a DEFERRED frame (owner unknown), and the
     * first hosted-plot resolution inside it ({@link #notifyPlotResolved}, called from
     * the plot bridge) arms the parent context for the remainder of the handler. One
     * injection at NeoForge's payload context covers every addon's packets — no per-mod
     * mixins (the Simulated assembler being the canonical case).
     */
    private static final ThreadLocal<Boolean> DEFERRED = new ThreadLocal<>();

    /** Saved state for restoring at frame end (frames may nest via enqueueWork). */
    public record DeferredFrame(boolean prevDeferred, @Nullable ServerLevel prevParent) {}

    public static boolean deferredActive() {
        return Boolean.TRUE.equals(DEFERRED.get());
    }

    /** Any world frame open on this thread — armed (owner known) or deferred (packet
     *  handler, owner pending). Gates frame-scoped bridges like the hosted-ship
     *  enumeration in the plot bridge. */
    public static boolean frameActive() {
        return CURRENT.get() != null || deferredActive();
    }

    public static DeferredFrame beginDeferredFrame() {
        DeferredFrame frame = new DeferredFrame(deferredActive(), CURRENT.get());
        DEFERRED.set(Boolean.TRUE);
        return frame;
    }

    public static void endDeferredFrame(DeferredFrame frame) {
        DEFERRED.set(frame.prevDeferred());
        CURRENT.set(frame.prevParent());
    }

    /**
     * A plot resolved to a hosted sub-level while a deferred frame is open and unarmed:
     * arm the frame with that ship's parent. First hosted hit wins; later resolutions
     * (rare multi-ship handlers) keep the first owner, matching the BE-tick seam's
     * one-owner scoping.
     */
    public static void notifyPlotResolved(@Nullable LevelPlot plot) {
        if (plot == null || !deferredActive() || CURRENT.get() != null) return;
        SubLevel subLevel = plot.getSubLevel();
        if (subLevel == null || subLevel.isRemoved() || !IplDimAgnostic.isHosted(subLevel)) {
            return;
        }
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        if (parent != null) {
            CURRENT.set(parent);
        }
    }
}
