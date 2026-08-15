package ipl.sable.transit;

import net.minecraft.server.level.ServerLevel;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.UUID;

/** Portal rim API disabled while its native physics implementation is removed. */
public final class IplPortalRimManager {

    private IplPortalRimManager() {}

    public static void tick(ServerLevel level) {}

    public static void clearAll() {}

    /**
     * Called by the ship-anchor driver immediately after it re-poses a portal from the
     * carrier's fresh physics pose. This is the rim's weld point: it uses the exact same
     * live portal transform as the aperture, rather than waiting for a later container
     * lifecycle sweep.
     */
    public static void followDrivenPortal(ServerLevel level, Portal portal) {}

    /**
     * Register (or clear) the carrier of a ship-anchored portal: the rim's bodies
     * stop colliding with the carrier ship. Applied immediately to a live rim and
     * re-applied automatically when the rim respawns (aperture change, portal
     * reload). Body ids are never reused, so stale exclusions are inert.
     */
    public static void setCarrierExclusion(UUID portalId, int carrierBodyId, boolean on) {}
}
