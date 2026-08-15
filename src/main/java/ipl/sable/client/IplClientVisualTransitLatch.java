package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Render-only bridge between a client delayed pose reaching an aperture and the server's
 * authoritative straddle snapshot. It never affects collision, parent ownership, or transit.
 */
final class IplClientVisualTransitLatch {

    private static final double EPSILON = 1.0e-8;
    /** Server detector's lateral finite-plane extension; never applied along portal normal. */
    private static final double APERTURE_MARGIN = 0.5;
    /** Server confirmation normally arrives before the delayed render reaches the plane. */
    private static final long CONFIRMATION_GRACE_TICKS = 6;
    private static final Map<UUID, Prediction> PREDICTIONS = new HashMap<>();
    /** Exact client-side A→B proof, retained until the handoff commits or the body backs out. */
    private static final Map<UUID, UUID> FORWARD_SWEEPS = new HashMap<>();
    /** Last submitted pose per ship. Interpolation changes every render frame, not only per tick. */
    private static final Map<UUID, Pose3d> LAST_RENDER_POSES = new HashMap<>();
    /** Render frame the pose above was sampled in; one sample per frame, not per pass. */
    private static final Map<UUID, Long> LAST_SAMPLE_FRAMES = new HashMap<>();

    /** Frames a pose sample survives without being re-sampled before it is dropped. */
    private static final long SAMPLE_RETENTION_FRAMES = 600L;
    private static long lastPruneFrame;

    private record Prediction(UUID portalId, long expiresAtTick) {}

    private record Projection(
        double minPlane, double maxPlane, double minWidth, double maxWidth,
        double minHeight, double maxHeight
    ) {}

    private IplClientVisualTransitLatch() {}

    static List<Portal> append(
        ClientSubLevel sub, ClientLevel level, List<Portal> visibleAuthoritativeSessions,
        List<Portal> resolved
    ) {
        UUID shipId = sub.getUniqueId();
        long tick = level.getGameTime();
        // Sample ONCE PER RENDER FRAME before examining a pending prediction. A
        // ClientSubLevel's interpolation pose moves between network ticks, so lastPose alone
        // cannot see a high-speed aperture crossing until after the server already decided it.
        //
        // This must not re-sample per render PASS. One frame runs the main renderLevel plus
        // one nested renderLevel per visible portal, and each pass re-resolves the same ship
        // (the straddle cache is per pass, and every prediction change invalidates it). With
        // a per-call sample, only the first pass of a frame saw a real from->to sweep; every
        // later pass compared this frame's pose with itself, so `crossesAperture` was false
        // and the projection was dropped exactly in the portal views that are supposed to
        // show the far-side geometry. At high speed that is the whole crossing: the ship
        // clears the aperture within one frame, and the only pass that could arm the
        // prediction was the one that does not draw the destination side.
        Pose3d previousRenderPose = ipl$sampleRenderPose(sub, shipId);
        Prediction prediction = PREDICTIONS.get(shipId);
        if (prediction != null) {
            if (containsPortal(visibleAuthoritativeSessions, prediction.portalId())) {
                remove(shipId);
                return resolved;
            }
            Portal portal = findPortal(level, prediction.portalId());
            if (portal == null || tick > prediction.expiresAtTick()
                || !stillEligible(sub, portal)) {
                remove(shipId);
                FORWARD_SWEEPS.remove(shipId, prediction.portalId());
            } else {
                return appendIfMissing(resolved, portal);
            }
        }

        Portal candidate = findEnteringPortal(sub, level, previousRenderPose);
        if (candidate == null) return resolved;

        // Preserve the exact finite-AABB crossing proof even when the handoff RPC arrives
        // after this frame. The pending handoff consumes it by portal UUID, never by a
        // nearest-portal guess, so recursive/rapid crossings remain FIFO-safe.
        FORWARD_SWEEPS.putIfAbsent(shipId, candidate.getUUID());
        PREDICTIONS.put(shipId, new Prediction(
            candidate.getUUID(), tick + CONFIRMATION_GRACE_TICKS));
        // A prediction can start while an enclosing portal render has already cached its
        // destination projection list. Rebuild every nested pass before any draw uses it.
        IplStraddleRenderCache.invalidateActivePasses();
        return appendIfMissing(resolved, candidate);
    }

    /**
     * The pose this ship had when the previous frame sampled it, refreshing the sample at
     * most once per frame. Returns null on the very first frame a ship is seen, and the
     * caller then falls back to `sub.lastPose()`.
     */
    private static Pose3d ipl$sampleRenderPose(ClientSubLevel sub, UUID shipId) {
        long frame = IplStraddleRenderCache.frameId();
        Long sampledFrame = LAST_SAMPLE_FRAMES.get(shipId);
        Pose3d previous = LAST_RENDER_POSES.get(shipId);
        if (sampledFrame == null || sampledFrame.longValue() != frame) {
            LAST_SAMPLE_FRAMES.put(shipId, frame);
            LAST_RENDER_POSES.put(shipId, new Pose3d(sub.renderPose()));
        }
        ipl$pruneSamples(frame);
        return previous;
    }

    /**
     * Ships leave render range, get removed, or change dimension without any latch event, so
     * these two maps only ever grew. Drop samples nothing has refreshed for a long while.
     */
    private static void ipl$pruneSamples(long frame) {
        if (frame - lastPruneFrame < SAMPLE_RETENTION_FRAMES) return;
        lastPruneFrame = frame;
        LAST_SAMPLE_FRAMES.entrySet().removeIf(entry -> {
            if (frame - entry.getValue() < SAMPLE_RETENTION_FRAMES) return false;
            LAST_RENDER_POSES.remove(entry.getKey());
            return true;
        });
    }

    private static boolean containsPortal(List<Portal> portals, UUID portalId) {
        for (Portal portal : portals) {
            if (portal.getUUID().equals(portalId)) return true;
        }
        return false;
    }

    /** The server owns physics parity; prediction only decides whether geometry reaches this frame. */
    static boolean isVisible(ClientSubLevel sub, Portal portal) {
        Projection now = project(sub, sub.renderPose(), portal);
        if (now.maxPlane() < -EPSILON) return false;
        if (now.minPlane() >= EPSILON) return true;
        return now.maxWidth() >= -portal.getWidth() * 0.5 - APERTURE_MARGIN - EPSILON
            && now.minWidth() <= portal.getWidth() * 0.5 + APERTURE_MARGIN + EPSILON
            && now.maxHeight() >= -portal.getHeight() * 0.5 - APERTURE_MARGIN - EPSILON
            && now.minHeight() <= portal.getHeight() * 0.5 + APERTURE_MARGIN + EPSILON;
    }

    static void clear(UUID shipId) {
        remove(shipId);
        FORWARD_SWEEPS.remove(shipId);
        LAST_RENDER_POSES.remove(shipId);
        LAST_SAMPLE_FRAMES.remove(shipId);
    }

    /**
     * The visual parent must not switch merely because a server handoff arrived. Require this
     * client's delayed OBB to cross the same finite aperture from source to destination.
     * If it returns wholly source-side first, discard the proof and keep the source frame.
     */
    static boolean hasForwardApertureSweep(ClientSubLevel sub, Portal portal) {
        UUID shipId = sub.getUniqueId();
        Projection now = project(sub, sub.renderPose(), portal);
        UUID recorded = FORWARD_SWEEPS.get(shipId);
        if (recorded != null && recorded.equals(portal.getUUID())) {
            if (now.maxPlane() < -EPSILON) {
                FORWARD_SWEEPS.remove(shipId);
                IplStraddleSessionStore.clearHandoffVisual(shipId);
                IplStraddleRenderCache.invalidateActivePasses();
                return false;
            }
            return true;
        }
        if (!hasBufferedForwardSweep(sub, portal)
            && !crossesAperture(project(sub, sub.lastPose(), portal), now, portal)) {
            return false;
        }
        FORWARD_SWEEPS.put(shipId, portal.getUUID());
        IplStraddleRenderCache.invalidateActivePasses();
        return true;
    }

    /**
     * `renderPose` can already be fully through on a fast packet burst. Search adjacent
     * delayed snapshots as well as this frame's endpoints, preserving the same finite
     * aperture criterion instead of falling back to an unconditional server handoff.
     */
    private static boolean hasBufferedForwardSweep(ClientSubLevel sub, Portal portal) {
        var buffer = sub.getInterpolator().buffer;
        synchronized (buffer) {
            for (int i = 1; i < buffer.size(); i++) {
                Projection from = project(sub, buffer.get(i - 1).pose(), portal);
                Projection to = project(sub, buffer.get(i).pose(), portal);
                if (crossesAperture(from, to, portal)) return true;
            }
        }
        return false;
    }

    private static void remove(UUID shipId) {
        if (PREDICTIONS.remove(shipId) != null) {
            IplStraddleRenderCache.invalidateActivePasses();
        }
    }

    private static List<Portal> appendIfMissing(List<Portal> portals, Portal portal) {
        for (Portal existing : portals) {
            if (existing.getUUID().equals(portal.getUUID())) return portals;
        }
        List<Portal> combined = new ArrayList<>(portals.size() + 1);
        combined.addAll(portals);
        combined.add(portal);
        return combined;
    }

    /** Keep a fully-through visual tail until handoff, but abort a straddler leaving aperture. */
    private static boolean stillEligible(ClientSubLevel sub, Portal portal) {
        Projection now = project(sub, sub.renderPose(), portal);
        if (now.maxPlane() < -EPSILON) return false;
        if (now.minPlane() >= EPSILON) return true;
        return isVisible(sub, portal);
    }

    /** Exact current visual OBB sweep against one finite aperture, not center/tick sampling. */
    private static Portal findEnteringPortal(
        ClientSubLevel sub, ClientLevel level, Pose3dc previousRenderPose
    ) {
        ProjectionBest best = null;
        List<Portal> portals = portals(level);
        for (Portal portal : portals) {
            if (portal.isRemoved() || !portal.isTeleportable()
                || Math.abs(portal.getScaling() - 1.0) > EPSILON
                || !isCanonicalEntranceFace(portal, portals)) continue;

            Projection to = project(sub, sub.renderPose(), portal);
            Projection from = project(sub,
                previousRenderPose == null ? sub.lastPose() : previousRenderPose, portal);
            if (!crossesAperture(from, to, portal)) continue;
            double progress = -from.maxPlane() / (to.maxPlane() - from.maxPlane());

            if (best == null || progress < best.progress() - EPSILON
                || (Math.abs(progress - best.progress()) <= EPSILON
                    && portal.getUUID().toString().compareTo(best.portal().getUUID().toString()) < 0)) {
                best = new ProjectionBest(portal, progress);
            }
        }
        return best == null ? null : best.portal();
    }

    /** Source→destination OBB sweep intersects this portal's finite rectangle. */
    private static boolean crossesAperture(Projection from, Projection to, Portal portal) {
        if (from.maxPlane() >= -EPSILON || to.maxPlane() < -EPSILON) return false;
        double progress = -from.maxPlane() / (to.maxPlane() - from.maxPlane());
        double minWidth = lerp(from.minWidth(), to.minWidth(), progress);
        double maxWidth = lerp(from.maxWidth(), to.maxWidth(), progress);
        double minHeight = lerp(from.minHeight(), to.minHeight(), progress);
        double maxHeight = lerp(from.maxHeight(), to.maxHeight(), progress);
        return maxWidth >= -portal.getWidth() * 0.5 - APERTURE_MARGIN - EPSILON
            && minWidth <= portal.getWidth() * 0.5 + APERTURE_MARGIN + EPSILON
            && maxHeight >= -portal.getHeight() * 0.5 - APERTURE_MARGIN - EPSILON
            && minHeight <= portal.getHeight() * 0.5 + APERTURE_MARGIN + EPSILON;
    }

    private record ProjectionBest(Portal portal, double progress) {}

    /** Match the server's collapse of duplicate directed IP companion entities. */
    private static boolean isCanonicalEntranceFace(Portal portal, List<Portal> candidates) {
        for (Portal other : candidates) {
            if (other == portal || !portal.getDestDim().equals(other.getDestDim())) continue;
            if (portal.getOriginPos().distanceToSqr(other.getOriginPos()) > 1.0e-12
                || portal.getDestPos().distanceToSqr(other.getDestPos()) > 1.0e-12
                || Math.abs(portal.getWidth() - other.getWidth()) > 1.0e-6
                || Math.abs(portal.getHeight() - other.getHeight()) > 1.0e-6
                || portal.getNormal().dot(other.getNormal()) < 0.999999) {
                continue;
            }
            if (portal.getUUID().toString().compareTo(other.getUUID().toString()) > 0) return false;
        }
        return true;
    }

    private static List<Portal> portals(ClientLevel level) {
        Map<UUID, Portal> unique = new HashMap<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Portal portal) unique.put(portal.getUUID(), portal);
        }
        for (Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            unique.put(portal.getUUID(), portal);
        }
        return List.copyOf(unique.values());
    }

    private static Portal findPortal(ClientLevel level, UUID portalId) {
        for (Portal portal : portals(level)) {
            if (portal.getUUID().equals(portalId)) return portal;
        }
        return null;
    }

    private static Projection project(ClientSubLevel sub, Pose3dc pose, Portal portal) {
        var bounds = sub.getPlot().getBoundingBox();
        Vec3 origin = portal.getOriginPos();
        Vec3 planeNormal = portal.getNormal().scale(-1.0);
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        double minPlane = Double.POSITIVE_INFINITY, maxPlane = Double.NEGATIVE_INFINITY;
        double minWidth = Double.POSITIVE_INFINITY, maxWidth = Double.NEGATIVE_INFINITY;
        double minHeight = Double.POSITIVE_INFINITY, maxHeight = Double.NEGATIVE_INFINITY;
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            Vec3 point = pose.transformPosition(new Vec3(
                x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0));
            Vec3 relative = point.subtract(origin);
            double plane = relative.dot(planeNormal);
            double width = relative.dot(axisW);
            double height = relative.dot(axisH);
            minPlane = Math.min(minPlane, plane);
            maxPlane = Math.max(maxPlane, plane);
            minWidth = Math.min(minWidth, width);
            maxWidth = Math.max(maxWidth, width);
            minHeight = Math.min(minHeight, height);
            maxHeight = Math.max(maxHeight, height);
        }
        return new Projection(minPlane, maxPlane, minWidth, maxWidth, minHeight, maxHeight);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * Math.clamp(progress, 0.0, 1.0);
    }
}
