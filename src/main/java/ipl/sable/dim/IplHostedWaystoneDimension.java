package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * The visible dimension of a hosted-ship waystone.
 *
 * <p>Waystones+Sable substitutes {@code WaystoneDelegate} wrappers into the teleport
 * context (visible source position, tracked target) — correct on stock Sable, where the
 * ship and its plot share one dimension. Hosted, the wrapper's inherited
 * {@code getDimension()} still reports the storage dim {@code ipl_sable:sublevels}, so
 * Waystones' source-range check fails its dimension-equality test ("You moved too far
 * away from the waystone") and target resolution aims at the hosting void.
 *
 * <p>This helper maps a delegate's dimension to the owning ship's PARENT dimension:
 * resolve the wrapped waystone's PLOT position (the delegate's, not the wrapper's
 * overridden visible one) to its hosted sub-level, and report where that ship actually
 * is. Reflection keeps us free of a compile-time Waystones dependency; the flow only
 * runs during teleport events, so the cost is irrelevant.
 */
public final class IplHostedWaystoneDimension {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-waystone-dim");

    /** Resolved on the DECLARING types (delegate base class + Waystone interface), so
     *  they are invocable on every implementation — a first-seen-class cache broke the
     *  moment a second delegate type came through ("not an instance of declaring
     *  class"). */
    private static volatile Class<?> DELEGATE_CLASS;
    private static volatile Method GET_DELEGATE;
    private static volatile Method GET_POS;
    private static volatile boolean REFLECTION_BROKEN;

    private IplHostedWaystoneDimension() {}

    public static ResourceKey<Level> visibleDimension(
        Object delegateWaystone, ResourceKey<Level> original
    ) {
        if (original != SableSubLevelDimension.SUBLEVELS || REFLECTION_BROKEN) {
            return original;
        }
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return original;
            ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(server);
            if (hosting == null) return original;

            if (GET_POS == null) {
                ClassLoader loader = delegateWaystone.getClass().getClassLoader();
                DELEGATE_CLASS = loader.loadClass("net.blay09.mods.waystones.api.WaystoneDelegate");
                GET_DELEGATE = DELEGATE_CLASS.getMethod("getDelegate");
                GET_POS = loader.loadClass("net.blay09.mods.waystones.api.Waystone")
                    .getMethod("getPos");
            }

            // Unwrap to the INNERMOST waystone: wrappers override getPos with the
            // visible position; the plot position lives on the raw waystone.
            Object inner = delegateWaystone;
            while (DELEGATE_CLASS.isInstance(inner)) {
                Object next = GET_DELEGATE.invoke(inner);
                if (next == null || next == inner) break;
                inner = next;
            }
            BlockPos plotPos = (BlockPos) GET_POS.invoke(inner);
            if (plotPos == null) return original;

            SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
            if (container == null) return original;
            LevelPlot plot = container.getPlot(plotPos.getX() >> 4, plotPos.getZ() >> 4);
            SubLevel sub = plot == null ? null : plot.getSubLevel();
            if (sub == null || sub.isRemoved()) return original;
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            return parent != null ? parent.dimension() : original;
        } catch (Throwable t) {
            REFLECTION_BROKEN = true;
            LOG.warn("[IPL-WAYSTONE-DIM] delegate reflection failed — hosted waystone "
                + "dimension mapping disabled", t);
            return original;
        }
    }
}
