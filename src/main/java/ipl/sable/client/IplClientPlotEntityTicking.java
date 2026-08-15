package ipl.sable.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.mixin.client.IplClientEntityStorageAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client mirror of {@code IplParentPlotEntityTicking}. A parent-level entity parked at
 * hosted plot coordinates sits in a parent {@code ClientLevel} chunk that never loads
 * client-side (the plot's chunks live in the hosting client level), so the
 * {@code TransientEntitySectionManager} never marks its section ticking and the client
 * copy silently stops ticking: {@code plungedTime} animations freeze, orientation pinning
 * stops, smoothing state goes stale — the entity renders in whatever transient state it
 * had (the plunger's drooping never-connected rope spline). Mark the plot chunk ticking
 * in the parent client level's entity manager for as long as plot-parked entities hold it.
 *
 * <p>All mutation happens on the client main thread (entity moves arrive via packet
 * application and client tick), so no cross-thread anchoring is needed here — and client
 * and server copies can never alias each other in this map because each side's tracker
 * only ever sees its own level type.
 */
public final class IplClientPlotEntityTicking {

    private record Membership(ClientLevel level, ChunkPos chunk) {}

    private static final Map<Entity, Membership> MEMBERSHIPS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Membership, Integer> ACTIVE_COUNTS = new HashMap<>();

    private IplClientPlotEntityTicking() {}

    public static void update(Entity entity, double x, double z) {
        if (!(entity.level() instanceof ClientLevel level)) return;

        Membership next = null;
        if (!IplDimAgnostic.isHostingLevel(level) && !entity.isRemoved()) {
            SubLevelContainer hosting = IplClientHostedLookup.getHostingContainerOrNull();
            int chunkX = ((int) Math.floor(x)) >> 4;
            int chunkZ = ((int) Math.floor(z)) >> 4;
            if (hosting != null && hosting.inBounds(chunkX, chunkZ)) {
                LevelPlot plot = hosting.getPlot(chunkX, chunkZ);
                SubLevel sub = plot == null ? null : plot.getSubLevel();
                if (sub != null && !sub.isRemoved()
                    && IplDimAgnostic.getParentLevel(sub) == level) {
                    next = new Membership(level, new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        Membership previous = MEMBERSHIPS.get(entity);
        if (next != null && next.equals(previous)) return;
        if (previous != null) release(previous);
        if (next == null) {
            MEMBERSHIPS.remove(entity);
            return;
        }

        MEMBERSHIPS.put(entity, next);
        retain(next);
    }

    public static void remove(Entity entity) {
        Membership previous = MEMBERSHIPS.remove(entity);
        if (previous != null) release(previous);
    }

    private static synchronized void retain(Membership membership) {
        int count = ACTIVE_COUNTS.getOrDefault(membership, 0);
        ACTIVE_COUNTS.put(membership, count + 1);
        if (count == 0) {
            ((IplClientEntityStorageAccessor) membership.level).ipl$entityStorage()
                .startTicking(membership.chunk);
        }
    }

    private static synchronized void release(Membership membership) {
        int count = ACTIVE_COUNTS.getOrDefault(membership, 0);
        if (count > 1) {
            ACTIVE_COUNTS.put(membership, count - 1);
            return;
        }
        ACTIVE_COUNTS.remove(membership);
        ((IplClientEntityStorageAccessor) membership.level).ipl$entityStorage()
            .stopTicking(membership.chunk);
    }
}
