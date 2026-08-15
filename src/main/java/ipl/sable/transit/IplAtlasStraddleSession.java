package ipl.sable.transit;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.mixin.IplRapierPipelineAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas straddle sessions (spec v3 §2.2-2.3): image colliders.
 *
 * <p>While a hosted ship straddles a portal, its REAL body gains an image collider in
 * the destination chart — extra geometry portal-prefixed by the full isometry
 * P = (R, t). The engine expresses the imaged side's state in the far frame, so
 * far-side contacts act on the one real body EXACTLY, in-solver: no clone body, no
 * servo, no feedback lag, no authority swap. Multi-straddle is just multiple images
 * on one body; a body never contacts its own image (engine same-parent filter).
 *
 * <p>This class owns the Atlas session registry (one per ship×portal), the image collider
     * lifecycle, the half-open portal-plane clip regions (real set keeps the near half,
     * image set the far half), and the mapping queries the entity/render/router layers
 * use. There is one real rigid body; destination presence is image geometry.
 *
 * <p>Scope: any scale-1 isometry pair.
 */
public final class IplAtlasStraddleSession {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-atlas-straddle");
    /** Match detector admission laterally; clip regions remain a zero-thickness plane. */
    private static final double APERTURE_MARGIN = 0.5;

    private static final Map<StraddleKey, Session> SESSIONS = new HashMap<>();

    private static boolean loggedSkip = false;

    private IplAtlasStraddleSession() {}

    private static final class Session {
        final ServerSubLevel sub;
        final Portal portal;
        final ServerLevel parent;
        final ServerLevel dest;
        /** Sable 2.0: scenes are native long handles held by each level's pipeline. */
        final long parentScene;
        final long destScene;
        final int realId;
        /** Full portal isometry source→dest (rotation-capable; scale gated at 1).
         *  MUTABLE since M5: moving portals re-derive it per tick. */
        IplStraddlePoseMap.StraddleMapping mapping;
        /** Crossing direction at the source plane (refreshed with the mapping). */
        Vec3 sourceToDest;
        /** Portal origin at the last clip-region computation (M5 movement detection). */
        Vec3 lastOrigin = Vec3.ZERO;
        /** The REAL body's portal-plane clip region for this portal (14 doubles). */
        double[] realClipRegion;
        /** The image collider of the REAL body in the dest chart (atlas M2/M4). */
        long imageHandle = -1;

        Session(ServerSubLevel sub, Portal portal, ServerLevel parent, ServerLevel dest,
                IplStraddlePoseMap.StraddleMapping mapping, Vec3 sourceToDest,
                long parentScene, long destScene) {
            this.sub = sub;
            this.portal = portal;
            this.parent = parent;
            this.dest = dest;
            this.mapping = mapping;
            this.sourceToDest = sourceToDest;
            this.parentScene = parentScene;
            this.destScene = destScene;
            this.realId = Rapier3D.getID(sub);
        }
    }

    public static boolean isEnabled() {
        return IplSceneOwnership.isEnabled();
    }

    /** Called each tick a hosted ship is STRADDLING (from the transit controller). */
    public static void onStraddleTick(ServerSubLevel hosted, Portal portal, Vec3 sourceToDest) {
        if (!isEnabled() || !IplDimAgnostic.isHosted(hosted)) return;
        // Never open a session for a ship straddling its OWN anchored portal
        // (defense in depth — the transit controller already skips the pair).
        if (IplShipPortalAnchor.isAnchorShip(portal, hosted.getUniqueId())) return;
        // The atlas natives ARE the physics — without them there is no straddle
        // session at all (and the whole mod is inoperable anyway, see IplFusedStep).
        if (!ipl.sable.natives.IplRapierNatives.isAvailable()) return;

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(hosted);
        if (parent == null) return;
        ServerLevel dest = parent.getServer().getLevel(portal.getDestDim());
        if (dest == null || IplDimAgnostic.isHostingLevel(dest)) return;

        // Scale-1 gate only (spec §3: sub-level bodies traverse isometries). Any fixed
        // rotation is fully supported by the image collider's portal-frame mapping.
        if (Math.abs(portal.getScaling() - 1.0) > 1e-9) {
            if (!loggedSkip) {
                loggedSkip = true;
                LOG.info("[IPL-IMAGE] portal {} has scale != 1; straddle sessions support "
                    + "isometry pairs only — skipping (logged once)", portal.getUUID());
            }
            return;
        }
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.StraddleMapping.of(portal);

        // Atlas keeps the real body in its honest hosting chart. Parent/destination charts
        // contain only image colliders; native body lookup is world-global and therefore the
        // image may be created through either chart view without moving third-party state.
        if (IplSceneOwnership.getBodyHome(hosted) != hosted.getLevel()) return;

        StraddleKey key = new StraddleKey(hosted.getUniqueId(), portal.getUUID());
        Session existing = SESSIONS.get(key);
        if (existing != null) {
            refreshIfMoved(existing, portal, sourceToDest);
            return;
        }

        RapierPhysicsPipeline parentPipeline = IplSceneOwnership.pipelineOf(parent);
        RapierPhysicsPipeline destPipeline = IplSceneOwnership.pipelineOf(dest);
        if (parentPipeline == null || destPipeline == null) return;
        long parentScene = ((IplRapierPipelineAccess) parentPipeline).ipl$sceneHandle();
        long destScene = ((IplRapierPipelineAccess) destPipeline).ipl$sceneHandle();

        Session session = new Session(
            hosted, portal, parent, dest, mapping, sourceToDest, parentScene, destScene);

        // The image collider IS the physics of the straddle (spec v3 §2.2-2.3): extra
        // geometry on the REAL body, portal-prefixed by the full isometry P = (R, t).
        // Contacts on it act on the body exactly, in-solver. No session without it.
        Vec3 shift = mapping.mapPoint(Vec3.ZERO);
        org.joml.Quaterniond rot = mapping.mapQuat(new org.joml.Quaterniond());
        session.imageHandle = ipl.sable.natives.IplRapierNatives.createImageCollider(
            session.destScene, session.realId,
            shift.x, shift.y, shift.z,
            rot.x, rot.y, rot.z, rot.w);
        if (session.imageHandle < 0) {
            LOG.error("[IPL-IMAGE] image collider creation failed for ship {} portal {} — "
                + "no straddle session (real body unavailable in Atlas world?)",
                hosted.getUniqueId(), portal.getUUID());
            return;
        }
        SESSIONS.put(key, session);

        // Contact clipping, half-open finite-aperture seam:
        //  - REAL body set: contacts past the source plane are dropped only inside the
        //    laterally extended doorway; geometry beside the frame remains source-solid.
        //  - IMAGE collider: complementary far-half contacts are dropped in the mapped
        //    doorway, so only the through-part is physically present destination-side.
        {
            Vec3 origin = portal.getOriginPos();
            double depthLimit = clipDepthLimit(hosted);
            session.lastOrigin = origin;
            session.realClipRegion = clipRegion(
                origin, sourceToDest, portal.getAxisW(), portal.getAxisH(),
                portal.getWidth() * 0.5 + APERTURE_MARGIN,
                portal.getHeight() * 0.5 + APERTURE_MARGIN,
                depthLimit);
            applyRealClipRegions(hosted, session.parentScene, session.realId);

            double[] imageRegion = clipRegion(
                mapping.mapPoint(origin),
                mapping.mapVec(sourceToDest).scale(-1.0),
                mapping.mapVec(portal.getAxisW()),
                mapping.mapVec(portal.getAxisH()),
                portal.getWidth() * 0.5 + APERTURE_MARGIN,
                portal.getHeight() * 0.5 + APERTURE_MARGIN,
                depthLimit);
            ipl.sable.natives.IplRapierNatives.setImageClipRegions(
                session.destScene, session.realId, session.imageHandle, imageRegion);
        }

        // Portal containment rims are ALWAYS-ON per portal entity (IplPortalRimManager)
        // — session-scoped rims had a hole: a ship shearing in laterally never starts a
        // session, so nothing existed yet to stop it.

        LOG.info("[IPL-IMAGE] start uuid={} portal={} dest={} rotated={} imageHandle={} destScene={}",
            hosted.getUniqueId(), portal.getUUID(), portal.getDestPos(),
            !mapping.isIdentityRotation(), session.imageHandle, session.destScene);
    }

    /**
     * Atlas M5 (spec v3 §2.8): moving portals. Re-derive the portal isometry each
     * straddle tick; when the portal moved (isometry OR aperture geometry), push the
     * fresh prefix to the image collider and rebuild both halves of the clip seam.
     * Cheap when static (two pose comparisons). Per-TICK granularity: within a tick
     * the isometry is frozen (kinematic-frame fiat, spec §2.8) — the frame-twist
     * velocity term is the M5b follow-up.
     */
    private static void refreshIfMoved(Session s, Portal portal, Vec3 sourceToDest) {
        if (s.sub.isRemoved()) return;
        IplStraddlePoseMap.StraddleMapping fresh = IplStraddlePoseMap.StraddleMapping.of(portal);
        Vec3 origin = portal.getOriginPos();

        Vec3 newShift = fresh.mapPoint(Vec3.ZERO);
        Vec3 oldShift = s.mapping.mapPoint(Vec3.ZERO);
        org.joml.Quaterniond newRot = fresh.mapQuat(new org.joml.Quaterniond());
        org.joml.Quaterniond oldRot = s.mapping.mapQuat(new org.joml.Quaterniond());
        boolean isoMoved = oldShift.distanceToSqr(newShift) > 1.0e-10
            || Math.abs(newRot.dot(oldRot)) < 1.0 - 1.0e-10;
        boolean apertureMoved = s.lastOrigin.distanceToSqr(origin) > 1.0e-10;
        if (!isoMoved && !apertureMoved) return;

        s.mapping = fresh;
        s.sourceToDest = sourceToDest;
        s.lastOrigin = origin;
        double depthLimit = clipDepthLimit(s.sub);

        if (isoMoved) {
            ipl.sable.natives.IplRapierNatives.setImagePrefix(
                s.destScene, s.imageHandle,
                newShift.x, newShift.y, newShift.z,
                newRot.x, newRot.y, newRot.z, newRot.w);
        }

        s.realClipRegion = clipRegion(
            origin, sourceToDest, portal.getAxisW(), portal.getAxisH(),
            portal.getWidth() * 0.5 + APERTURE_MARGIN,
            portal.getHeight() * 0.5 + APERTURE_MARGIN,
            depthLimit);
        applyRealClipRegions(s.sub, s.parentScene, s.realId);
        double[] imageRegion = clipRegion(
            fresh.mapPoint(origin),
            fresh.mapVec(sourceToDest).scale(-1.0),
            fresh.mapVec(portal.getAxisW()),
            fresh.mapVec(portal.getAxisH()),
            portal.getWidth() * 0.5 + APERTURE_MARGIN,
            portal.getHeight() * 0.5 + APERTURE_MARGIN,
            depthLimit);
        ipl.sable.natives.IplRapierNatives.setImageClipRegions(
            s.destScene, s.realId, s.imageHandle, imageRegion);
    }

    /**
     * Tolerance on the NEAR side of the cut. A solver contact whose point lands a hair
     * before the plane while the geometry it belongs to is already past it used to
     * survive the cut -- that residue is what is still "felt" when walking into the
     * doorway from the opposite side.
     */
    private static final double CLIP_SKIN = 1.0e-3;

    /**
     * How far past the plane the cutter reaches: the owning body's own diagonal plus the
     * aperture margin. The native region used to be an UNBOUNDED half-space, so it could
     * neutralize contacts arbitrarily far beyond the portal -- the source of the
     * "microscopically affects other sub-levels" symptom. Bounding it turns the cutter
     * into a closed box around exactly the volume that can be straddling: still Rapier's
     * own contact pass, just scoped to this crossing instead of to all of space.
     */
    private static double clipDepthLimit(ServerSubLevel ship) {
        var box = ship.getPlot().getBoundingBox();
        double dx = box.maxX() - box.minX() + 1.0;
        double dy = box.maxY() - box.minY() + 1.0;
        double dz = box.maxZ() - box.minZ() + 1.0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + APERTURE_MARGIN;
    }

    private static double[] clipRegion(
        Vec3 point, Vec3 normal, Vec3 axisW, Vec3 axisH, double halfW, double halfH,
        double maxDepth
    ) {
        return new double[]{
            point.x, point.y, point.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z, halfW,
            axisH.x, axisH.y, axisH.z, halfH,
            CLIP_SKIN, maxDepth
        };
    }

    /**
     * (Re)apply the union of all active sessions' portal-plane clip regions to a ship's REAL body —
     * a ship can straddle several portals at once, and regions replace wholesale.
     */
    private static void applyRealClipRegions(ServerSubLevel ship, long parentScene, int realId) {
        java.util.ArrayList<double[]> regions = new java.util.ArrayList<>(2);
        for (Session s : SESSIONS.values()) {
            if (s.sub == ship && s.realClipRegion != null) regions.add(s.realClipRegion);
        }
        double[] flat = new double[regions.size() * 16];
        for (int i = 0; i < regions.size(); i++) {
            System.arraycopy(regions.get(i), 0, flat, i * 16, 16);
        }
        ipl.sable.natives.IplRapierNatives.setClipRegions(parentScene, realId, flat);
        for (double[] r : regions) {
            LOG.debug("[IPL-IMAGE-CLIP] realId={} plane=({},{},{}) normal=({},{},{})",
                realId,
                String.format("%.1f", r[0]), String.format("%.1f", r[1]), String.format("%.1f", r[2]),
                String.format("%.2f", r[3]), String.format("%.2f", r[4]), String.format("%.2f", r[5]));
        }
    }

    /** Straddle ended (backed out, flipped, or left the zone): retire the image. */
    public static void clear(StraddleKey key, String reason) {
        Session session = SESSIONS.remove(key);
        if (session == null) return;
        ipl.sable.natives.IplRapierNatives.removeImageCollider(
            session.destScene, session.realId, session.imageHandle);
        // Recompute the real body's clip regions from whatever sessions remain (usually
        // none → cleared): the through-part becomes fully source-solid again.
        if (session.realClipRegion != null && !session.sub.isRemoved()) {
            applyRealClipRegions(session.sub, session.parentScene, session.realId);
        }
        LOG.info("[IPL-IMAGE] end uuid={} imageHandle={} reason={}",
            session.sub.getUniqueId(), session.imageHandle, reason);
    }

    /** Server stopping: drop all state without native despawns (scenes die with the JVM). */
    public static void clearAll() {
        SESSIONS.clear();
        loggedSkip = false;
    }

    /** Whether any Atlas session is active for this ship (server-side straddle truth). */
    public static boolean hasSession(UUID shipUuid) {
        for (Session s : SESSIONS.values()) {
            if (s.sub.getUniqueId().equals(shipUuid)) return true;
        }
        return false;
    }

    /**
     * The active Atlas session's portal isometry mapping {@code sub}'s source frame into
     * {@code destLevel}, or null. Feeds the frame mapping for entity collision /
     * interaction on the through-part.
     */
    public static IplStraddlePoseMap.StraddleMapping getMappingInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level destLevel
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.mapping;
            }
        }
        return null;
    }

    public static boolean hasSessionKey(StraddleKey key) {
        return SESSIONS.containsKey(key);
    }

    /** Snapshot of active session keys (declarative lifecycle reap sweep). */
    public static java.util.List<StraddleKey> sessionKeys() {
        return new java.util.ArrayList<>(SESSIONS.keySet());
    }

    /** Snapshot all active portal sessions owned by one real body. */
    public static java.util.List<StraddleKey> sessionKeysFor(UUID shipUuid) {
        java.util.List<StraddleKey> keys = new java.util.ArrayList<>();
        for (StraddleKey key : SESSIONS.keySet()) {
            if (key.subLevelUuid().equals(shipUuid)) keys.add(key);
        }
        return keys;
    }

    /** Portal of the active session mapping this ship INTO {@code level} (dest side). */
    public static qouteall.imm_ptl.core.portal.Portal getSessionPortalInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.portal;
            }
        }
        return null;
    }

    /** Visit EVERY active session mapping this ship INTO {@code level} (multi-straddle). */
    public static void forEachSessionInto(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal,
            IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                visitor.accept(s.portal, s.mapping);
            }
        }
    }

    /** Visit EVERY active session this ship is exiting FROM {@code level} (multi-straddle). */
    public static void forEachSessionFrom(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level,
        java.util.function.BiConsumer<qouteall.imm_ptl.core.portal.Portal,
            IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.parent == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                visitor.accept(s.portal, s.mapping);
            }
        }
    }

    /** Portal of the active session this ship is exiting FROM {@code level} (source side). */
    public static qouteall.imm_ptl.core.portal.Portal getSessionPortalFrom(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.parent == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                return s.portal;
            }
        }
        return null;
    }

    /** True when {@code position} is on the destination side of a same-dimension session. */
    public static boolean isInMappedHalf(
        dev.ryanhcode.sable.sublevel.SubLevel sub, net.minecraft.world.level.Level level, Vec3 position
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == level && s.parent == level && s.sub.getUniqueId().equals(sub.getUniqueId())) {
                // Mapped dest-plane test + proximity disambiguation (shared with the
                // client branch — see IplStraddlePoseMap.isInMappedHalf for why both
                // conditions are needed on rotated pairs).
                return IplStraddlePoseMap.isInMappedHalf(
                    s.mapping, s.portal, s.sub.boundingBox(), position);
            }
        }
        return false;
    }

    /**
     * Visit every active Atlas session whose DESTINATION is {@code destLevel}: the ship and
     * the mapped image region. Used by Atlas chart ticket enrollment.
     */
    public static void forEachSessionInto(
        ServerLevel destLevel,
        java.util.function.BiConsumer<ServerSubLevel, IplStraddlePoseMap.StraddleMapping> visitor
    ) {
        for (Session s : SESSIONS.values()) {
            if (s.dest == destLevel && !s.sub.isRemoved()) {
                visitor.accept(s.sub, s.mapping);
            }
        }
    }

}
