package ipl.sable.render;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

/**
 * For a given client-side sub-level, the portal whose plane it currently straddles —
 * so {@link SableSourceClipMixin} can install a clip plane that culls the geometry on
 * the dest side, and so client collision/interaction can select frames consistently.
 *
 * <p><b>Server-authoritative since the session sync:</b> which half of a straddling
 * ship is "still here" is historical state (a ship 60% through is geometrically
 * identical to one 40% through the other way), so this class no longer guesses it from
 * geometry. The straddle portal comes from {@code IplStraddleSessionStore} — the client
 * mirror of the transit controller's latch — and only the plane GEOMETRY is derived
 * per frame from that portal entity's current transform (moving portals stay smooth;
 * parity switches exactly when the server's latch does). The former heuristics
 * (orient-toward-center, contact-normal locking, crossing latches with expiry
 * deadlines) are gone with their failure modes: parked-ship false clips, halfway
 * parity flips on bi-faced portals, stale locks across same-dimension handoffs.
 */
public final class SourceClipPortalFinder {

    private SourceClipPortalFinder() {}

    /**
     * The chosen portal and its source-side clip plane. Convention: the kept
     * half-space is {@code normal · (p - pos) > 0}; the plane normal points to the
     * source side of the portal, where the ship's native frame remains.
     */
    public record ClipDecision(Portal portal, Plane plane) {}

    @Nullable
    public static ClipDecision findStraddlingPortalPlane(ClientSubLevel sub) {
        if (sub == null) return null;

        // Dest-side projection pass (dim-agnostic straddle rendering): the projection
        // driver has already computed the complementary plane — install that instead of
        // searching. This makes the legacy bracket the single clip installer for both
        // sides of a partial crossing.
        qouteall.q_misc_util.my_util.Plane projectionPlane =
            ipl.sable.client.IplStraddleRenderState.getPlaneFor(sub);
        if (projectionPlane != null) {
            // IPL fix (rim): do NOT widen the projection's kept half.
            //
            // The source bracket keeps `n·(p-pos) >= 0` exactly on the mathematical
            // portal plane, and the projection keeps the complementary half. Those two
            // halves already tile the sub-level perfectly. The previous
            // `move(-FrontClipping.ADJUSTMENT)` pushed the projection's cut 0.01 blocks
            // PAST the plane, so a 1cm slab of the ship was inside BOTH kept halves and
            // was therefore drawn twice -- once by the source draw and once by the
            // mapped projection. That doubly-drawn slab is the sub-pixel "ободок"
            // visible along the outer contour of anything poking through the portal
            // (most obvious on a long thin 1x4 stick, whose silhouette is nearly all
            // contour).
            //
            // FrontClipping.ADJUSTMENT (0.01) is IP's z-fighting guard for its own
            // DESTINATION TERRAIN pass -- terrain and the sub-level are different
            // geometry, so inheriting that offset here never prevented a gap between
            // the sub-level's two halves; it only created an overlap in the sub-level
            // itself. Cutting both halves on the identical plane is the exact,
            // gap-free and overlap-free split.
            return new ClipDecision(
                ipl.sable.client.IplStraddleRenderState.getPortalFor(sub), projectionPlane);
        }

        if (ipl.sable.client.IplStraddleRenderCache.hasDecision(sub)) {
            return ipl.sable.client.IplStraddleRenderCache.decision(sub);
        }
        ClipDecision decision = ipl$findAuthoritative(sub);
        ipl.sable.client.IplStraddleRenderCache.cacheDecision(sub, decision);
        return decision;
    }

    /**
     * The session store's portal, with the plane rebuilt from its live transform. The
     * session is keyed on the canonical ENTRANCE face, whose normal by IP convention
     * points at the remaining (source) half — exactly the kept side, no orientation
     * heuristic needed.
     */
    @Nullable
    private static ClipDecision ipl$findAuthoritative(ClientSubLevel sub) {
        Portal portal = ipl.sable.client.IplStraddleSessionStore.resolveRenderPortal(sub);
        if (portal == null) return null;

        Vec3 origin = portal.getOriginPos();
        Vec3 keepNormal = portal.getNormal();
        return new ClipDecision(portal, new Plane(origin, keepNormal));
    }

    /**
     * ALL current straddle decisions for this sub (multi-straddle), session-start
     * order. The source render's kept region is the INTERSECTION of every decision's
     * half-space; the shader takes {@code min} over two cut planes, so brackets feed
     * the first two (more than two simultaneous straddles logs and clips the rest as
     * best-effort with the first pair).
     */
    public static java.util.List<ClipDecision> findStraddlingPortalPlanes(ClientSubLevel sub) {
        if (sub == null) return java.util.List.of();
        java.util.List<Portal> portals =
            ipl.sable.client.IplStraddleSessionStore.resolveAllRenderPortals(sub);
        if (portals.isEmpty()) return java.util.List.of();
        java.util.List<ClipDecision> out = new java.util.ArrayList<>(portals.size());
        for (Portal portal : portals) {
            out.add(new ClipDecision(portal, new Plane(portal.getOriginPos(), portal.getNormal())));
        }
        return out;
    }
}
