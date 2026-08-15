package ipl.sable.atlas;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Skip all physics processing for hosted ships whose parent-pointer chunks are unloaded.
 *
 * <p>Stock Sable never simulates a sub-level over unloaded terrain — the holding system
 * serializes it out of the world first. Dim-agnostic hosting keeps ships always-live in
 * always-loaded plot chunks, which silently dropped that invariant: a nether ship with no
 * players in the nether simulates in mid-air against a chart whose terrain was never
 * baked, and falls into the void.
 *
 * <p>Restoration, vanilla-flavored: while the parent chunk under a ship's origin is not
 * loaded (the same {@code isChunkLoadedEnough} block-ticking test the terrain enrollment
 * uses), the ship goes DORMANT — its native body switches to Fixed (no integration, no
 * gravity, immovable; still a valid rope/joint anchor) and the enrollment pass skips it
 * entirely. No chunks are force-loaded. A player wandering back re-loads the area, the
 * gate flips, the body returns to Dynamic and wakes at rest exactly where it froze.
 *
 * <p>The freeze is applied on transition and re-asserted from the hosted body-add seam
 * ({@code onHostedBodyAdded}, guard-mixin TAIL) so a body recreated mid-dormancy (rehome
 * twin swap, deserialization restore) is Fixed from its first tick — plus a slow periodic
 * re-assert as belt-and-braces. Kill switch: {@code -Dipl.sable.parentLoadGate=false}.
 */
public final class IplHostedTerrainGate {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-terrain-gate");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.parentLoadGate", "true"));

    private static final Set<UUID> DORMANT = new HashSet<>();

    private IplHostedTerrainGate() {}

    /**
     * Called from the parent-level enrollment pass for each hosted ship of that level.
     * Returns true when the ship is dormant and the caller should skip all further
     * processing (terrain enrollment, velocity prediction) for it this tick.
     */
    public static boolean tick(ServerLevel parent, PhysicsPipeline pipeline, ServerSubLevel sub) {
        if (!ENABLED) return false;

        var pos = sub.logicalPose().position();
        int cx = SectionPos.blockToSectionCoord(Mth.floor(pos.x()));
        int cz = SectionPos.blockToSectionCoord(Mth.floor(pos.z()));
        UUID id = sub.getUniqueId();

        if (PhysicsChunkTicketManager.isChunkLoadedEnough(parent, cx, cz)) {
            if (DORMANT.remove(id)) {
                setDormant(sub, false);
                pipeline.wakeUp(sub);
                LOG.info("[IPL-TERRAIN-GATE] woke ship {} in {} — parent chunk [{}, {}] loaded",
                    id, parent.dimension().location(), cx, cz);
            }
            return false;
        }

        if (DORMANT.add(id)) {
            setDormant(sub, true);
            LOG.info("[IPL-TERRAIN-GATE] ship {} dormant in {} — parent chunk [{}, {}] not "
                + "loaded", id, parent.dimension().location(), cx, cz);
        } else if ((parent.getGameTime() & 63) == 0) {
            setDormant(sub, true); // slow re-assert: belt-and-braces for unknown re-add paths
        }
        return true;
    }

    /**
     * Hosted body-add seam (guard-mixin TAIL — fires for rehome twin swaps and
     * deserialization restores): a body recreated while its ship is dormant must be
     * Fixed from its FIRST tick, not after the next enrollment pass re-polls.
     */
    public static void onHostedBodyAdded(ServerSubLevel sub) {
        if (!ENABLED || !DORMANT.contains(sub.getUniqueId())) return;
        setDormant(sub, true);
    }

    /** Prune dormancy entries for ships no longer in the hosting container. */
    public static void retainLive(Iterable<? extends ServerSubLevel> subLevels) {
        if (DORMANT.isEmpty()) return;
        Set<UUID> live = new HashSet<>();
        for (ServerSubLevel sub : subLevels) {
            if (!sub.isRemoved()) live.add(sub.getUniqueId());
        }
        DORMANT.retainAll(live);
    }

    private static void setDormant(ServerSubLevel sub, boolean dormant) {
        if (!IplRapierNatives.isAvailable()) return;
        long scene = IplSceneOwnership.liveSceneHandle((ServerLevel) sub.getLevel());
        if (scene == 0) return;
        IplRapierNatives.setBodyDormant(scene, Rapier3D.getID(sub), dormant);
    }

    public static void clearAll() {
        DORMANT.clear();
    }
}
