package ipl.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public-facing soft dependency on Sable for the IP-Sable compatibility integration.
 *
 * <p>None of the methods on this class reference Sable types in their bytecode -- those
 * references live inside {@link SableImpl}, which the JVM only loads when its methods are
 * first invoked. We guard every dispatch on {@link #PRESENT} so that {@code SableImpl} is
 * never reached if Sable isn't on the classpath at runtime.
 *
 * <p>This means IP can be packaged and shipped without Sable as a required dep -- if Sable
 * isn't loaded, every method here is a no-op equivalent (returns {@code null} or an empty
 * fallback), and IP behaves identically to upstream.
 */
public final class SableBridge {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-bridge");

    /**
     * {@code true} iff Sable is loadable at the time this class was initialized.
     */
    public static final boolean PRESENT;

    static {
        boolean p;
        try {
            Class.forName("dev.ryanhcode.sable.Sable");
            p = true;
        } catch (Throwable t) {
            p = false;
        }
        PRESENT = p;
        if (PRESENT) {
            LOG.info("[IPL-SABLE] Sable detected on classpath; sub-level-aware integration active.");
            LOG.info("[IPL-SABLE] ======== FIX BUILD: ROUND 5 (2026-07-24) ========");
        } else {
            LOG.info("[IPL-SABLE] Sable not present; IP runs in upstream-equivalent mode.");
        }
    }

    private SableBridge() {}

    /**
     * Walks the sub-levels in {@code world} that intersect {@code worldPos} and returns the
     * first non-air block found at the corresponding plot-local coordinate, mirroring Sable's
     * own source-side pattern in
     * {@code dev.ryanhcode.sable.mixin.entity.entity_sublevel_collision.EntityMixin#getInBlockState}.
     *
     * <p>Used by IP's cross-portal block-state lookup so that an entity standing under an
     * upward-facing portal whose destination is inside an airship sees the airship's deck
     * block instead of the air at the visible coordinate.
     *
     * @param world    the destination world to consult
     * @param worldPos the world-space position to test (e.g. {@code portal.transformPoint(entity.position())})
     * @return the non-air {@link BlockState} from an intersecting sub-level at {@code worldPos},
     *         or {@code null} if Sable is absent or no sub-level contributes a non-air block.
     */
    @Nullable
    public static BlockState lookupNonAirSubLevelBlockAt(Level world, Vec3 worldPos) {
        if (!PRESENT) return null;
        return SableImpl.lookupNonAirSubLevelBlockAt(world, worldPos);
    }

    /**
     * Tests whether {@code chunkPos} is inside the sub-level plot grid for {@code world}.
     *
     * <p>Sable allocates sub-levels far out in chunk coordinates (default origin around
     * {@code (10000, 10000)} chunks, ~160k blocks out). A chunk whose coordinates fall
     * inside that plot grid contains a sub-level's internal block storage; a chunk outside
     * is a normal world chunk.
     *
     * <p>Used by IP's {@code ChunkMap.getPlayers} replacement to route plot chunks to
     * Sable's sub-level-tracker lookup and leave normal chunks to IP's portal-aware
     * chunk tracking.
     *
     * @return {@code true} if the chunk is in Sable's plot grid; {@code false} if Sable is
     *         absent, the level has no plot container, or the chunk is in normal world space.
     */
    public static boolean isPlotChunk(Level world, ChunkPos chunkPos) {
        if (!PRESENT) return false;
        return SableImpl.isPlotChunk(world, chunkPos);
    }

    /**
     * Computes squared distance from {@code playerPos} to {@code (x, y, z)} accounting for
     * sub-level offsets, mirroring Sable's own {@code ActiveSableCompanion.distanceSquaredWithSubLevels}
     * semantics: if the player is on a sub-level or {@code (x, y, z)} is at a plot coordinate
     * that has a corresponding visible world coordinate, the visible position is used in the
     * distance calculation.
     *
     * <p>With Sable absent, falls back to plain Euclidean squared distance from
     * {@code playerPos} to {@code (x, y, z)} -- the vanilla broadcast distance metric.
     *
     * <p>Used by IP's {@code PlayerList.broadcast} replacement to deliver position-radius
     * packets (e.g. sound events) to players standing on airships, whose visible position is
     * far from the plot coordinate where the sound originated but whose perceived distance
     * should be computed against the visible airship location.
     */
    public static double distanceSquaredWithSubLevels(
        Level level, Vec3 playerPos, double x, double y, double z
    ) {
        if (!PRESENT) {
            double dx = playerPos.x - x;
            double dy = playerPos.y - y;
            double dz = playerPos.z - z;
            return dx * dx + dy * dy + dz * dz;
        }
        return SableImpl.distanceSquaredWithSubLevels(level, playerPos, x, y, z);
    }

    /**
     * Does this entity have a sub-level floor beneath it <em>this collision tick</em>?
     *
     * <p>Reads Sable's per-tick {@code CollisionInfo}: true iff the entity is
     * tracking a sub-level ({@code trackingSubLevel != null}) AND that tracking
     * came from a downward collision this tick ({@code verticalCollisionBelow}).
     * Sable only sets {@code trackingSubLevel} when {@code verticalCollisionBelow}
     * is true, but {@code trackingSubLevel} also persists across ticks as a field;
     * requiring {@code verticalCollisionBelow} pins this to "standing on a ship
     * floor right now" rather than a stale carry-over.
     *
     * <p>Used by IP's {@code Entity.collide} wrap to decide whether to fold Sable's
     * sub-level collision back in when IP would otherwise take its portal-only
     * branch -- the fix for "fall through an airship deck the moment it clips the
     * portal frame." Returns {@code false} when Sable is absent (no-op, upstream IP
     * behavior preserved).
     */
    public static boolean hasSubLevelFloorThisTick(Entity entity) {
        if (!PRESENT) return false;
        return SableImpl.hasSubLevelFloorThisTick(entity);
    }

    /**
     * Is the sub-level floor under this entity currently STRADDLING a portal?
     *
     * <p>Used by IP's {@code Entity.collide} wrap: while riding a straddling ship inside the
     * portal-collision zone, Sable's collision is both-frame aware (mapped poses + cross-seam
     * Atlas chart terrain), so IP's two-sided portal collision must NOT be folded in — its
     * other-side pass collides the rider's transformed box against dest-world geometry beyond
     * the plane (e.g. the ground the source portal stands on), producing phantom floors
     * (reduced apparent gravity) and capped jumps. Returns {@code false} when Sable is absent.
     */
    public static boolean isFloorSubLevelStraddlingPortal(Entity entity) {
        if (!PRESENT) return false;
        return SableImpl.isFloorSubLevelStraddlingPortal(entity);
    }

    /**
     * Distance² that understands sub-level frames: positions inside a plot project to
     * world space through the owning sub-level's pose (portal-mapped for foreign
     * straddlers via the projection keystone wrap). Falls back to raw distance² without
     * Sable. Used by IP's cross-portal interaction targeting/validation, where a raw
     * measurement against plot coordinates (~20M) rejects every ship interaction.
     */
    public static double frameAwareDistanceSqr(
        net.minecraft.world.level.Level level,
        net.minecraft.world.phys.Vec3 a,
        net.minecraft.world.phys.Vec3 b
    ) {
        if (!PRESENT) return a.distanceToSqr(b);
        return SableImpl.frameAwareDistanceSqr(level, a, b);
    }

    /**
     * The dimension of the sub-level that {@code vehicle} belongs to, or
     * {@code null} if the vehicle isn't on a sub-level (or Sable is absent).
     *
     * <p>Used to stamp the IP dimension field on the {@code ClientboundPlayerPositionPacket}
     * that Sable sends when a player mounts a seat on a sub-level
     * ({@code ServerPlayerMixin.sable$adjustTeleportPacket}). That packet's
     * coordinates are sub-level-LOCAL ({@code logicalPose().transformPositionInverse}),
     * so the dimension IP resolves them against must be the dimension the
     * <em>sub-level</em> lives in -- not the player's current dimension. Today those
     * coincide (you can only mount a same-dim seat), but anchoring on the sub-level
     * is correct by construction for the future "click/stay-seated across a portal"
     * cases where the seat's sub-level is in the destination dim while the player is
     * (still) source-side.
     */
    @Nullable
    public static ResourceKey<Level> subLevelDimensionOfVehicle(Entity vehicle) {
        if (!PRESENT) return null;
        return SableImpl.subLevelDimensionOfVehicle(vehicle);
    }

    /**
     * Sentinel returned by {@link #effectiveTrackingChunkPos} when there is no
     * remap to apply (Sable absent, or the entity isn't on a sub-level). Callers
     * fall back to the entity's raw {@code chunkPosition()} on this value.
     * {@code Long.MIN_VALUE} can't collide with a real packed {@link net.minecraft.world.level.ChunkPos}
     * long (real chunk coords are 32-bit halves; MIN_VALUE would require x=z=
     * {@code Integer.MIN_VALUE}, ~34 billion blocks out -- unreachable).
     */
    public static final long NO_REMAP = Long.MIN_VALUE;

    /**
     * <b>The designated unification seam between Sable's and IP's "track distant
     * chunks" solutions.</b>
     *
     * <p>Both mods solve the same underlying problem -- an entity's
     * tracking-relevant position is not its literal coordinate -- from opposite
     * ends. <b>Sable transforms the entity</b>: a sub-level entity sits ~20M blocks
     * out in the plot grid but is visibly on an airship next to you, so Sable
     * remaps its position ({@code logicalPose().transformPosition}) and lets stock
     * distance logic run. <b>IP transforms the watcher set</b>: a chunk seen
     * through a portal keeps its coordinates but gains extra portal-aware watchers.
     * These are orthogonal axes of one abstraction, and the seat-on-a-sub-level
     * case is exactly where they must compose -- IP's portal-aware visibility must
     * be evaluated <em>at Sable's remapped position</em>.
     *
     * <p>They don't compose today because IP replaces vanilla's tracking loop
     * wholesale ({@code MixinChunkMap_E} cancels {@code ChunkMap.tick}; the loop
     * moves into {@code EntitySync}), which silently strands Sable's vanilla-callsite
     * remap ({@code entity_tracking.TrackedEntityMixin} +
     * {@code server_entities_tick.ChunkMapMixin}, both {@code @WrapOperation}s on
     * callsites that no longer execute). So IP's tracking reads the raw ~20M-block
     * chunk, finds no watchers, and never tracks the entity -- e.g. a player sitting
     * on a Create seat on an airship never receives the {@code SetPassengers} packet.
     *
     * <p>This method is the single point IP's tracking gates consult for an entity's
     * effective tracking chunk. It returns the visible-position chunk (packed
     * {@link net.minecraft.world.level.ChunkPos} long) for sub-level entities, or
     * {@link #NO_REMAP} otherwise (caller uses the raw chunk). Routing all of IP's
     * tracking-position reads through this one function keeps the remap consistent
     * (the danger of scattered redirects is remapping some reads but not the
     * distance math that depends on them).
     *
     * <p><b>Eventual convergence:</b> when Sable's and IP's tracking systems are
     * unified, this becomes the integration contract -- ideally promoted to a real
     * {@code getEffectiveTrackingPosition()} that vanilla answers with
     * {@code position()}, Sable overrides, and IP consumes. For now it teaches IP's
     * replacement the one fact Sable knows that IP doesn't, without merging the two
     * sources of truth (that merge is the actual rewrite). No-op when Sable is
     * absent -- IP behaves exactly as upstream.
     */
    public static long effectiveTrackingChunkPos(Entity entity) {
        if (!PRESENT || entity == null) return NO_REMAP;
        return SableImpl.effectiveTrackingChunkPos(entity);
    }

    // ------------------------------------------------------------------
    // Ship-borne nether portals: IP's generation pipeline consults these so a
    // frame built ON a sub-level (blocks in plot space) generates a working,
    // ship-anchored portal. All are exact no-ops for non-plot inputs.

    /**
     * The level portal GENERATION should run in for a fire lit at {@code pos}.
     * A plot-space position (frame on a sub-level) resolves to the owning ship's
     * PARENT level — the dimension the ship visibly sails in — so the
     * overworld↔nether dimension gates and the {@code from} dimension stamped on
     * the resulting portals are the gameplay ones. The plot bridge makes the
     * plot-space block reads (shape finding, integrity checks, placeholder
     * fills) work from that parent level unchanged. Non-plot positions (or
     * Sable absent) return {@code world} as-is.
     */
    public static net.minecraft.server.level.ServerLevel effectivePortalGenLevel(
        net.minecraft.server.level.ServerLevel world, BlockPos pos
    ) {
        if (!PRESENT || !isPlotPos(world, pos)) return world;
        return SableImpl.effectivePortalGenLevel(world, pos);
    }

    /**
     * Chunk-presence test that understands plot coordinates: vanilla
     * {@code hasChunkAt} for world positions; "does a live sub-level own this
     * plot" for plot positions (the parent level's chunk map doesn't hold plot
     * chunks — the hosting dimension does).
     */
    public static boolean hasChunkAtFrameAware(
        net.minecraft.server.level.ServerLevel world, BlockPos pos
    ) {
        if (!PRESENT || !isPlotPos(world, pos)) return world.hasChunkAt(pos);
        return SableImpl.subLevelAtPlotPos(world, pos) != null;
    }

    /**
     * Where a plot-space frame center sits in the WORLD (through the owning
     * ship's pose), for mapping the destination side of a generated portal —
     * without this, a frame at plot coords ~20.4M maps to nether coords ~2.5M.
     * Null when {@code pos} isn't plot-space or no ship owns the plot.
     */
    @Nullable
    public static Vec3 shipFrameWorldCenter(
        net.minecraft.server.level.ServerLevel world, BlockPos pos
    ) {
        if (!PRESENT || !isPlotPos(world, pos)) return null;
        return SableImpl.shipFrameWorldCenter(world, pos);
    }

    /**
     * Re-pose a template portal whose origin was initialized from a PLOT-space
     * shape: position and orientation map through the owning ship's pose into
     * the parent world, and the portal's transform rotation is re-derived so
     * the (static) destination frame stays fixed — the same dest-lock math the
     * ship-portal anchor uses per tick. No-op for world-space portals.
     * Must run on the template BEFORE flipped/reverse portals are derived.
     */
    public static void mapShipFramePortalPose(qouteall.imm_ptl.core.portal.Portal portal) {
        if (!PRESENT || portal == null || !(portal.level() instanceof net.minecraft.server.level.ServerLevel level)
            || !isPlotPos(level, BlockPos.containing(portal.getOriginPos()))) {
            return;
        }
        SableImpl.mapShipFramePortalPose(portal);
    }

    /**
     * Anchor a freshly spawned breakable-portal cluster to the ship that owns
     * its plot-space frame ({@code shapeAnchor} = the from-shape's anchor pos).
     * Call on the cluster PRIMARY only — the anchor tick driver rectifies the
     * other members. No-op for world-space shapes.
     */
    public static void anchorShipFramePortal(
        qouteall.imm_ptl.core.portal.Portal portal, BlockPos shapeAnchor
    ) {
        if (!PRESENT || portal == null || shapeAnchor == null
            || !(portal.level() instanceof net.minecraft.server.level.ServerLevel level)
            || !isPlotPos(level, shapeAnchor)) {
            return;
        }
        SableImpl.anchorShipFramePortal(portal, shapeAnchor);
    }

    /**
     * The level that AUTHORITATIVELY stores the block at {@code pos}: the plot-hosting
     * dimension for plot-space positions, {@code context} otherwise. Breakable-portal
     * frame checks and placeholder writes go through this so a ship-borne portal's
     * plot-space shape is read/written where the chunks actually live, independent of
     * how much of the parent-level plot routing a given code path enjoys.
     */
    public static Level plotAwareLevel(Level context, @Nullable BlockPos pos) {
        if (!PRESENT || pos == null || !(context instanceof net.minecraft.server.level.ServerLevel serverLevel)
            || !isPlotPos(serverLevel, pos)) return context;
        Level hosting = SableImpl.hostingLevelOf(serverLevel);
        return hosting != null ? hosting : context;
    }

    /** Plot identity belongs to the live hosting container, never a coordinate threshold. */
    private static boolean isPlotPos(net.minecraft.server.level.ServerLevel context, BlockPos pos) {
        var container = ipl.sable.dim.IplDimAgnostic.getHostingContainerFor(context);
        if (container == null) return false;
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return container.inBounds(chunkX, chunkZ) && container.getPlot(chunkX, chunkZ) != null;
    }
}
