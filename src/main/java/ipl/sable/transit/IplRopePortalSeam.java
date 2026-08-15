package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRopeObjectAccess;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Map;

/**
 * Ropes span portals instead of gating them.
 *
 * <p>A rope-attached ship transits under the normal declarative rules. Its rope stays
 * behind: the chain keeps solving in its home frame, and the transited ship's end joint
 * re-targets to a ground anchor that tracks the ship's IMAGE through the portal isometry
 * (native {@code setRopePortalPrefix} + per-tick anchor drive in the rope tick). Tension
 * therefore pulls the trailing body toward and through the aperture — a portal window,
 * not a teleport. When the other end's ship crosses too, the accumulated end prefixes
 * match and the whole chain is remapped through the portal in one step
 * ({@code remapRope}: particles teleport, chart restamps) and both ends re-attach
 * normally. A return trip composes the inverse and unwinds to identity.
 *
 * <p>One-way coupling by design: the transited ship feels no rope reaction (the ground
 * anchor absorbs it). The break monitor bounds the artifact — split ropes past
 * {@code -Dipl.sable.ropeBreakStretch} (default 1.75× natural length, e.g. a snagged
 * trailing ship) BREAK, vanilla-lead-style; any rope past 4× breaks too (catches
 * relog-orphaned seams, whose runtime prefixes are lost). Kill switch:
 * {@code -Dipl.sable.ropePortalSeam=false}.
 */
public final class IplRopePortalSeam {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-rope-seam");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.ropePortalSeam", "true"));

    private static final double BREAK_STRETCH = Double.parseDouble(
        System.getProperty("ipl.sable.ropeBreakStretch", "1.75"));

    private static final double EXTREME_STRETCH = 4.0;

    /** Accumulated prefix per rope END (key = ropeId << 1 | endBit): the isometry
     *  mapping the attached ship's CURRENT frame back into the chain's home frame. */
    private static final Map<Long, Prefix> PREFIXES = new HashMap<>();

    private static final class Prefix {
        final Quaterniond rot;
        final Vector3d trans;

        Prefix(Quaterniond rot, Vector3d trans) {
            this.rot = rot;
            this.trans = trans;
        }

        boolean nearIdentity() {
            return Math.abs(rot.w) > 1.0 - 1.0e-9 && trans.lengthSquared() < 1.0e-12;
        }

        boolean nearlyEquals(Prefix other) {
            // Loose on purpose: the two ends' prefixes are derived from the same portal
            // on different ticks — centimeter-scale agreement means "same seam".
            return Math.abs(rot.dot(other.rot)) > 1.0 - 1.0e-6
                && trans.distanceSquared(other.trans) < 1.0e-4;
        }

        String describe() {
            return String.format("t=(%.3f,%.3f,%.3f) q=(%.4f,%.4f,%.4f,%.4f)",
                trans.x, trans.y, trans.z, rot.x, rot.y, rot.z, rot.w);
        }
    }

    private IplRopePortalSeam() {}

    /** A ship just transited {@code portal} (leader or rigid mate). Re-seam its ropes. */
    public static void onShipTransit(ServerSubLevel ship, Portal portal) {
        if (!ENABLED || !IplRapierNatives.isAvailable()) return;
        try {
            long scene = IplSceneOwnership.liveSceneHandle((ServerLevel) ship.getLevel());
            if (scene == 0) return;
            long[] ends = IplRapierNatives.ropesAttachedToSableBody(
                scene, Rapier3D.getID(ship));
            if (ends.length == 0) return;

            // The transit isometry M (source→dest) and its inverse.
            IplStraddlePoseMap.StraddleMapping mapping =
                IplStraddlePoseMap.StraddleMapping.of(portal);
            Quaterniond mRot = mapping.mapQuat(new Quaterniond());
            Vec3 mT = mapping.mapPoint(Vec3.ZERO);
            Quaterniond invRot = mRot.conjugate(new Quaterniond());
            Vector3d invT = invRot.transform(new Vector3d(mT.x, mT.y, mT.z)).negate();

            for (long key : ends) {
                Prefix old = PREFIXES.get(key);
                // P' = P_old ∘ M⁻¹ (identity P_old when unset).
                Prefix next = old == null
                    ? new Prefix(new Quaterniond(invRot), new Vector3d(invT))
                    : new Prefix(
                        old.rot.mul(invRot, new Quaterniond()),
                        old.rot.transform(new Vector3d(invT)).add(old.trans));

                long ropeId = key >>> 1;
                boolean end = (key & 1) != 0;
                if (next.nearIdentity()) {
                    PREFIXES.remove(key);
                    IplRapierNatives.setRopePortalPrefix(scene, ropeId, end, false,
                        0, 0, 0, 0, 0, 0, 1);
                    LOG.info("[IPL-ROPE-SEAM] rope {} end {} unwound to identity", ropeId, end);
                    continue;
                }
                PREFIXES.put(key, next);
                IplRapierNatives.setRopePortalPrefix(scene, ropeId, end, true,
                    next.trans.x, next.trans.y, next.trans.z,
                    next.rot.x, next.rot.y, next.rot.z, next.rot.w);

                // Both ends now in the SAME displaced frame → the whole chain follows:
                // remap particles through the common mapping, restamp to the ships'
                // chart, clear both prefixes.
                Prefix other = PREFIXES.get(key ^ 1L);
                LOG.info("[IPL-ROPE-SEAM] rope {} end {} seamed through portal {} [{}] "
                    + "other={}", ropeId, end, portal.getUUID(), next.describe(),
                    other == null ? "absent" : (next.nearlyEquals(other) ? "match" : "MISMATCH"));
                if (other != null && !next.nearlyEquals(other)) {
                    LOG.warn("[IPL-ROPE-SEAM] rope {} seam MISMATCH: this end [{}] vs "
                        + "other end [{}] — chain stays split", ropeId,
                        next.describe(), other.describe());
                }
                if (other != null && next.nearlyEquals(other)) {
                    Quaterniond chainRot = next.rot.conjugate(new Quaterniond());
                    Vector3d chainT = chainRot.transform(new Vector3d(next.trans)).negate();
                    ServerLevel parent = IplDimAgnostic.getServerParentLevel(ship);
                    long destScene = IplSceneOwnership.liveSceneHandle(parent);
                    IplRapierNatives.remapRope(scene, ropeId,
                        chainT.x, chainT.y, chainT.z,
                        chainRot.x, chainRot.y, chainRot.z, chainRot.w,
                        destScene);
                    IplRapierNatives.setRopePortalPrefix(scene, ropeId, false, false,
                        0, 0, 0, 0, 0, 0, 1);
                    IplRapierNatives.setRopePortalPrefix(scene, ropeId, true, false,
                        0, 0, 0, 0, 0, 0, 1);
                    PREFIXES.remove(key);
                    PREFIXES.remove(key ^ 1L);
                    LOG.info("[IPL-ROPE-SEAM] rope {} re-unified — chain remapped through "
                        + "the portal", ropeId);
                }
            }
        } catch (Throwable t) {
            LOG.error("[IPL-ROPE-SEAM] seam update failed for ship {}",
                ship.getUniqueId(), t);
        }
    }

    /** Break monitor + seam-state prune. Called once per hosting-container tick. */
    public static void tick(ServerSubLevelContainer hostingContainer) {
        if (!ENABLED || !IplRapierNatives.isAvailable()) return;
        SubLevelPhysicsSystem hostingSystem = hostingContainer.physicsSystem();
        ServerLevel hostingLevel = hostingSystem.getLevel();
        if (hostingLevel == null || (hostingLevel.getGameTime() % 20) != 0) return;

        try {
            long scene = IplSceneOwnership.liveSceneHandle(hostingLevel);
            if (scene == 0) return;

            long[] split = IplRapierNatives.overstretchedRopes(scene, BREAK_STRETCH);
            long[] extreme = IplRapierNatives.overstretchedRopes(scene, EXTREME_STRETCH);
            if (split.length == 0 && extreme.length == 0 && PREFIXES.isEmpty()) return;

            MinecraftServer server = hostingLevel.getServer();
            java.util.Set<Long> liveRopeIds = new java.util.HashSet<>();
            for (ServerLevel level : server.getAllLevels()) {
                var container = SubLevelContainer.getContainer(level);
                if (!(container instanceof ServerSubLevelContainer serverContainer)) continue;
                SubLevelPhysicsSystem system = serverContainer.physicsSystem();
                java.util.List<RopePhysicsObject> toBreak = new java.util.ArrayList<>(0);
                for (ArbitraryPhysicsObject obj : system.getArbitraryObjects()) {
                    if (!(obj instanceof RopePhysicsObject rope)) continue;
                    if (!(((IplRopeObjectAccess) rope).ipl$handle()
                        instanceof RapierRopeHandle handle)) continue;
                    liveRopeIds.add(handle.handle());
                    boolean isSplit = PREFIXES.containsKey(handle.handle() << 1)
                        || PREFIXES.containsKey((handle.handle() << 1) | 1L);
                    if ((isSplit && contains(split, handle.handle()))
                        || contains(extreme, handle.handle())) {
                        toBreak.add(rope);
                    }
                }
                for (RopePhysicsObject rope : toBreak) {
                    long id = ((RapierRopeHandle) ((IplRopeObjectAccess) rope).ipl$handle())
                        .handle();
                    LOG.info("[IPL-ROPE-SEAM] rope {} overstretched — breaking", id);
                    system.removeObject(rope);
                    PREFIXES.remove(id << 1);
                    PREFIXES.remove((id << 1) | 1L);
                }
            }
            // Prune seam state for ropes that no longer exist anywhere — clearing the
            // NATIVE prefix too, so Java and native seam state never drift apart.
            final long sceneF = scene;
            PREFIXES.keySet().removeIf(key -> {
                if (liveRopeIds.contains(key >>> 1)) return false;
                LOG.info("[IPL-ROPE-SEAM] pruning seam state for dead rope {} end {}",
                    key >>> 1, (key & 1) != 0);
                IplRapierNatives.setRopePortalPrefix(sceneF, key >>> 1, (key & 1) != 0,
                    false, 0, 0, 0, 0, 0, 0, 1);
                return true;
            });
        } catch (Throwable t) {
            LOG.error("[IPL-ROPE-SEAM] break monitor failed", t);
        }
    }

    private static boolean contains(long[] ids, long id) {
        for (long candidate : ids) {
            if (candidate == id) return true;
        }
        return false;
    }

    public static void clearAll() {
        PREFIXES.clear();
    }
}
