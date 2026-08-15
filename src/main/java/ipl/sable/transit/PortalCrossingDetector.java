package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * History-backed finite-aperture swept-AABB detector.
 *
 * <p>For every occupied cube, each physics segment sweeps its endpoint AABB center through
 * a portal aperture expanded by that AABB's half-extents (Minkowski sum). Plane and aperture
 * are clipped as one continuous segment test, not tick samples or rays. Two adjacent physics
 * segments are retained: a portal skipped between one pair of visible poses remains eligible
 * when its final pose is inspected on the following tick.
 */
public final class PortalCrossingDetector {

    private static final double EPSILON = 1.0e-8;
    /** Half-depth of portal entry/exit hysteresis; not part of face direction. */
    private static final double PORTAL_HYSTERESIS_DEPTH = 0.1;
    private static final Map<UUID, Trail> TRAILS = new HashMap<>();
    /**
     * EXIT HYSTERESIS. A transit that has just executed leaves the body sitting a hair
     * past the portal plane in the destination frame. At absurd speeds the very next
     * evaluation can read that pose as "already left the aperture" (tearing the seam down
     * one tick after building it) or, worse, as a fresh crossing through the coincident
     * BACK face -- the body bounces back and forth through the same doorway instead of
     * flying out of it.
     *
     * <p>The fix is the user-described pair: at the moment of the crossing we snapshot the
     * body's position, and until it has travelled this fraction of the portal's own width
     * away from that snapshot the exit simply does not count. The seam is retained and no
     * new crossing may be admitted, so the back face can never claim the body.
     *
     * <p>The band is deliberately tiny (1% of the portal's width): a body actually flying
     * away clears it within the same physics segment, so legitimate fast chains through a
     * portal loop are untouched.
     */
    private static final double EXIT_CLEARANCE_THICKNESS = 0.01;
    /** Floor for degenerate/hairline portals, so the band is never numerically zero. */
    private static final double MIN_EXIT_CLEARANCE = 1.0e-3;
    /** A latch can never outlive this many hosting ticks, even if the body never moves. */
    /**
     * Hard ceiling on how long a latch may live. The band is 0.01 blocks thick, so any
     * body that is still moving leaves it inside a single tick; the ceiling only exists
     * so a body parked exactly on the plane cannot pin its doorway forever. The old
     * value (200 ticks = 10 s) turned the latch into the dominant term and is exactly
     * the length a portal loop used to survive before it stalled.
     */
    private static final long EXIT_LATCH_MAX_TICKS = 3L;
    private static final Map<UUID, ExitLatch> EXIT_LATCHES = new HashMap<>();
    private static long trailTick;

    private PortalCrossingDetector() {}

    public enum CrossingPhase { APPROACHING, STRADDLING, CROSSED }
    public enum SweepDirection { TOWARD_DESTINATION, TOWARD_SOURCE, AMBIGUOUS, NONE }

    public record CrossingState(
        CrossingPhase phase,
        boolean startedBeforePortalPlane,
        boolean sweptIntersectsPortalAperture,
        boolean currentIntersectsPortalAperture,
        SweepDirection sweptEntryDirection,
        SweepDirection currentSweepDirection,
        /** Fraction of the CURRENT segment at which the source->destination entry happened. */
        double entryTime
    ) {
        public boolean sweptTowardDestination() {
            return sweptEntryDirection == SweepDirection.TOWARD_DESTINATION;
        }
    }

    /** Starts one hosting-container sweep; stale trail entries are pruned at its end. */
    public static void beginTrailTick() {
        trailTick++;
    }

    /** Removes trails for ships no longer visited by the hosting container. */
    public static void pruneTrails() {
        TRAILS.entrySet().removeIf(entry -> entry.getValue().lastSeenTick != trailTick);
    }

    /** Capture once before broad-phase query. Retains a continuous two-tick pose trail. */
    public static void captureTrail(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        if (trail == null) {
            trail = new Trail(new Pose3d(airship.lastPose()), new Pose3d(airship.lastPose()),
                new Pose3d(airship.logicalPose()));
            TRAILS.put(id, trail);
        } else {
            // Reuse all pose objects. The old implementation allocated three poses and
            // a record for every hosted ship every server tick.
            trail.older.set(trail.start);
            trail.hasOlder = true;
            if (trail.rebasedThisTick) {
                // HEART FIX. After a parent flip, airship.lastPose() is the pose the body
                // held in the OLD chart. Joining it to the new logicalPose() produces a
                // segment that jumps across the portal in raw coordinates -- a sweep of
                // arbitrary length through geometry the body never touched. The detector
                // then rejects every candidate along it (or mis-sweeps), which is exactly
                // why a body at extreme speed completed roughly ONE crossing per tick and
                // a tight portal loop took ~11 seconds to unwind no matter how high the
                // per-tick crossing cap was set. The rebased end is already expressed in
                // the new chart and IS the correct start of the next segment.
                trail.start.set(trail.end);
                trail.hasOlder = false;
                trail.rebasedThisTick = false;
            } else {
                trail.start.set(airship.lastPose());
            }
            trail.end.set(airship.logicalPose());
        }
        trail.lastSeenTick = trailTick;
    }

    /** Forget source-frame history after a parent flip; it cannot be joined to destination poses. */
    public static void forgetTrail(ServerSubLevel airship) {
        TRAILS.remove(airship.getUniqueId());
        EXIT_LATCHES.remove(airship.getUniqueId());
    }

    /**
     * A completed source-side exit starts a fresh trail at its current pose. This discards
     * the just-unwound portal segment, so it cannot claim the coincident back face next tick.
     *
     * <p>The reset also counts as a re-base for the NEXT capture. Without that flag the
     * following tick rebuilt the segment from {@code airship.lastPose()} -- the pose the
     * body held before the exit, in the chart it was in before the exit -- which resurrects
     * exactly the huge cross-world sweep this reset exists to destroy.
     */
    public static void resetTrail(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail == null) {
            captureTrail(airship);
            return;
        }
        trail.older.set(airship.logicalPose());
        trail.start.set(airship.logicalPose());
        trail.end.set(airship.logicalPose());
        trail.hasOlder = false;
        trail.rebasedThisTick = true;
        trail.lastSeenTick = trailTick;
    }

    /**
     * ATOMIC EXIT SETTLEMENT. Not a tick job: the caller runs this the instant a crossing
     * (or a whole sub-tick chain of crossings) has consumed the physics segment, and it
     * answers one question -- has the body physically left the 0.01 slab of the portal it
     * came out of?
     *
     * <p>If it has, the exit is COMPLETE right now: the latch is dropped and the trail is
     * re-seeded at the pose the body actually holds, so the segment that led up to the exit
     * stops existing at the same instant. The caller then closes the straddle session for
     * the returned portal. That pairing is what makes the back face unreachable without any
     * timing window at all:
     * <ul>
     *   <li>still inside the slab -> the session is still open and owns the doorway, so
     *       nothing else may claim the body;</li>
     *   <li>outside the slab -> the session is closed AND the trail no longer contains a
     *       single point behind the plane, so even an instant reversal has no swept segment
     *       that could be read as an entry through the opposite face.</li>
     * </ul>
     *
     * @return the portal whose exit just completed, or null when the body is still inside
     *         the slab (or was never latched)
     */
    @org.jetbrains.annotations.Nullable
    public static UUID settleExit(ServerSubLevel airship) {
        UUID id = airship.getUniqueId();
        ExitLatch latch = EXIT_LATCHES.get(id);
        if (latch == null) return null;
        boolean expired = trailTick - latch.tick > EXIT_LATCH_MAX_TICKS;
        if (!expired && latch.overlaps(currentBounds(airship))) return null;
        EXIT_LATCHES.remove(id);
        resetTrail(airship);
        return latch.portalId;
    }

    /**
     * Re-bases a trail into the destination frame at the exact crossing time of a transit that
     * has just executed. Forgetting the trail (the previous behaviour) threw away the part of
     * the physics segment lying BEYOND the portal plane, so a body fast enough to cross two
     * portals inside one segment consumed only the first one and travelled the remainder in the
     * wrong chart. The retained remainder is [crossingTime, 1] mapped through the portal
     * isometry; history before the seam is dropped because it cannot be joined to destination
     * poses.
     */
    public static void rebaseTrailThroughPortal(
        ServerSubLevel airship, Portal portal, double crossingTime
    ) {
        UUID id = airship.getUniqueId();
        Trail trail = TRAILS.get(id);
        Pose3d entry;
        if (trail == null || !Double.isFinite(crossingTime)) {
            entry = new Pose3d(airship.logicalPose());
        } else {
            entry = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(
                interpolate(trail.start, trail.end, Math.clamp(crossingTime, 0.0, 1.0)));
        }
        if (trail == null) {
            trail = new Trail(new Pose3d(entry), new Pose3d(entry), new Pose3d(entry));
            TRAILS.put(id, trail);
        }
        // DIRECTION HISTORY MUST SURVIVE THE SEAM. Dropping it (hasOlder = false) left the
        // re-derived tail with a single post-seam segment and no evidence of where the body
        // came FROM. A bi-faced portal then had no directed source->destination entry to
        // prove face ownership, so every sub-tick chain link was refused and the body fell
        // through the instant one tick had to contain two crossings. Carry the pose held
        // just before the seam, mapped through the same isometry, so the tail is a genuine
        // continuation of the pre-seam motion instead of a segment with no past.
        double entryT = Double.isFinite(crossingTime) ? Math.clamp(crossingTime, 0.0, 1.0) : 0.0;
        Pose3d prior = IplStraddlePoseMap.StraddleMapping.of(portal).mapPose(
            interpolate(trail.start, trail.end, Math.max(0.0, entryT - 1.0e-3)));
        trail.older.set(prior);
        trail.hasOlder = true;
        trail.start.set(entry);
        // Snapshot of the previous position + the 1%-of-width band it must clear before
        // this exit counts. Without it a body at absurd speed can be re-admitted through
        // the coincident back face on the very next evaluation.
        armExitClearance(airship, portal);
        trail.end.set(airship.logicalPose());
        trail.rebasedThisTick = true;
        trail.lastSeenTick = trailTick;
    }

    /**
     * Predictive aperture arming. Answers whether the current segment, continued forward by
     * {@code lookaheadSegments}, drives the body into this finite aperture from the source
     * side. The straddle seam (image collider plus clip regions) is then opened one segment
     * early, so a body moving several blocks per tick meets an already porous doorway instead
     * of the intact source-side terrain behind the portal plane.
     */
    public static boolean willEnterAperture(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal,
        double apertureMargin, double lookaheadSegments
    ) {
        if (lookaheadSegments <= 0.0) return false;
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
        if (blocks.isEmpty()) return false;
        Trail trail = trail(airship);
        Pose3d ahead = interpolate(trail.start, trail.end, 1.0 + lookaheadSegments);
        SweepResult predicted = sweepSegment(portal, blocks,
            new Frame(trail.end), new Frame(ahead), sourceToDestNormal, apertureMargin);
        return predicted.intersects()
            && predicted.direction() == SweepDirection.TOWARD_DESTINATION;
    }

    /**
     * Position lerp with orientation slerp. {@code t} may exceed 1 to extrapolate the segment
     * forward; orientation is clamped at the segment end because extrapolated spin is not
     * evidence, only translation is used for lookahead admission.
     */
    private static Pose3d interpolate(Pose3dc from, Pose3dc to, double t) {
        Pose3d out = new Pose3d(from);
        out.position().set(
            from.position().x() + (to.position().x() - from.position().x()) * t,
            from.position().y() + (to.position().y() - from.position().y()) * t,
            from.position().z() + (to.position().z() - from.position().z()) * t);
        org.joml.Quaterniond rotation = new org.joml.Quaterniond(from.orientation());
        rotation.slerp(new org.joml.Quaterniond(to.orientation()), Math.clamp(t, 0.0, 1.0));
        out.orientation().set(rotation);
        return out;
    }

    /**
     * Snapshots the body's post-transit position and opens the exit-clearance band for the
     * portal it just came through. Called for every executed transit (including every link
     * of a sub-tick chain), so the latch always describes the most recent crossing.
     */
    public static void armExitClearance(ServerSubLevel airship, Portal portal) {
        if (portal == null) return;
        // CORRECTED SEMANTICS. This is an ABSOLUTE thickness (0.01 blocks), not a fraction
        // of the portal's width: the plane is given a real 0.01-thick body instead of being
        // a zero-thickness surface, and the band is symmetric -- 0.01 on the exit side and
        // 0.01 on the opposite side. A body must clear the whole slab before its exit is
        // counted, which is what makes fast back-and-forth re-entry impossible regardless
        // of how wide the portal happens to be.
        double clearance = Math.max(EXIT_CLEARANCE_THICKNESS, MIN_EXIT_CLEARANCE);
        // Resetting the swept buffer is the other half of the guarantee: the stale sweep
        // sample from BEFORE the crossing is what a re-entry would otherwise be tested
        // against, so it is dropped together with the snapshot being armed here.
        IplEnteringVolumeVisualization.clearBuffer(airship);
        // The PLATE. ONLY the normal direction gets thickness: the zero-thickness quad
        // becomes a slab of half-thickness `clearance` (0.01 on each face), while the
        // rectangle keeps the portal's own width and height. Widening W/H as well made
        // the plate spill outside the doorway, so a body falling PAST a portal (a loop of
        // several portals) kept re-triggering a latch that belongs to a crossing it never
        // made.
        // THE PLATE BELONGS TO THE EXIT, NOT TO THE ENTRANCE. This used to be built from
        // portal.getOriginPos() and the source-frame axes -- the rectangle the body flew
        // INTO -- while the body itself is now standing at portal.getDestPos(). Two
        // consequences, and together they are the whole bug:
        //
        //  1. The back-face guard never guarded anything. The body is nowhere near the
        //     entrance rectangle after a transit, so the slab it was tested against was
        //     empty space.
        //  2. In a two-portal loop (floor portal A, ceiling portal B, A -> B) the plate sat
        //     exactly on A -- which is precisely where the body arrives one fall later. The
        //     NEXT, entirely legitimate crossing of A was therefore refused, every time.
        //     While the body was slow enough to need less than one tick per lap that never
        //     showed; the moment per-tick travel reached the distance between the portals
        //     the crossing had to be resolved inside the same tick, the refusal killed the
        //     chain, and the body fell straight through the floor portal. That is the fixed
        //     ~200-tick lifetime of the loop, independent of tick rate: it is a SPEED
        //     threshold (travel per tick >= portal spacing), which is exactly why running
        //     the server at 5 TPS bought wall-clock seconds and not a single extra lap.
        //
        // The plate is therefore the DESTINATION rectangle, mapped through the portal's own
        // isometry: the surface the body actually came out of, and the only surface a
        // reversed re-entry can happen through.
        IplStraddlePoseMap.StraddleMapping exitFrame =
            IplStraddlePoseMap.StraddleMapping.of(portal);
        Vec3 origin = exitFrame.mapPoint(portal.getOriginPos());
        Vec3 normal = exitFrame.mapVec(portal.getNormal());
        Vec3 axisW = exitFrame.mapVec(portal.getAxisW());
        Vec3 axisH = exitFrame.mapVec(portal.getAxisH());
        EXIT_LATCHES.put(airship.getUniqueId(), new ExitLatch(
            portal.getUUID(),
            origin.x, origin.y, origin.z,
            normal.x, normal.y, normal.z,
            axisW.x, axisW.y, axisW.z,
            axisH.x, axisH.y, axisH.z,
            portal.getWidth() * 0.5,
            portal.getHeight() * 0.5,
            clearance, trailTick));
    }

    /**
     * True while the body still sits inside the exit-clearance band of its last crossing.
     * The latch self-clears the moment the band is passed (or after a hard tick budget), so
     * this is safe to call every tick and needs no explicit teardown.
     *
     * @param portal when non-null, only a latch belonging to THIS portal answers true;
     *               pass null to ask "is this body still leaving anything at all?"
     */
    public static boolean withinExitClearance(ServerSubLevel airship, Portal portal) {
        UUID id = airship.getUniqueId();
        ExitLatch latch = EXIT_LATCHES.get(id);
        if (latch == null) return false;
        if (trailTick - latch.tick > EXIT_LATCH_MAX_TICKS) {
            EXIT_LATCHES.remove(id);
            return false;
        }
        // Test the POST-CROSSING pose only. Using sweptBounds() here was the bug: that
        // box also contains trail.start / trail.older, i.e. the pose the body held BEFORE
        // the crossing, which by construction lies on the other side of the plate. The
        // union therefore always intersected the slab and the latch could only ever be
        // released by the tick budget -- which is why the loop died after a fixed number
        // of seconds instead of after the body physically left the doorway.
        //
        // This method is a PREDICATE. It must not mutate the trail: the segment tail is
        // still needed by the other portals scanned in this same tick and by the sub-tick
        // crossing chain. Trail re-seeding belongs to the caller that actually completes
        // the exit.
        if (!latch.overlaps(currentBounds(airship))) {
            EXIT_LATCHES.remove(id);
            return false;
        }
        return portal == null || latch.portalId.equals(portal.getUUID());
    }

    public static void clearTrails() {
        TRAILS.clear();
        EXIT_LATCHES.clear();
        trailTick = 0L;
    }

    /** Debug-only prior endpoint. Null until the current ship has one completed segment. */
    public static Pose3dc bufferedPose(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        return trail != null && trail.hasOlder ? trail.older : null;
    }

    /** World bounds of the body at fraction {@code t} of the retained segment. */
    public static AABB boundsAt(ServerSubLevel airship, double t) {
        Trail trail = trail(airship);
        double clamped = Double.isFinite(t) ? Math.clamp(t, 0.0, 1.0) : 0.0;
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, interpolate(trail.start, trail.end, clamped));
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    /** Length in blocks of the retained segment (translation only). */
    public static double segmentLength(ServerSubLevel airship) {
        Trail trail = trail(airship);
        return trail.start.position().distance(trail.end.position());
    }

    /**
     * GEOMETRIC RE-ENTRY GUARD. Asks whether the body would ENTER a new doorway from
     * inside the 0.01 slab of the doorway it just came out of.
     *
     * <p>The identity-based guard ({@link #withinExitClearance}) only ever refused the
     * portal that was just used and its coincident twin, and that is not what breaks a
     * loop. A loop is built from a PAIR of portals: the instant the body lands at the
     * exit of A it is standing in the aperture of B, whose own back face leads straight
     * back to A. Both faces belong to portals the guard considered unrelated, so the
     * sub-tick chain teleported the body A-B-A-B... at zero travelled distance until it
     * ran out of the per-tick budget -- 16 useless parent flips per tick with the default
     * cap (which is what degraded the loop and eventually dropped the body through), and
     * 512 chunk rehomes per tick with the raised cap, which is simply a crash.
     *
     * <p>This test is geometric, so a genuine multi-portal loop is untouched: the entry
     * point of a portal even 0.02 blocks away from the previous exit is outside the slab
     * and is admitted immediately. Only an entry that happens INSIDE the slab -- i.e. at
     * zero travelled distance from the exit -- is refused, and only until the body has
     * physically left the slab, which at loop speeds takes a fraction of a tick.
     *
     * @param entryTime fraction of the retained segment at which the entry occurs
     */
    public static boolean entryWithinExitSlab(ServerSubLevel airship, double entryTime) {
        UUID id = airship.getUniqueId();
        ExitLatch latch = EXIT_LATCHES.get(id);
        if (latch == null) return false;
        if (trailTick - latch.tick > EXIT_LATCH_MAX_TICKS) {
            EXIT_LATCHES.remove(id);
            return false;
        }
        return latch.overlaps(boundsAt(airship, entryTime));
    }

    /** Broad phase of the CURRENT pose only, with no swept history. */
    public static AABB currentBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.end);
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    /** Broad phase covers both retained swept segments. */
    public static AABB sweptBounds(ServerSubLevel airship) {
        Trail trail = trail(airship);
        Bounds bounds = new Bounds();
        includePlotBounds(bounds, airship, trail.start);
        includePlotBounds(bounds, airship, trail.end);
        if (trail.hasOlder) includePlotBounds(bounds, airship, trail.older);
        return new AABB(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    public static CrossingState evaluate(ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal) {
        return evaluate(airship, portal, sourceToDestNormal, 0.0);
    }

    public static CrossingState evaluate(
        ServerSubLevel airship, Portal portal, Vec3 sourceToDestNormal, double apertureMargin
    ) {
        List<BlockPos> blocks = IplPortalVolumeCache.blocks(airship);
        if (blocks.isEmpty()) {
            return new CrossingState(
                CrossingPhase.APPROACHING, false, false, false,
                SweepDirection.NONE, SweepDirection.NONE, Double.NaN);
        }

        Trail trail = trail(airship);
        Frame start = new Frame(trail.start);
        Frame end = new Frame(trail.end);
        Frame older = trail.hasOlder ? new Frame(trail.older) : null;
        Sample current = sample(blocks, end, portal.getOriginPos(), sourceToDestNormal);
        Sample oldest = sample(blocks, older == null ? start : older, portal.getOriginPos(), sourceToDestNormal);
        SweepResult recent = sweepSegment(
            portal, blocks, start, end, sourceToDestNormal, apertureMargin);
        SweepResult historic = older == null ? SweepResult.NONE : sweepSegment(
            portal, blocks, older, start, sourceToDestNormal, apertureMargin);
        SweepResult sweep = recent.mergePreferred(historic);

        CrossingState result = new CrossingState(
            phase(current.minDistance, current.maxDistance),
            // This is entry evidence, not phase hysteresis. Requiring the body to be an
            // extra band behind the plane loses slow crossings whose leading block first
            // touches it within that band.
            oldest.minDistance < -EPSILON,
            sweep.intersects,
            overlapsAperture(portal, blocks, end, sourceToDestNormal, apertureMargin),
            sweep.direction(),
            recent.direction(),
            Double.isFinite(recent.towardTime()) ? recent.towardTime()
                : (Double.isFinite(sweep.towardTime()) ? sweep.towardTime() : Double.NaN));
        IplEnteringVolumeVisualization.record(airship, portal, result);
        return result;
    }

    private static Trail trail(ServerSubLevel airship) {
        Trail trail = TRAILS.get(airship.getUniqueId());
        if (trail != null) return trail;
        return new Trail(new Pose3d(airship.lastPose()), new Pose3d(airship.lastPose()),
            new Pose3d(airship.logicalPose()));
    }

    private static void includePlotBounds(Bounds out, ServerSubLevel airship, Pose3dc pose) {
        var bounds = airship.getPlot().getBoundingBox();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            out.include(pose.transformPosition(new Vec3(
                x == 0 ? bounds.minX() : bounds.maxX() + 1.0,
                y == 0 ? bounds.minY() : bounds.maxY() + 1.0,
                z == 0 ? bounds.minZ() : bounds.maxZ() + 1.0)));
        }
    }

    private static CrossingPhase phase(double min, double max) {
        if (min >= PORTAL_HYSTERESIS_DEPTH) return CrossingPhase.CROSSED;
        return max >= -PORTAL_HYSTERESIS_DEPTH ? CrossingPhase.STRADDLING : CrossingPhase.APPROACHING;
    }

    /**
     * Exact endpoint OBB projection using the cached pose basis, with zero per-block allocations.
     *
     * <p>Phase is physical block geometry. Swept aperture admission below deliberately
     * uses a conservative endpoint AABB, so very fast motion cannot tunnel past a finite
     * portal. These tests answer different questions and must not be conflated.
     */
    private static Sample sample(List<BlockPos> blocks, Frame frame, Vec3 plane, Vec3 normal) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double support = frame.obbSupport(normal.x, normal.y, normal.z);
        for (BlockPos block : blocks) {
            double distance = (frame.centerX(block) - plane.x) * normal.x
                + (frame.centerY(block) - plane.y) * normal.y
                + (frame.centerZ(block) - plane.z) * normal.z;
            min = Math.min(min, distance - support);
            max = Math.max(max, distance + support);
        }
        return new Sample(min, max);
    }

    /** Swept AABB vs finite portal plane. `from`/`to` can be arbitrarily far apart. */
    private static SweepResult sweepSegment(
        Portal portal, List<BlockPos> blocks, Frame from, Frame to, Vec3 normal, double margin
    ) {
        boolean intersects = false;
        double towardTime = Double.POSITIVE_INFINITY;
        double sourceTime = Double.POSITIVE_INFINITY;
        Vec3 origin = portal.getOriginPos();
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        double fromPlaneSupport = from.aabbSupport(normal.x, normal.y, normal.z);
        double toPlaneSupport = to.aabbSupport(normal.x, normal.y, normal.z);
        // Keep the endpoint AABB for conservative swept-aperture admission, but never
        // use it to choose a portal face. Its projection is wider than the physical OBB
        // on an inclined plane, which can hide a genuine source-side entry entirely.
        double fromPhysicalPlaneSupport = from.obbSupport(normal.x, normal.y, normal.z);
        double toPhysicalPlaneSupport = to.obbSupport(normal.x, normal.y, normal.z);
        double planeHalf = Math.max(fromPlaneSupport, toPlaneSupport);
        double halfW = portal.getWidth() * 0.5
            + Math.max(from.aabbSupport(axisW.x, axisW.y, axisW.z), to.aabbSupport(axisW.x, axisW.y, axisW.z)) + margin;
        double halfH = portal.getHeight() * 0.5
            + Math.max(from.aabbSupport(axisH.x, axisH.y, axisH.z), to.aabbSupport(axisH.x, axisH.y, axisH.z)) + margin;
        for (BlockPos block : blocks) {
            double fromX = from.centerX(block), fromY = from.centerY(block), fromZ = from.centerZ(block);
            double toX = to.centerX(block), toY = to.centerY(block), toZ = to.centerZ(block);
            double fromPlane = (fromX - origin.x) * normal.x + (fromY - origin.y) * normal.y + (fromZ - origin.z) * normal.z;
            double toPlane = (toX - origin.x) * normal.x + (toY - origin.y) * normal.y + (toZ - origin.z) * normal.z;
            double deltaPlane = toPlane - fromPlane;
            double start;
            double end;
            if (Math.abs(deltaPlane) <= EPSILON) {
                if (Math.abs(fromPlane) > planeHalf + PORTAL_HYSTERESIS_DEPTH + EPSILON) continue;
                start = 0.0;
                end = 1.0;
            } else {
                double a = (-planeHalf - PORTAL_HYSTERESIS_DEPTH - fromPlane) / deltaPlane;
                double b = (planeHalf + PORTAL_HYSTERESIS_DEPTH - fromPlane) / deltaPlane;
                start = Math.max(0.0, Math.min(a, b));
                end = Math.min(1.0, Math.max(a, b));
                if (start > end + EPSILON) continue;
            }
            if (!segmentIntersectsAperture(
                origin, axisW, axisH, fromX, fromY, fromZ, toX, toY, toZ, start, end, halfW, halfH)) continue;

            intersects = true;
            // Face selection follows the block's own continuous A-to-B trajectory.
            // No position threshold or seam fallback: the opposite face receives the
            // same hit with the opposite signed motion and is rejected by controller.
            // Direction belongs to a real volume-boundary crossing only. A block that
            // already straddles the plane may drift or jitter in either signed direction;
            // that must retain an existing session, never choose a new portal face.
            boolean enteredDestination = fromPlane + fromPhysicalPlaneSupport < -EPSILON
                && toPlane + toPhysicalPlaneSupport >= -EPSILON;
            boolean enteredSource = fromPlane - fromPhysicalPlaneSupport > EPSILON
                && toPlane - toPhysicalPlaneSupport <= EPSILON;
            if (enteredDestination) {
                towardTime = Math.min(towardTime, start);
            } else if (enteredSource) {
                sourceTime = Math.min(sourceTime, start);
            }
        }
        return new SweepResult(intersects, towardTime, sourceTime);
    }

    /** Current finite-aperture overlap for an already-owned straddle session. */
    private static boolean overlapsAperture(
        Portal portal, List<BlockPos> blocks, Frame frame, Vec3 normal, double margin
    ) {
        Vec3 origin = portal.getOriginPos();
        Vec3 axisW = portal.getAxisW();
        Vec3 axisH = portal.getAxisH();
        double halfW = portal.getWidth() * 0.5
            + margin;
        double halfH = portal.getHeight() * 0.5
            + margin;
        for (BlockPos block : blocks) {
            if (frame.intersectsAperture(block, origin, normal, axisW, axisH, halfW, halfH)) {
                return true;
            }
        }
        return false;
    }

    /** Liang-Barsky segment clipping against width/height aperture expanded by the swept AABB. */
    private static boolean segmentIntersectsAperture(
        Vec3 origin, Vec3 axisW, Vec3 axisH,
        double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
        double planeStart, double planeEnd, double halfW, double halfH
    ) {
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double startX = fromX + dx * planeStart - origin.x;
        double startY = fromY + dy * planeStart - origin.y;
        double startZ = fromZ + dz * planeStart - origin.z;
        double endX = fromX + dx * planeEnd - origin.x;
        double endY = fromY + dy * planeEnd - origin.y;
        double endZ = fromZ + dz * planeEnd - origin.z;
        double startW = startX * axisW.x + startY * axisW.y + startZ * axisW.z;
        double startH = startX * axisH.x + startY * axisH.y + startZ * axisH.z;
        double deltaW = (endX - startX) * axisW.x + (endY - startY) * axisW.y + (endZ - startZ) * axisW.z;
        double deltaH = (endX - startX) * axisH.x + (endY - startY) * axisH.y + (endZ - startZ) * axisH.z;
        double lower = 0.0;
        double upper = 1.0;
        if (Math.abs(deltaW) <= EPSILON) {
            if (startW < -halfW - EPSILON || startW > halfW + EPSILON) return false;
        } else {
            double a = (-halfW - startW) / deltaW;
            double b = (halfW - startW) / deltaW;
            lower = Math.max(lower, Math.min(a, b));
            upper = Math.min(upper, Math.max(a, b));
            if (lower > upper + EPSILON) return false;
        }
        if (Math.abs(deltaH) <= EPSILON) {
            return startH >= -halfH - EPSILON && startH <= halfH + EPSILON;
        }
        double a = (-halfH - startH) / deltaH;
        double b = (halfH - startH) / deltaH;
        lower = Math.max(lower, Math.min(a, b));
        upper = Math.min(upper, Math.max(a, b));
        return lower <= upper + EPSILON;
    }

    /** Snapshot of the previous (crossing-time) position plus the band it must clear. */
    private static final class ExitLatch {
        final UUID portalId;
        final double ox, oy, oz;
        final double nx, ny, nz;
        final double wx, wy, wz;
        final double hx, hy, hz;
        final double halfW, halfH, halfThickness;
        final long tick;

        ExitLatch(UUID portalId,
                  double ox, double oy, double oz,
                  double nx, double ny, double nz,
                  double wx, double wy, double wz,
                  double hx, double hy, double hz,
                  double halfW, double halfH, double halfThickness, long tick) {
            this.portalId = portalId;
            this.ox = ox; this.oy = oy; this.oz = oz;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.wx = wx; this.wy = wy; this.wz = wz;
            this.hx = hx; this.hy = hy; this.hz = hz;
            this.halfW = halfW;
            this.halfH = halfH;
            this.halfThickness = halfThickness;
            this.tick = tick;
        }

        /**
         * Conservative separating-axis test of a world AABB against the plate. The plate
         * is an oriented box: half-thickness along the portal normal, half-width and
         * half-height along the portal's own axes, each already grown by the 0.01 margin.
         * Testing the body's SWEPT bounds (not just its current pose) is deliberate --
         * a body fast enough to pass the whole plate inside one segment must still be
         * seen as having been inside it.
         */
        boolean overlaps(AABB box) {
            double cx = (box.minX + box.maxX) * 0.5 - ox;
            double cy = (box.minY + box.maxY) * 0.5 - oy;
            double cz = (box.minZ + box.maxZ) * 0.5 - oz;
            double ex = (box.maxX - box.minX) * 0.5;
            double ey = (box.maxY - box.minY) * 0.5;
            double ez = (box.maxZ - box.minZ) * 0.5;
            double dN = cx * nx + cy * ny + cz * nz;
            double sN = Math.abs(nx) * ex + Math.abs(ny) * ey + Math.abs(nz) * ez;
            if (Math.abs(dN) > halfThickness + sN) return false;
            double dW = cx * wx + cy * wy + cz * wz;
            double sW = Math.abs(wx) * ex + Math.abs(wy) * ey + Math.abs(wz) * ez;
            if (Math.abs(dW) > halfW + sW) return false;
            double dH = cx * hx + cy * hy + cz * hz;
            double sH = Math.abs(hx) * ex + Math.abs(hy) * ey + Math.abs(hz) * ez;
            return Math.abs(dH) <= halfH + sH;
        }
    }

    private static final class Trail {
        final Pose3d older;
        final Pose3d start;
        final Pose3d end;
        boolean hasOlder;
        /**
         * Set by {@link #rebaseTrailThroughPortal}; consumed by the next capture. Marks
         * that this trail's end is already in a post-flip chart, so the pre-flip
         * {@code lastPose()} must NOT be used as the following segment's start.
         */
        boolean rebasedThisTick;
        long lastSeenTick;

        Trail(Pose3d older, Pose3d start, Pose3d end) {
            this.older = older;
            this.start = start;
            this.end = end;
        }
    }

    /** Transform basis and endpoint world-AABB support, shared by every block in one pose. */
    private static final class Frame {
        final double ox, oy, oz;
        final double xx, xy, xz, yx, yy, yz, zx, zy, zz;
        final double halfX, halfY, halfZ;

        Frame(Pose3dc pose) {
            Vec3 origin = pose.transformPosition(Vec3.ZERO);
            Vec3 x = pose.transformPosition(new Vec3(1.0, 0.0, 0.0)).subtract(origin);
            Vec3 y = pose.transformPosition(new Vec3(0.0, 1.0, 0.0)).subtract(origin);
            Vec3 z = pose.transformPosition(new Vec3(0.0, 0.0, 1.0)).subtract(origin);
            ox = origin.x; oy = origin.y; oz = origin.z;
            xx = x.x; xy = x.y; xz = x.z;
            yx = y.x; yy = y.y; yz = y.z;
            zx = z.x; zy = z.y; zz = z.z;
            halfX = (Math.abs(xx) + Math.abs(yx) + Math.abs(zx)) * 0.5;
            halfY = (Math.abs(xy) + Math.abs(yy) + Math.abs(zy)) * 0.5;
            halfZ = (Math.abs(xz) + Math.abs(yz) + Math.abs(zz)) * 0.5;
        }

        double centerX(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return ox + xx * x + yx * y + zx * z;
        }

        double centerY(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return oy + xy * x + yy * y + zy * z;
        }

        double centerZ(BlockPos block) {
            double x = block.getX() + 0.5, y = block.getY() + 0.5, z = block.getZ() + 0.5;
            return oz + xz * x + yz * y + zz * z;
        }

        double aabbSupport(double x, double y, double z) {
            return halfX * Math.abs(x) + halfY * Math.abs(y) + halfZ * Math.abs(z);
        }

        double obbSupport(double x, double y, double z) {
            return (Math.abs(xx * x + xy * y + xz * z)
                + Math.abs(yx * x + yy * y + yz * z)
                + Math.abs(zx * x + zy * y + zz * z)) * 0.5;
        }

        /** Exact SAT test between one transformed block OBB and the finite portal rectangle. */
        boolean intersectsAperture(
            BlockPos block, Vec3 origin, Vec3 normal, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH
        ) {
            double centerX = centerX(block), centerY = centerY(block), centerZ = centerZ(block);
            return !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, axisW.x, axisW.y, axisW.z)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, axisH.x, axisH.y, axisH.z)
                && !separatesAperture(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, normal.x, normal.y, normal.z)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, xx, xy, xz, normal)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, yx, yy, yz, normal)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, axisW)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, axisH)
                && !crossSeparates(centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH, zx, zy, zz, normal);
        }

        private boolean crossSeparates(
            double centerX, double centerY, double centerZ, Vec3 origin, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH, double ax, double ay, double az, Vec3 b
        ) {
            double crossX = ay * b.z - az * b.y;
            double crossY = az * b.x - ax * b.z;
            double crossZ = ax * b.y - ay * b.x;
            return crossX * crossX + crossY * crossY + crossZ * crossZ > EPSILON * EPSILON
                && separatesAperture(
                    centerX, centerY, centerZ, origin, axisW, axisH, halfW, halfH,
                    crossX, crossY, crossZ);
        }

        private boolean separatesAperture(
            double centerX, double centerY, double centerZ, Vec3 origin, Vec3 axisW, Vec3 axisH,
            double halfW, double halfH, double axisX, double axisY, double axisZ
        ) {
            double relX = centerX - origin.x, relY = centerY - origin.y, relZ = centerZ - origin.z;
            double centerDistance = Math.abs(relX * axisX + relY * axisY + relZ * axisZ);
            double blockRadius = obbSupport(axisX, axisY, axisZ);
            double rectangleRadius = halfW * Math.abs(axisW.x * axisX + axisW.y * axisY + axisW.z * axisZ)
                + halfH * Math.abs(axisH.x * axisX + axisH.y * axisY + axisH.z * axisZ);
            return centerDistance > blockRadius + rectangleRadius + EPSILON;
        }
    }

    private record Sample(double minDistance, double maxDistance) {}

    private record SweepResult(boolean intersects, double towardTime, double sourceTime) {
        static final SweepResult NONE = new SweepResult(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);

        /** Current segment has priority; history only fills a stationary/absent current trail. */
        SweepResult mergePreferred(SweepResult historic) {
            if (direction() != SweepDirection.NONE) return this;
            if (historic.direction() != SweepDirection.NONE) {
                return new SweepResult(intersects || historic.intersects, historic.towardTime, historic.sourceTime);
            }
            return new SweepResult(intersects || historic.intersects, towardTime, sourceTime);
        }

        SweepDirection direction() {
            boolean toward = Double.isFinite(towardTime);
            boolean source = Double.isFinite(sourceTime);
            if (!toward && !source) return SweepDirection.NONE;
            if (toward && !source) return SweepDirection.TOWARD_DESTINATION;
            if (!toward) return SweepDirection.TOWARD_SOURCE;
            if (towardTime + EPSILON < sourceTime) return SweepDirection.TOWARD_DESTINATION;
            if (sourceTime + EPSILON < towardTime) return SweepDirection.TOWARD_SOURCE;
            return SweepDirection.AMBIGUOUS;
        }
    }

    private static final class Bounds {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        void include(Vec3 point) {
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y); minZ = Math.min(minZ, point.z);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y); maxZ = Math.max(maxZ, point.z);
        }
    }
}
