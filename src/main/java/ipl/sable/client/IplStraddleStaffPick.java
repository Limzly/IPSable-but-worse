package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;

import com.mojang.datafixers.util.Pair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Through-portal pick for straddling hosted sub-levels.
 *
 * <p>Sable's raycast overlay transforms the ray into each intersecting sub-level's local
 * space via the level pose provider — which is unmapped for a ship straddling in from
 * another dimension, so picks at the through-part miss. This helper raycasts the SAME ray
 * through each straddle projection's MAPPED pose instead: the ray is inverse-transformed
 * into plot space and clipped there (block reads resolve through the plot bridge from any
 * dimension). The returned {@link BlockHitResult} is in PLOT coordinates, matching Sable's
 * own convention for sub-level hits — so {@code getContainingClient(hit.getLocation())}
 * and everything downstream (drag anchors, lock points) work unchanged.
 *
 * <p>This class owns pick/drag CAPTURE state only. The frame algebra of an active drag
 * lives in the grab chain ({@link IplGrabChainClient} / server {@code IplGrabChain});
 * beam geometry lives in {@link IplStaffBeamRoutes}. The pick's job at grab start is to
 * hand the chain its seed: the exact portal path the pick ray traversed, plus — when the
 * click landed on a straddle IMAGE rather than the native half — the session portal whose
 * inverse links the image's frame back to the body's parent frame. Which representation
 * was clicked is resolved geometrically against the actual ray (both candidate positions
 * are computed and the one the ray intersects wins), not guessed from player position.
 */
public final class IplStraddleStaffPick {

    /** Last pick, awaiting confirmation by Simulated's drag-session creation. */
    private static final Map<UUID, PortalTarget> PENDING_TARGETS = new HashMap<>();

    /** Locked drag frame. It never changes because a later hover happened to hit a portal. */
    private static final Map<UUID, PortalTarget> DRAG_TARGETS = new HashMap<>();

    /**
     * VISIBLE ray length from eye to the picked point, captured at pick, keyed by sub-level.
     * Stock derives the hold distance from {@code logicalPose} (the body's NATIVE position),
     * which is a different coordinate space for a cross-dimension grab and a different
     * location for a same-dimension image grab. The physical pick already walked the true
     * distance the ray travelled (rigid mappings preserve length); the drag session and the
     * beam's initial node density both use it.
     */
    private static final Map<UUID, Double> GRAB_DISTANCE = new HashMap<>();

    private IplStraddleStaffPick() {}

    /** Best projection hit for a ray, or null. Used by the staff fallback. */
    @Nullable
    public static HitResult pickStraddleProjections(Player player, double range, float partialTick) {
        if (!(player.level() instanceof ClientLevel level)) return null;
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 end = eye.add(player.getViewVector(partialTick).scale(range));
        ProjectionHit best = clipProjections(level, new ClipContext(
            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return best != null ? best.hit() : null;
    }

    /**
     * Raycast through IP portals, including straddle projections in every reached world.
     * Returns only Sable targets; ordinary portal block targeting remains IP's responsibility.
     */
    @Nullable
    public static PortalTarget pickThroughPortals(Player player, double range, float partialTick) {
        Vec3 eye = player.getEyePosition(partialTick);
        PortalTarget target = pickThroughPortals(
            player, player.level(), eye, player.getViewVector(partialTick), range, List.of(), 0, 0.0
        );
        if (target == null) return null;
        // Exactly ONE pick may be pending at a time. The map used to accumulate captures
        // across aborted picks, and startDraggingSubLevel would happily consume a stale
        // one -- binding a freshly grabbed build to a portal path it was never picked
        // through.
        PENDING_TARGETS.clear();
        PENDING_TARGETS.put(target.sub().getUniqueId(), target);
        GRAB_DISTANCE.put(target.sub().getUniqueId(), target.visibleDistance());
        return target;
    }

    /** Portal recursion with Sable plot hits compared in their visible world frame. */
    @Nullable
    private static PortalTarget pickThroughPortals(
        Player player, Level level, Vec3 from, Vec3 direction, double remaining,
        List<Portal> path, int depth, double traveled
    ) {
        if (depth > 3 || remaining <= 0.0) return null;

        Vec3 to = from.add(direction.scale(remaining));
        BlockHitResult block = level.clip(new ClipContext(
            from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
        ));
        ClientSubLevel sub = block.getType() == HitResult.Type.MISS ? null : getHitSubLevel(level, block);
        VisibleHit visible = sub == null ? null : visibleHit(level, from, to, sub, block);
        double blockDistance = visible == null ? Double.MAX_VALUE : visible.distance();

        Optional<Pair<Portal, qouteall.q_misc_util.my_util.RayTraceResult>> portalHit =
            qouteall.imm_ptl.core.portal.PortalUtils.raytracePortals(
                level, from, to, true, portal -> portal.isInteractableBy(player)
            );
        double portalDistance = portalHit.map(hit -> hit.getSecond().hitPos().distanceTo(from))
            .orElse(Double.MAX_VALUE);

        // PROJECTION PASS IN THE DESTINATION WORLD.
        //
        // `level.clip` above only finds geometry that physically lives in `level`. A
        // hosted plot does not: it lives in the sub-level container dimension and appears
        // in this world only as a portal-mapped IMAGE. On the player's own level that gap
        // is covered by the ClientLevel clip override, but this recursion walks into
        // `portal.getDestinationWorld()` and asks it for raw blocks, so a body straddling
        // into the far side was simply not there to be hit. The ray fell through it and
        // the pick resolved against whatever real world geometry stood behind -- which is
        // exactly the "the point is not refracted into the portal, it flies off to real
        // world coordinates" behaviour. Clip the same ray against every straddle
        // projection into this world and let the nearest of the three candidates win.
        ProjectionHit projected = null;
        if (level instanceof ClientLevel projectionLevel) {
            projected = clipProjections(projectionLevel, new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        }
        if (projected != null && projected.sub() != null) {
            double projectedDistance = Math.sqrt(projected.distSq());
            if (projectedDistance <= blockDistance
                && projectedDistance <= portalDistance + 0.0001) {
                return new PortalTarget(
                    projected.sub(), level, projected.hit(), path,
                    projected.imagePortal(), traveled + projectedDistance);
            }
        }

        if (visible != null && blockDistance <= portalDistance + 0.0001) {
            // traveled through prior frames + the visible reach within this frame = the true
            // ray length to the grabbed point (mappings are rigid, so lengths add).
            return new PortalTarget(
                sub, level, block, path, visible.imagePortal(), traveled + blockDistance);
        }
        if (block.getType() != HitResult.Type.MISS && portalDistance > blockDistance) {
            return null;
        }
        if (portalHit.isEmpty()) return null;

        Portal portal = portalHit.get().getFirst();
        qouteall.q_misc_util.my_util.RayTraceResult trace = portalHit.get().getSecond();
        double rest = remaining - portalDistance;
        if (rest <= 0.0) return null;

        java.util.ArrayList<Portal> nextPath = new java.util.ArrayList<>(path);
        nextPath.add(portal);
        return pickThroughPortals(
            player,
            portal.getDestinationWorld(),
            portal.transformPoint(trace.hitPos()).add(trace.surfaceNormal().scale(-0.001)),
            portal.transformLocalVecNonScale(direction),
            rest,
            List.copyOf(nextPath),
            depth + 1,
            traveled + portalDistance
        );
    }

    /**
     * Which visible representation of {@code sub} did this ray actually hit? Both concrete
     * candidates are computed — the native pose (when the body is native to {@code level})
     * and the straddle-session image (when one projects into {@code level}) — and the one
     * geometrically ON the ray wins. Exact candidate resolution, not a side heuristic; for
     * a same-dimension straddle both candidates exist and this is the only correct
     * disambiguation of "grabbed the emerged half" vs "grabbed the native half".
     */
    @Nullable
    private static VisibleHit visibleHit(
        Level level, Vec3 from, Vec3 to, ClientSubLevel sub, BlockHitResult hit
    ) {
        Vec3 nearest = null;
        double nearestError = Double.MAX_VALUE;
        Portal imagePortal = null;

        if (ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) == level) {
            Vec3 candidate = sub.renderPose().transformPosition(hit.getLocation());
            nearest = candidate;
            nearestError = rayErrorSq(from, to, candidate);
        }

        IplClientHostedLookup.StraddleProjection projection =
            IplClientHostedLookup.getStraddleProjectionFor(sub);
        if (projection != null && projection.portal().getDestDim() == level.dimension()) {
            Vec3 candidate = projection.mappedPose().transformPosition(hit.getLocation());
            double error = rayErrorSq(from, to, candidate);
            if (error < nearestError) {
                nearest = candidate;
                nearestError = error;
                imagePortal = projection.portal();
            }
        }
        return nearest == null ? null : new VisibleHit(from.distanceTo(nearest), imagePortal);
    }

    private record VisibleHit(double distance, @Nullable Portal imagePortal) {}

    private static double rayErrorSq(Vec3 from, Vec3 to, Vec3 point) {
        Vec3 ray = to.subtract(from);
        double lengthSq = ray.lengthSqr();
        if (lengthSq < 1.0e-12) return point.distanceToSqr(from);
        double t = Math.clamp(point.subtract(from).dot(ray) / lengthSq, 0.0, 1.0);
        return point.distanceToSqr(from.add(ray.scale(t)));
    }

    /** Current world render path, ordered from player world to current world. */
    public static List<Portal> getActiveRenderPath() {
        if (!qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
            return List.of();
        }
        return qouteall.imm_ptl.core.render.context_management.PortalRendering.getPortalPath();
    }

    /**
     * Simulated created a drag session for this selected ship: lock the captured pick and
     * seed the grab chain from it (traversed portals forward, image session inverse).
     */
    public static void beginDrag(ClientSubLevel sub) {
        PortalTarget selected = PENDING_TARGETS.remove(sub.getUniqueId());
        if (selected == null || selected.sub() != sub) {
            DRAG_TARGETS.remove(sub.getUniqueId());
            IplGrabChainClient.seedLocal(sub.getUniqueId(), List.of(), null);
            return;
        }
        DRAG_TARGETS.put(sub.getUniqueId(), selected);
        IplGrabChainClient.seedLocal(sub.getUniqueId(), selected.portals(), selected.imagePortal());
    }

    /**
     * LOCK ("fixate") path. Simulated routes a lock through the same pick as a grab but
     * never enters {@code startDraggingSubLevel}, so the portal chain was never seeded and
     * the beam had nothing to fold through: it was drawn straight to the body's RAW world
     * coordinates instead of through the portal. A lock is the same optical situation as a
     * grab, so it publishes the same chain -- the only difference is that the pending
     * capture is not consumed, because a following grab on the same body must still see it.
     */
    public static void beginLock(ClientSubLevel sub) {
        PortalTarget selected = PENDING_TARGETS.get(sub.getUniqueId());
        if (selected == null || selected.sub() != sub) {
            DRAG_TARGETS.remove(sub.getUniqueId());
            IplGrabChainClient.seedLocal(sub.getUniqueId(), List.of(), null);
            return;
        }
        DRAG_TARGETS.put(sub.getUniqueId(), selected);
        IplGrabChainClient.seedLocal(sub.getUniqueId(), selected.portals(), selected.imagePortal());
    }

    public static void clearDragTargets() {
        PENDING_TARGETS.clear();
        DRAG_TARGETS.clear();
        GRAB_DISTANCE.clear();
        IplGrabChainClient.clearLocal();
        IplStaffBeamRoutes.clearAll();
    }

    /**
     * Overwrite the fresh drag session's hold distance with the true visible pick distance.
     * Called at the TAIL of {@code startDraggingSubLevel}, after Simulated created the session
     * with its native-pose distance. No-op for a plain local grab (no captured pick distance),
     * so ordinary same-world dragging keeps stock behavior.
     */
    public static void applyGrabDistance(PhysicsStaffClientHandler handler,
                                         dev.ryanhcode.sable.sublevel.SubLevel sub) {
        Double distance = GRAB_DISTANCE.get(sub.getUniqueId());
        if (distance == null) return;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        if (session == null || !session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())) return;
        session.setDistance(Math.clamp(distance, 2.0, PhysicsStaffItem.RANGE));
    }

    /**
     * VISIBLE ray length from the eye to whatever this hit actually represents, or
     * {@link Double#MAX_VALUE} when there is nothing comparable to measure.
     *
     * <p>Sub-level hits report PLOT coordinates, so their raw location cannot be measured
     * against the eye directly. The same candidate resolution the through-portal pick uses
     * maps them into the visible frame first, which is the only frame in which a local hit
     * and a through-portal hit are comparable at all.
     */
    public static double visibleRayLength(Player player, @Nullable HitResult hit, float partialTick) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) return Double.MAX_VALUE;
        if (!(player.level() instanceof ClientLevel level)) return Double.MAX_VALUE;
        Vec3 eye = player.getEyePosition(partialTick);
        if (hit instanceof BlockHitResult blockHit) {
            ClientSubLevel sub = getHitSubLevel(level, blockHit);
            if (sub != null) {
                Vec3 end = eye.add(player.getViewVector(partialTick).scale(PhysicsStaffItem.RANGE));
                VisibleHit visible = visibleHit(level, eye, end, sub, blockHit);
                return visible == null ? Double.MAX_VALUE : visible.distance();
            }
        }
        return eye.distanceTo(hit.getLocation());
    }

    /**
     * Drop a speculative through-portal capture the pick did not end up choosing.
     * {@link #pickThroughPortals(Player, double, float)} records its result eagerly (the
     * recursion is the only place the traversed portal path exists), so whoever rejects
     * that result owns the cleanup. Without it the rejected capture stays pending and the
     * next drag session seeds a portal chain for a body that was grabbed locally.
     */
    public static void discardTarget(PortalTarget target) {
        UUID id = target.sub().getUniqueId();
        PENDING_TARGETS.remove(id);
        GRAB_DISTANCE.remove(id);
    }

    /** True pick-ray length for this sub's last pick, or NaN (beam creation node density). */
    public static double pickDistance(UUID subId) {
        Double distance = GRAB_DISTANCE.get(subId);
        return distance == null ? Double.NaN : distance;
    }

    /**
     * Convert the staff's mouse pitch axis into the grabbed body's constraint frame.
     *
     * <p>Derivation: the player rotates what they SEE. With E the beam route's frame fold
     * (rotation composition of its links) and M the image-refinement rotation when the beam
     * targets a straddle image (identity otherwise), the visible representation v relates to
     * the native orientation o by v = M·o, and the axis the player means, expressed in the
     * visible frame, is R_E·a. Solving ΔQ(axis_constraint)·o = M⁻¹·ΔQ(R_E·a)·M·o gives
     * axis_constraint = M⁻¹·R_E·a — computed by {@link IplStaffBeamRoutes#axisRotation}
     * from the same route the beam draws. One frame source for goal, beam, aim, and axis.
     */
    public static Vec3 unmapStaffInputAxis(Player player, ClientSubLevel sub, Vec3 axis) {
        PhysicsStaffClientHandler.ClientDragSession session =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER.getDragSession();
        Vec3 localAnchor = session != null
            && session.dragSubLevel().getUniqueId().equals(sub.getUniqueId())
            ? new Vec3(session.dragLocalAnchor().x(), session.dragLocalAnchor().y(),
                session.dragLocalAnchor().z())
            : Vec3.ZERO;
        Quaterniond rotation = IplStaffBeamRoutes.axisRotation(player, sub, localAnchor);
        if (rotation == null) return axis;
        Vector3d mapped = rotation.transform(new Vector3d(axis.x, axis.y, axis.z));
        return new Vec3(mapped.x, mapped.y, mapped.z);
    }

    /**
     * Keep a local target's clicked representation too, not only portal-traversed ones. The
     * same geometric candidate resolution as the through-portal pick decides whether the
     * click landed on the native half or on a straddle image projected into this world —
     * for a same-dimension straddle both exist, and grabbing the emerged half must link the
     * image frame (its session portal's inverse), or the constraint would pull it back in.
     */
    public static void rememberLocalProjection(Player player, HitResult hit) {
        if (!(player.level() instanceof ClientLevel level) || !(hit instanceof BlockHitResult blockHit)) return;
        ClientSubLevel sub = getHitSubLevel(level, blockHit);
        if (sub == null) return;

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0f).scale(PhysicsStaffItem.RANGE));
        VisibleHit visible = visibleHit(level, eye, end, sub, blockHit);
        if (visible == null) {
            PENDING_TARGETS.remove(sub.getUniqueId());
            DRAG_TARGETS.remove(sub.getUniqueId());
            GRAB_DISTANCE.remove(sub.getUniqueId());
            return;
        }

        if (visible.imagePortal() == null
            && ipl.sable.dim.IplDimAgnostic.getParentLevel(sub) == level) {
            // Plain native grab in the body's own world: stock behavior, no capture.
            PENDING_TARGETS.remove(sub.getUniqueId());
            DRAG_TARGETS.remove(sub.getUniqueId());
            GRAB_DISTANCE.remove(sub.getUniqueId());
            return;
        }

        PENDING_TARGETS.clear();
        PENDING_TARGETS.put(sub.getUniqueId(), new PortalTarget(
            sub, level, blockHit, List.of(), visible.imagePortal(), visible.distance()
        ));
        GRAB_DISTANCE.put(sub.getUniqueId(), visible.distance());
    }

    @Nullable
    private static ClientSubLevel getHitSubLevel(net.minecraft.world.level.Level level, BlockHitResult hit) {
        // Every hosted plot lives in the dedicated sublevels container. `level` is the visible
        // source/destination world during portal recursion and has no plot ownership map; using
        // it here lost the clicked plot anchor and made the constraint fall back to ship center.
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
            IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return null;
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(
            net.minecraft.core.BlockPos.containing(hit.getLocation())
        );
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(chunk);
        dev.ryanhcode.sable.sublevel.SubLevel sub = plot != null ? plot.getSubLevel() : null;
        return sub instanceof ClientSubLevel clientSub ? clientSub : null;
    }

    /**
     * Pose of a captured remote target in the current staff render pass. Remove only the
     * portal suffix not traversed by this render pass, so every recursive pass receives its
     * target in its own world frame. (Held staff ITEM rendering only; beam geometry is
     * chain-driven in {@link IplStaffBeamRoutes}.)
     */
    public static Pose3d mapStaffRenderPose(ClientSubLevel sub, Pose3dc pose) {
        PortalTarget target = DRAG_TARGETS.get(sub.getUniqueId());
        if (target == null || target.sub() != sub
            || (target.portals().isEmpty() && target.imagePortal() == null)) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            net.minecraft.world.level.Level renderLevel = mc.level;
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            ipl.sable.transit.IplStraddlePoseMap.StraddleMapping mapping = renderLevel == null
                ? null
                : ipl.sable.transit.IplStraddlePoseMap.getMappingInto(sub, renderLevel);
            return mapping == null ? new Pose3d(pose) : mapping.mapPose(pose);
        }
        Pose3d mapped = new Pose3d(pose);
        if (target.portals().isEmpty()) {
            // Image grab: the visible representation in the image's world is the session
            // portal's forward map of the native pose.
            net.minecraft.world.level.Level renderLevel = net.minecraft.client.Minecraft.getInstance().level;
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                renderLevel = qouteall.imm_ptl.core.render.context_management.PortalRendering
                    .getRenderingPortal().getDestinationWorld();
            }
            Portal portal = target.imagePortal();
            if (renderLevel != null && portal.getDestDim() == renderLevel.dimension()) {
                Vec3 position = portal.transformPoint(new Vec3(
                    mapped.position().x(), mapped.position().y(), mapped.position().z()
                ));
                qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
                mapped.position().set(position.x, position.y, position.z);
                mapped.orientation().set(new org.joml.Quaterniond(
                    rotation.x, rotation.y, rotation.z, rotation.w
                ).mul(mapped.orientation()));
            }
            return mapped;
        }
        List<Portal> renderPath = getActiveRenderPath();
        int traversed = 0;
        while (traversed < renderPath.size() && traversed < target.portals().size()
            && renderPath.get(traversed) == target.portals().get(traversed)) {
            traversed++;
        }
        // Target pose starts in final destination frame. Undo only portal suffix that this
        // render pass has not traversed. Thus outer world gets its source-side endpoint, first
        // portal render gets its intermediate endpoint, and final recursive pass gets native
        // target coordinates.
        for (int i = target.portals().size() - 1; i >= traversed; i--) {
            Portal portal = target.portals().get(i);
            Vec3 position = portal.inverseTransformPoint(new Vec3(
                mapped.position().x(), mapped.position().y(), mapped.position().z()
            ));
            qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
            org.joml.Quaterniond inverse = new org.joml.Quaterniond(
                rotation.x, rotation.y, rotation.z, rotation.w
            ).invert();
            mapped.position().set(position.x, position.y, position.z);
            mapped.orientation().set(inverse.mul(mapped.orientation()));
        }
        return mapped;
    }

    /**
     * A captured pick target: the exact forward portal path the ray traversed, plus the
     * straddle-session portal whose IMAGE was clicked (null when the native half was).
     */
    public record PortalTarget(
        ClientSubLevel sub, net.minecraft.world.level.Level world, BlockHitResult hit,
        List<Portal> portals, @Nullable Portal imagePortal, double visibleDistance
    ) {}

    /** A projection hit: plot-coordinate BlockHitResult + ray distance² (frame-comparable). */
    public record ProjectionHit(
        BlockHitResult hit, double distSq,
        @Nullable ClientSubLevel sub, @Nullable Portal imagePortal
    ) {}

    /**
     * Clip the context's ray against every straddle projection into {@code level}, using
     * MAPPED poses. The local clip resolves plot blocks through the plot bridge; the
     * returned hit is in PLOT coordinates (Sable's sub-level hit convention). Distances
     * are frame-comparable because the mapping is rigid.
     *
     * <p>Re-entrant calls (our own projection clips go through {@code level.clip}) are
     * guarded by the caller.
     */
    @Nullable
    public static ProjectionHit clipProjections(ClientLevel level, ClipContext ctx) {
        java.util.List<IplClientHostedLookup.StraddleProjection> projections =
            IplClientHostedLookup.getStraddleProjectionsInto(level);
        if (projections.isEmpty()) return null;

        Vec3 from = ctx.getFrom();
        Vec3 to = ctx.getTo();
        ipl.sable.mixin.client.IplClipContextAccessor access =
            (ipl.sable.mixin.client.IplClipContextAccessor) ctx;

        BlockHitResult best = null;
        ClientSubLevel bestSub = null;
        Portal bestPortal = null;
        double bestDistSq = Double.MAX_VALUE;

        for (IplClientHostedLookup.StraddleProjection projection : projections) {
            Pose3dc mapped = projection.mappedPose();

            // Which half of the image physically exists is decided in PLOT SPACE by
            // the source-half keep filter — the same cut physics and interaction use.
            // (The old half-space ray gate against the infinite dest plane was
            // orientation-fragile: it both rejected legitimate rays aimed straight at
            // the emerged part and let near-portal rays alias onto source-half twin
            // blocks through the image frame.)
            java.util.function.Predicate<net.minecraft.core.BlockPos> keep =
                ipl.sable.transit.IplStraddlePoseMap.getSourceHalfKeepFilter(
                    projection.sub(), level);

            // Distance frame anchor: the ORIGINAL ray origin, not the clipped segment
            // start — distSq must stay comparable with the stock hit's measurement.
            Vector3d localStart = mapped.transformPositionInverse(
                new Vector3d(from.x, from.y, from.z));
            Vector3d segStart = localStart;
            Vector3d segStop = mapped.transformPositionInverse(
                new Vector3d(to.x, to.y, to.z));

            // The rays below are already in plot space — Sable's own sub-level
            // projection is suppressed per cast, leaving pure vanilla traversal whose
            // block reads resolve through the plot bridge.

            // Only the THROUGH half exists as this image: hits on source-half blocks
            // reached via the image frame are phantoms (the twin-block alias) — skip
            // past them along the ray (bounded), like the native drop pass re-casts.
            Vec3 rayFrom = new Vec3(segStart.x, segStart.y, segStart.z);
            Vec3 rayTo = new Vec3(segStop.x, segStop.y, segStop.z);
            Vec3 rayDir = rayTo.subtract(rayFrom).normalize();
            BlockHitResult hit = null;
            for (int guard = 0; guard < 4; guard++) {
                ClipContext stepCtx = new ClipContext(
                    rayFrom, rayTo,
                    access.ipl$getBlock(), access.ipl$getFluid(),
                    access.ipl$getCollisionContext());
                ((dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension) stepCtx)
                    .sable$setDoNotProject(true);
                BlockHitResult step = level.clip(stepCtx);
                if (step.getType() == HitResult.Type.MISS) {
                    hit = null;
                    break;
                }
                if (keep == null || !keep.test(step.getBlockPos())) {
                    hit = step;
                    break;
                }
                // Phantom: advance just past the hit and re-cast.
                rayFrom = step.getLocation().add(rayDir.scale(1.0e-3));
                hit = null;
            }
            if (hit == null) continue;

            double distSq = hit.getLocation().distanceToSqr(
                localStart.x, localStart.y, localStart.z);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = hit;
                bestSub = projection.sub();
                bestPortal = projection.portal();
            }
        }
        return best != null
            ? new ProjectionHit(best, bestDistSq, bestSub, bestPortal) : null;
    }
}
