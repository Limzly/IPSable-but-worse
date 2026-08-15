package ipl.sable.transit;

import ipl.sable.natives.IplRapierNatives;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Portal containment rim: a world-anchored kinematic-contraption body shaped as a
 * hollow rectangle in the portal plane — solid everywhere on the plane EXCEPT the
 * aperture. IP portals have no frame blocks (arbitrary placement), so without this the
 * plane-minus-aperture is vacuum: ships could cross the plane beside the opening or
 * shear laterally into the aperture column without ever starting a proper crossing.
 * Rims are ALWAYS-ON per portal entity (see {@link IplPortalRimManager}) — containment
 * must exist before any straddle session does.
 *
 * <p>Built as direct native Rapier compound geometry, outside Sable's voxel path. The
 * rim uses dedicated 1/64-block contact skins, not full block-shaped frame voxels. Each skin
 * hugs only the OUTSIDE edge of the aperture, so a ship can rest or pivot on the
 * doorway edge from either side without an invisible wall intruding into the opening.
 * This is deliberately finite: sub-level rigid bodies have CCD, but a zero-width
 * mathematical edge has no contact manifold and can be crossed between substeps. The
 * 1/64 skin gives a stable contact manifold while keeping the opening exact. The
 * contraption's local +Y follows the portal normal. Arbitrary portal rotation and real
 * solver collision remain intact; entities never touch Rapier and pass through freely.
 *
 * <p>Aperture exactness: native cuboids use the portal's actual double width and height,
 * including fractional apertures. No integer voxel hole, slack overlap, or secondary rim
 * is needed.
 */
public final class IplStraddlePortalRim {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-portal-rim");
    /** Minimum practical finite contact area for CCD bodies; never enters aperture. */
    private static final double RIM_WIDTH = 1.0 / 64.0;
    private static final double HALF_THICKNESS = 1.0 / 128.0;

    private IplStraddlePortalRim() {}

    /** Immutable spawn geometry, reused for move updates. */
    public record RimGeometry(
        Vec3 planePoint, Vec3 normal, Vec3 axisW, Vec3 axisH, double halfW, double halfH
    ) {}

    /**
     * Spawn the rim bodies in {@code scene}; returns spawned ids (possibly empty).
     */
    public static int[] spawn(long scene, RimGeometry g) {
        int id = spawnOne(scene, g);
        return id < 0 ? new int[0] : new int[]{id};
    }

    /** Re-anchor already-spawned rims to a portal's new geometry (portal moved). */
    public static void updateTransforms(long scene, int[] ids, RimGeometry g) {
        for (int i = 0; i < ids.length; i++) {
            applyTransform(scene, ids[i], g);
        }
    }

    /** Remove a rim body; safe on ids the scene no longer knows. */
    public static void remove(long scene, int id) {
        if (id < 0 || scene == 0) return;
        try {
            IplRapierNatives.removePortalRim(scene, id);
        } catch (Throwable t) {
            LOG.error("[IPL-RIM] remove failed for id={}", id, t);
        }
    }

    private static int spawnOne(long scene, RimGeometry g) {
        try {
            int id = IplRapierNatives.createPortalRim(
                scene, 2.0 * g.halfW(), 2.0 * g.halfH(), RIM_WIDTH, HALF_THICKNESS);
            if (id < 0) return -1;

            applyTransform(scene, id, g);

            LOG.info("[IPL-RIM] spawn id={} aperture={}x{} width=1/64 scene={}",
                id, 2.0 * g.halfW(), 2.0 * g.halfH(), Long.toHexString(scene));
            return id;
        } catch (Throwable t) {
            LOG.error("[IPL-RIM] spawn failed", t);
            return -1;
        }
    }

    private static void applyTransform(long scene, int id, RimGeometry g) {
        // Right-handed basis with Y along the normal: X = axisW, Z = X×Y. This is
        // the same portal basis used by IP for width/height, so local native bars map
        // to the aperture even when a stationary ship portal is arbitrarily rotated.
        org.joml.Vector3d yAxis = new org.joml.Vector3d(
            g.normal().x, g.normal().y, g.normal().z).normalize();
        org.joml.Vector3d xAxis = new org.joml.Vector3d(
            g.axisW().x, g.axisW().y, g.axisW().z).normalize();
        org.joml.Vector3d zAxis = new org.joml.Vector3d(xAxis).cross(yAxis).normalize();
        org.joml.Matrix3d basis = new org.joml.Matrix3d(
            xAxis.x, xAxis.y, xAxis.z,
            yAxis.x, yAxis.y, yAxis.z,
            zAxis.x, zAxis.y, zAxis.z);
        org.joml.Quaterniond rot = new org.joml.Quaterniond().setFromNormalized(basis);

        // Direct Rapier bars are centered on local origin, exactly like a Portal's
        // origin. Map origin to origin; the old voxel rim used min-corner coordinates.
        IplRapierNatives.setPortalRimTransform(
            scene, id,
            g.planePoint().x, g.planePoint().y, g.planePoint().z,
            rot.x, rot.y, rot.z, rot.w);
    }
}
