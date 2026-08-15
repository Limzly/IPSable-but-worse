package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Route hosted plot block entities' FLYWHEEL VISUALS into the PARENT dimension's
 * visualization world.
 *
 * <p>Flywheel registers a BE visual with the {@code VisualizationManager} of the level the
 * BE is added to — for hosted plots that's {@code ipl_sable:sublevels}, whose renderer never
 * runs, so the visual never draws. Mod renderers meanwhile skip their vanilla path when
 * {@code VisualizationManager.supportsVisualization(be.getLevel())} — net effect: swivel
 * bearings, throttle levers and every other Flywheel-visualized ship part rendered NOTHING.
 *
 * <p>Re-registering the BE with the PARENT level's manager (public
 * {@code VisualizationManager.get(parent).blockEntities().queueAdd/queueRemove} API) drops
 * it into Sable's own Flywheel integration: {@code BlockEntityStorageMixin.sable$createVisual}
 * resolves the containing sub-level from the block entity's honest hosting level and embeds
 * the visual in a per-sub-level {@code VisualEmbedding} whose transform follows
 * {@code renderPose()} every frame — the stock pipeline, in the world that actually renders.
 *
 * <p>Lifecycle is self-tracked (weak maps): chunk add/remove/clear hooks
 * ({@code IplHostedFlywheelVisualRerouteMixin}) and parent flips
 * ({@code IplParentDimSync} → {@link #onParentFlip}) re-home the visuals.
 */
public final class IplClientFlywheelReroute {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-flywheel-reroute");

    /** Which parent manager each hosted BE's visual was queued into. Weak keys: BEs unload. */
    private static final Map<BlockEntity, ClientLevel> QUEUED_UNDER =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** Hosted BEs per sub-level UUID, for parent-flip re-homing. Weak BE sets. */
    private static final Map<UUID, Set<BlockEntity>> SUB_BES =
        Collections.synchronizedMap(new java.util.HashMap<>());

    /** Tri-state Flywheel availability: null = unprobed. */
    private static volatile Boolean flywheelAvailable;

    private IplClientFlywheelReroute() {}

    /**
     * Safety-net sweep, run from the hosting container's client tick: Sable manages plot
     * chunks through its own pipeline, so vanilla {@code LevelChunk} add hooks can miss
     * plot BEs entirely (and a BE can arrive before its sub-level's parent is synced).
     * Cheap: already-queued BEs are one weak-map hit.
     */
    public static void sweepHostedContainer(dev.ryanhcode.sable.api.sublevel.SubLevelContainer container) {
        if (!ipl$flywheelPresent()) return;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved()) continue;
            Level parentRaw = ((ipl.sable.duck.IplSubLevelDuck) sub).ipl$getParentLevel();
            if (!(parentRaw instanceof ClientLevel parent)
                || ipl.sable.dim.IplDimAgnostic.isHostingLevel(parent)) continue;

            for (dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                net.minecraft.world.level.chunk.LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be == null || be.isRemoved() || QUEUED_UNDER.containsKey(be)) continue;
                    if (queueAdd(parent, be)) {
                        QUEUED_UNDER.put(be, parent);
                        SUB_BES.computeIfAbsent(sub.getUniqueId(),
                                id -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())))
                            .add(be);
                    }
                }
            }
        }
    }

    public static void onBlockEntityAdded(Level chunkLevel, BlockEntity be) {
        if (!ipl.sable.dim.IplDimAgnostic.isHostingLevel(chunkLevel)) return;
        BlockPos pos = be.getBlockPos();
        SubLevel sub = ipl$owningSub(chunkLevel, pos);
        if (sub == null) return;
        ClientLevel parent = resolveParentForPlotPos(chunkLevel, pos);
        if (parent == null) return; // parent not synced yet; renderer falls back until reload

        if (queueAdd(parent, be)) {
            QUEUED_UNDER.put(be, parent);
            SUB_BES.computeIfAbsent(sub.getUniqueId(),
                    id -> Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>())))
                .add(be);
        }
    }

    public static void onBlockEntityRemoved(Level chunkLevel, BlockEntity be) {
        if (!ipl.sable.dim.IplDimAgnostic.isHostingLevel(chunkLevel)) return;
        ClientLevel target = QUEUED_UNDER.remove(be);
        if (target != null) {
            queueRemove(target, be);
        }
    }

    /**
     * A hosted sub-level's client parent flipped (cross-portal transit): re-home every
     * tracked visual from the old parent's manager into the new one.
     */
    public static void onParentFlip(SubLevel sub, @Nullable ClientLevel oldParent, @Nullable ClientLevel newParent) {
        Set<BlockEntity> bes = SUB_BES.get(sub.getUniqueId());
        if (bes == null) return;
        synchronized (bes) {
            for (BlockEntity be : bes) {
                if (be == null || be.isRemoved()) continue;
                ClientLevel queued = QUEUED_UNDER.get(be);
                if (queued != null) {
                    queueRemove(queued, be);
                }
                if (newParent != null && queueAdd(newParent, be)) {
                    QUEUED_UNDER.put(be, newParent);
                } else {
                    QUEUED_UNDER.remove(be);
                }
            }
        }
    }

    @Nullable
    private static SubLevel ipl$owningSub(Level hosting, BlockPos pos) {
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(hosting);
        if (container == null) return null;
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot =
            container.getPlot(pos.getX() >> 4, pos.getZ() >> 4);
        if (plot == null) return null;
        SubLevel sub = plot.getSubLevel();
        return sub == null || sub.isRemoved() ? null : sub;
    }

    @Nullable
    private static ClientLevel resolveParentForPlotPos(Level hosting, BlockPos pos) {
        SubLevel sub = ipl$owningSub(hosting, pos);
        if (sub == null) return null;
        Level parent = ((ipl.sable.duck.IplSubLevelDuck) sub).ipl$getParentLevel();
        return parent instanceof ClientLevel client && !ipl.sable.dim.IplDimAgnostic.isHostingLevel(client)
            ? client : null;
    }

    // ------------------------------------------------------------------
    // Flywheel API surface, isolated behind an availability probe.
    // ------------------------------------------------------------------

    private static boolean queueAdd(ClientLevel parent, BlockEntity be) {
        if (!ipl$flywheelPresent()) return false;
        try {
            var manager = dev.engine_room.flywheel.api.visualization.VisualizationManager.get(parent);
            if (manager == null) return false; // backend off → vanilla BER path renders instead
            manager.blockEntities().queueAdd(be);
            return true;
        } catch (Throwable t) {
            ipl$disable(t);
            return false;
        }
    }

    private static void queueRemove(ClientLevel parent, BlockEntity be) {
        if (!ipl$flywheelPresent()) return;
        try {
            var manager = dev.engine_room.flywheel.api.visualization.VisualizationManager.get(parent);
            if (manager != null) {
                manager.blockEntities().queueRemove(be);
            }
        } catch (Throwable t) {
            ipl$disable(t);
        }
    }

    private static boolean ipl$flywheelPresent() {
        Boolean available = flywheelAvailable;
        if (available == null) {
            try {
                Class.forName("dev.engine_room.flywheel.api.visualization.VisualizationManager");
                available = Boolean.TRUE;
            } catch (Throwable t) {
                available = Boolean.FALSE;
                LOG.info("[IPL-FLW] Flywheel not present; hosted BE visual reroute disabled");
            }
            flywheelAvailable = available;
        }
        return available;
    }

    private static void ipl$disable(Throwable t) {
        if (flywheelAvailable != Boolean.FALSE) {
            flywheelAvailable = Boolean.FALSE;
            LOG.warn("[IPL-FLW] disabling hosted BE visual reroute", t);
        }
    }
}
