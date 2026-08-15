package ipl.sable.natives;

/**
 * IPSable's extensions to the sable_rapier native surface (portal-physics spec phase 4).
 * The symbols live in the SAME DLL as Sable's natives (our local build — see
 * {@code IplNativesOverrideMixin} and {@code natives/build-windows.ps1}), namespaced under
 * this class so sable's own JNI ABI stays untouched.
 *
 * <p>Callers MUST check {@link #isAvailable()} first: when the custom natives failed to
 * load (non-Windows platform, kill switch, extraction error) these methods throw
 * {@link UnsatisfiedLinkError}.
 */
public final class IplRapierNatives {

    private static volatile boolean available = false;

    private IplRapierNatives() {}

    /** Set by {@code IplNativesOverrideMixin} once the IPSable-built DLL is loaded. */
    public static void markAvailable() {
        available = true;
    }

    public static boolean isAvailable() {
        return available;
    }

    /**
     * Set (or clear, with an empty array) body clip regions: solver contacts past the
     * region's plane and within its lateral rectangle are dropped from its manifolds.
     * Infinite lateral half extents encode a full plane.
     *
     * <p>Layout: N regions × 14 doubles —
     * {@code [px py pz  nx ny nz  wx wy wz  halfW  hx hy hz  halfH]}.
     */
    public static native void setClipRegions(long sceneHandle, int bodyId, double[] regions);

    /**
     * Register ({@code excluded=true}) or clear a contact exclusion between two bodies in
     * one scene: the dispatcher's dynamic-vs-dynamic path generates no contact manifolds
     * for excluded pairs (and drops persisted ones). Used by portal rims to exempt an
     * anchored portal's carrier from colliding with its own containment body.
     *
     * <p>Idempotent in both directions; no-op on a null scene or negative/equal ids.
     */
    public static native void setBodyPairExclusion(
        long sceneHandle, int idA, int idB, boolean excluded);

    /**
     * Create a portal rim as four native Rapier cuboids, not as Sable voxel terrain.
     * All dimensions are in the rim's local frame: X=portal width, Y=portal normal,
     * Z=portal height. The opening remains exact; bars extend only outside it.
     *
     * @return body id for transform/removal, or -1 on an unavailable scene.
     */
    public static native int createPortalRim(
        long sceneHandle,
        double holeWidth, double holeHeight, double width, double halfThickness);

    /** Set a native portal-rim body's world transform. */
    public static native void setPortalRimTransform(
        long sceneHandle, int rimId,
        double x, double y, double z,
        double qx, double qy, double qz, double qw);

    /** Remove a native portal-rim body. */
    public static native void removePortalRim(long sceneHandle, int rimId);

    /**
     * Tag a hosted body's native collider info with its parent-frame id. The dispatcher's
     * dynamic-vs-dynamic path drops native-vs-native contacts between bodies whose nonzero
     * frames differ (real hosted bodies share the hosting chart at parent-frame coordinates,
     * so cross-parent numeric overlap is meaningless). Image colliders are unaffected —
     * their frame is the shape's chart, which the native chart guard already scopes, so
     * straddle image-vs-image contacts in a shared chart resolve on their own. Call on
     * parent flips only, not per tick.
     *
     * @return true when the tag landed; false when the body is not (yet) registered in the
     *         scene — the caller should retry next hosting tick.
     */
    public static native boolean setParentFrame(long sceneHandle, int bodyId, int frameId);

    /**
     * Sable bodies in {@code bodyId}'s joint component. {@code rigidOnly} restricts the
     * walk to joints whose both endpoints are ship bodies — the rigid assembly (swivel
     * bearings, couplings) that transits portals as one unit; rope particle chains do
     * not bridge components in that mode.
     */
    public static native int[] connectedSableBodyIds(
        long sceneHandle, int bodyId, boolean rigidOnly);

    // ------------------------------------------------------------------
    // Rope portal seams: a rope whose attached ship transited a portal keeps its chain
    // in the SOURCE frame; the end joint re-targets to the static ground body and its
    // anchor tracks the ship's image through the portal isometry, so tension pulls the
    // trailing body toward and through the aperture.
    // ------------------------------------------------------------------

    /** Set ({@code has=true}) or clear the portal prefix on one rope end (the isometry
     *  mapping the ship's CURRENT frame back into the rope chain's frame). */
    public static native void setRopePortalPrefix(
        long sceneHandle, long ropeId, boolean end, boolean has,
        double px, double py, double pz,
        double qx, double qy, double qz, double qw);

    /** Packed {@code (ropeId << 1) | endBit} for every rope end attached to the body. */
    public static native long[] ropesAttachedToSableBody(long sceneHandle, int bodyId);

    /** Teleport a whole rope chain through an isometry and restamp it to the chart of
     *  {@code destSceneHandle} (0 = keep). Used when both ends re-unify on the far
     *  side; caller clears the ends' prefixes afterwards. */
    public static native void remapRope(
        long sceneHandle, long ropeId,
        double dx, double dy, double dz,
        double qx, double qy, double qz, double qw,
        long destSceneHandle);

    /** Rope ids whose stretch ratio (chain + attachment gaps over natural length)
     *  exceeds {@code threshold} — the break monitor's input. */
    public static native long[] overstretchedRopes(long sceneHandle, double threshold);

    /**
     * Dormancy switch: {@code dormant=true} makes the body Fixed (no integration, no
     * gravity, immovable — still a valid joint/rope anchor) with velocities zeroed;
     * {@code false} restores Dynamic. Idempotent — safe to re-apply every tick. Used
     * for hosted ships whose parent-pointer chunks are unloaded.
     */
    public static native void setBodyDormant(long sceneHandle, int bodyId, boolean dormant);

    // ------------------------------------------------------------------
    // Atlas M2 (spec v3 §2.2-2.3): image colliders — Tier-1 exact coupling.
    // ------------------------------------------------------------------

    /**
     * Create an image collider for the body in the CALLING scene view's chart, portal
     * isometry {@code P = (R, t)}: translation {@code (dx,dy,dz)} plus rotation quat
     * {@code (qx,qy,qz,qw)} (identity for translation-only portals). The image is extra
     * geometry on the SAME rigid body: its contacts act on the body through the engine's
     * portal-frame mapping — exact, in-solver, no servo, any fixed rotation (Tier 2).
     *
     * @return packed collider handle for {@link #removeImageCollider} /
     *         {@link #setImageClipRegions}, or -1 if the body is unknown.
     */
    public static native long createImageCollider(
        long sceneHandle, int bodyId,
        double dx, double dy, double dz,
        double qx, double qy, double qz, double qw);

    /**
     * Atlas M5: update an image collider's portal isometry — moving portals (animated,
     * or anchored to physics structures) re-derive {@code P = (R, t)} per tick.
     */
    public static native void setImagePrefix(
        long sceneHandle, long imageHandle,
        double dx, double dy, double dz,
        double qx, double qy, double qz, double qw);

    /** Remove an image collider created by {@link #createImageCollider}. */
    public static native void removeImageCollider(
        long sceneHandle, int bodyId, long imageHandle);

    /**
     * Set (or clear, with an empty array) the clip regions of one IMAGE collider — the
     * far side of the half-open aperture seam. Same 14-double layout as
     * {@link #setClipRegions}.
     */
    public static native void setImageClipRegions(
        long sceneHandle, int bodyId, long imageHandle, double[] regions);
}
