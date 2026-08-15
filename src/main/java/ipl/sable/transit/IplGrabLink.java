package ipl.sable.transit;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One frame hop of a grab chain: an exact isometry snapshot of a portal crossing,
 * captured at the moment the crossing EVENT happened (player teleport, body transit,
 * pick raycast). Where the player and the body ARE is a consequence of the transform
 * that was applied at that moment — a portal moving afterwards does not move them —
 * so the snapshot, not the live entity, is the correct frame mapping. The live portal
 * entity is only consulted for VISUALS (beam aperture clamping), verified by origin.
 *
 * <p>A chain of links maps a point from the grab owner's world to the grabbed body's
 * parent frame by folding {@link #transform(Vec3)} in order. Player crossings prepend
 * the INVERSE of the crossed portal; body transits append the crossed portal FORWARD;
 * adjacent links that compose to the identity annihilate ({@link #composesToIdentity}).
 * That single algebra covers walk-through, walk-back, drag-through, drag-back, and any
 * sequential multi-portal recursion without special cases.
 */
public record IplGrabLink(
    UUID portalId,
    ResourceKey<Level> fromDim,
    ResourceKey<Level> toDim,
    Vec3 origin,
    Vec3 dest,
    Quaterniond rotation,
    double scale,
    Vec3 normal,
    Vec3 axisW,
    Vec3 axisH,
    double width,
    double height
) {

    public static final double POSITION_EPSILON = 1.0e-4;
    public static final double ROTATION_EPSILON = 1.0e-6;

    /** Forward snapshot of {@code portal}: maps its source frame into its destination frame. */
    public static IplGrabLink forward(Portal portal) {
        return new IplGrabLink(
            portal.getUUID(),
            portal.level().dimension(),
            portal.getDestDim(),
            portal.getOriginPos(),
            portal.getDestPos(),
            rotationOf(portal),
            portal.getScaling(),
            portal.getNormal(),
            portal.getAxisW(),
            portal.getAxisH(),
            portal.getWidth(),
            portal.getHeight()
        );
    }

    /**
     * Inverse snapshot of {@code portal}: maps its destination frame back into its source
     * frame. Geometry (aperture rectangle) is expressed on the DESTINATION side, which is
     * where a beam coming from that side physically meets the doorway. When IP tracks a
     * reverse portal entity, its UUID is recorded so pass matching and live lookups find
     * the entity actually rendered on that side; a one-way portal keeps the forward UUID
     * (the snapshot alone fully defines the transform).
     */
    public static IplGrabLink inverse(Portal portal) {
        Quaterniond rotation = rotationOf(portal);
        Quaterniond inverseRotation = new Quaterniond(rotation).conjugate();
        UUID id = portal.getUUID();
        Portal reverse = qouteall.imm_ptl.core.portal.PortalExtension.get(portal).reversePortal;
        if (reverse != null && !reverse.isRemoved()) {
            id = reverse.getUUID();
        }
        Vector3d mappedNormal = rotation.transform(
            new Vector3d(portal.getNormal().x, portal.getNormal().y, portal.getNormal().z));
        Vector3d mappedAxisW = rotation.transform(
            new Vector3d(portal.getAxisW().x, portal.getAxisW().y, portal.getAxisW().z));
        Vector3d mappedAxisH = rotation.transform(
            new Vector3d(portal.getAxisH().x, portal.getAxisH().y, portal.getAxisH().z));
        return new IplGrabLink(
            id,
            portal.getDestDim(),
            portal.level().dimension(),
            portal.getDestPos(),
            portal.getOriginPos(),
            inverseRotation,
            portal.getScaling() <= 0.0 ? 1.0 : 1.0 / portal.getScaling(),
            // The destination-side doorway faces opposite the mapped source normal.
            new Vec3(-mappedNormal.x, -mappedNormal.y, -mappedNormal.z),
            new Vec3(mappedAxisW.x, mappedAxisW.y, mappedAxisW.z),
            new Vec3(mappedAxisH.x, mappedAxisH.y, mappedAxisH.z),
            portal.getWidth() * portal.getScaling(),
            portal.getHeight() * portal.getScaling()
        );
    }

    private static Quaterniond rotationOf(Portal portal) {
        DQuaternion q = portal.getRotationD();
        return q == null ? new Quaterniond() : new Quaterniond(q.x, q.y, q.z, q.w);
    }

    public Vec3 transform(Vec3 point) {
        Vector3d local = new Vector3d(
            (point.x - origin.x) * scale,
            (point.y - origin.y) * scale,
            (point.z - origin.z) * scale
        );
        rotation.transform(local);
        return new Vec3(dest.x + local.x, dest.y + local.y, dest.z + local.z);
    }

    public Vec3 inverseTransform(Vec3 point) {
        Vector3d local = new Vector3d(point.x - dest.x, point.y - dest.y, point.z - dest.z);
        new Quaterniond(rotation).conjugate().transform(local);
        double inv = scale == 0.0 ? 1.0 : 1.0 / scale;
        return new Vec3(origin.x + local.x * inv, origin.y + local.y * inv, origin.z + local.z * inv);
    }

    public Vec3 transformDirection(Vec3 direction) {
        Vector3d v = new Vector3d(direction.x, direction.y, direction.z);
        rotation.transform(v);
        return new Vec3(v.x, v.y, v.z);
    }

    /** True when applying {@code this} then {@code next} is the identity map. */
    public boolean composesToIdentity(IplGrabLink next) {
        if (!toDim.equals(next.fromDim) || !next.toDim.equals(fromDim)) return false;
        Quaterniond composed = new Quaterniond(next.rotation).mul(rotation);
        if (Math.abs(composed.x) > ROTATION_EPSILON
            || Math.abs(composed.y) > ROTATION_EPSILON
            || Math.abs(composed.z) > ROTATION_EPSILON
            || Math.abs(Math.abs(composed.w) - 1.0) > ROTATION_EPSILON) {
            return false;
        }
        if (Math.abs(scale * next.scale - 1.0) > ROTATION_EPSILON) return false;
        // Identity also requires a fixed point: this.origin must map back onto itself.
        Vec3 roundTrip = next.transform(transform(origin));
        return roundTrip.distanceToSqr(origin) < POSITION_EPSILON * POSITION_EPSILON;
    }

    /**
     * Append {@code link} at the tail of {@code chain}, annihilating an identity pair.
     * Returns a new immutable list.
     */
    public static List<IplGrabLink> append(List<IplGrabLink> chain, IplGrabLink link) {
        if (!chain.isEmpty() && chain.get(chain.size() - 1).composesToIdentity(link)) {
            return List.copyOf(chain.subList(0, chain.size() - 1));
        }
        List<IplGrabLink> out = new ArrayList<>(chain);
        out.add(link);
        return List.copyOf(out);
    }

    /**
     * Prepend {@code link} at the head of {@code chain}, annihilating an identity pair.
     * Returns a new immutable list.
     */
    public static List<IplGrabLink> prepend(List<IplGrabLink> chain, IplGrabLink link) {
        if (!chain.isEmpty() && link.composesToIdentity(chain.get(0))) {
            return List.copyOf(chain.subList(1, chain.size()));
        }
        List<IplGrabLink> out = new ArrayList<>(chain.size() + 1);
        out.add(link);
        out.addAll(chain);
        return List.copyOf(out);
    }

    /** Fold a point through the whole chain (owner frame -> final frame). */
    public static Vec3 fold(List<IplGrabLink> chain, Vec3 point) {
        Vec3 mapped = point;
        for (IplGrabLink link : chain) {
            mapped = link.transform(mapped);
        }
        return mapped;
    }

    /** Fold a direction through the whole chain's rotations. */
    public static Vec3 foldDirection(List<IplGrabLink> chain, Vec3 direction) {
        Vec3 mapped = direction;
        for (IplGrabLink link : chain) {
            mapped = link.transformDirection(mapped);
        }
        return mapped;
    }

    // ------------------------------------------------------------------
    // Wire encoding (exact hex doubles, matching the handoff RPC style).
    // ------------------------------------------------------------------

    private static final String FIELD_SEPARATOR = ",";
    public static final String LINK_SEPARATOR = "|";

    public String encode() {
        return String.join(FIELD_SEPARATOR,
            portalId.toString(),
            fromDim.location().toString(),
            toDim.location().toString(),
            Double.toHexString(origin.x), Double.toHexString(origin.y), Double.toHexString(origin.z),
            Double.toHexString(dest.x), Double.toHexString(dest.y), Double.toHexString(dest.z),
            Double.toHexString(rotation.x), Double.toHexString(rotation.y),
            Double.toHexString(rotation.z), Double.toHexString(rotation.w),
            Double.toHexString(scale),
            Double.toHexString(normal.x), Double.toHexString(normal.y), Double.toHexString(normal.z),
            Double.toHexString(axisW.x), Double.toHexString(axisW.y), Double.toHexString(axisW.z),
            Double.toHexString(axisH.x), Double.toHexString(axisH.y), Double.toHexString(axisH.z),
            Double.toHexString(width), Double.toHexString(height)
        );
    }

    @Nullable
    public static IplGrabLink decode(String encoded) {
        String[] parts = encoded.split(FIELD_SEPARATOR, -1);
        if (parts.length != 25) return null;
        try {
            double[] d = new double[22];
            for (int i = 0; i < 22; i++) {
                d[i] = Double.valueOf(parts[i + 3]);
            }
            return new IplGrabLink(
                UUID.fromString(parts[0]),
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(parts[1])),
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(parts[2])),
                new Vec3(d[0], d[1], d[2]),
                new Vec3(d[3], d[4], d[5]),
                new Quaterniond(d[6], d[7], d[8], d[9]),
                d[10],
                new Vec3(d[11], d[12], d[13]),
                new Vec3(d[14], d[15], d[16]),
                new Vec3(d[17], d[18], d[19]),
                d[20], d[21]
            );
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String encodeChain(List<IplGrabLink> chain) {
        StringBuilder sb = new StringBuilder();
        for (IplGrabLink link : chain) {
            if (sb.length() > 0) sb.append(LINK_SEPARATOR);
            sb.append(link.encode());
        }
        return sb.toString();
    }

    public static List<IplGrabLink> decodeChain(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        List<IplGrabLink> out = new ArrayList<>(4);
        for (String part : encoded.split("\\" + LINK_SEPARATOR)) {
            IplGrabLink link = decode(part);
            if (link == null) return List.of(); // reject the whole chain, never half-apply
            out.add(link);
        }
        return List.copyOf(out);
    }
}
