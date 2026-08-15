package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.BitSet;
import java.util.List;
import java.util.UUID;

/**
 * Static transit primitives for Phase 1: atomic teleport of a {@link ServerSubLevel}
 * through an IP {@link Portal}.
 *
 * <p>"Atomic" here means: in a single server tick, the source sub-level is removed
 * from its dimension and a new sub-level with the same UUID is allocated in the
 * destination dimension at the portal-mapped pose. Phase 1 leaves the dest plot
 * empty (block copy is Commit 2). Riders are not yet teleported (Commit 3).
 *
 * <p>All operations here are pure — they read sub-level state, mutate Sable containers
 * via the documented API, and return. The orchestrator that decides <i>when</i> to
 * fire is {@link SableTransitController}.
 */
public final class SableTransitOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-transit");

    private SableTransitOps() {}

    /**
     * Execute an atomic transit. Returns true if the transit completed; false if it
     * was aborted (e.g., dest plot grid full, dest dim has no container, etc.).
     *
     * <p>Order of operations is deliberate:
     * <ol>
     *   <li>Resolve destination level + container. Bail if missing.</li>
     *   <li>Find a free plot in destination container. Bail if grid full.</li>
     *   <li>Compute the portal-mapped pose for the destination.</li>
     *   <li>Allocate destination sub-level (reuses source UUID).</li>
     *   <li>Remove source sub-level. Cascades through Sable's observers
     *       ({@code SubLevelTrackingSystem.onSubLevelRemoved} -> sendRemoval to
     *       all source-dim trackers; {@code SubLevelPhysicsSystem.onSubLevelRemoved}
     *       -> physics body removed).</li>
     * </ol>
     *
     * <p>Block copy and rider teleport are Commits 2 and 3 of Phase 1 respectively;
     * placeholders in this method just log that the steps would run.
     */
    public static boolean executeTransit(ServerSubLevel source, Portal portal) {
        MinecraftServer server = source.getLevel().getServer();
        if (server == null) {
            LOG.warn("[IPL-TRANSIT] aborted: source level has no server (sourceUuid={})", source.getUniqueId());
            return false;
        }

        ServerLevel destLevel = server.getLevel(portal.getDestDim());
        if (destLevel == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} not loaded (sourceUuid={})",
                portal.getDestDim().location(), source.getUniqueId());
            return false;
        }

        ServerSubLevelContainer destContainer = SubLevelContainer.getContainer(destLevel);
        if (destContainer == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} has no SubLevelContainer (sourceUuid={})",
                destLevel.dimension().location(), source.getUniqueId());
            return false;
        }

        int[] plotXZ = findFirstEmptyPlot(destContainer);
        if (plotXZ == null) {
            LOG.warn("[IPL-TRANSIT] aborted: destination dim {} plot grid full (sourceUuid={})",
                destLevel.dimension().location(), source.getUniqueId());
            return false;
        }

        Pose3d destPose = computeMappedPose(source.logicalPose(), portal);
        UUID uuid = source.getUniqueId();

        ServerSubLevelContainer sourceContainer = (ServerSubLevelContainer)
            SubLevelContainer.getContainer(source.getLevel());
        if (sourceContainer == null) {
            LOG.warn("[IPL-TRANSIT] aborted: source level has no SubLevelContainer (uuid={})", uuid);
            return false;
        }

        LOG.info("[IPL-TRANSIT] firing  uuid={}  {} -> {}  destPlot=({},{})  destPos=({},{},{})",
            uuid,
            source.getLevel().dimension().location(),
            destLevel.dimension().location(),
            plotXZ[0], plotXZ[1],
            destPose.position().x(), destPose.position().y(), destPose.position().z());

        // Capture source velocity BEFORE allocating dest (which would briefly leave
        // both sub-levels alive and could affect timing) and definitely before removing
        // source. Velocities are in world space.
        Vector3d sourceLinVel = new Vector3d(source.latestLinearVelocity);
        Vector3d sourceAngVel = new Vector3d(source.latestAngularVelocity);

        // 4. Allocate dest sub-level.
        ServerSubLevel dest;
        try {
            dest = (ServerSubLevel) destContainer.allocateSubLevel(uuid, plotXZ[0], plotXZ[1], destPose);
        } catch (Throwable t) {
            LOG.error("[IPL-TRANSIT] failed to allocate destination sub-level for uuid={}", uuid, t);
            return false;
        }

        // 5. Copy blocks plot -> plot. Sable's handleBlockChange cascade fires on
        //    each setBlockState, rebuilding mass tracker, heatmap, floating-block
        //    state, and Aero's per-block-position state (balloons etc.) on dest.
        int blocksCopied = copyPlotBlocks(source.getPlot(), dest.getPlot(),
            source.getLevel(), destLevel);
        LOG.info("[IPL-TRANSIT] copied {} blocks  uuid={}", blocksCopied, uuid);

        // 5b. Transfer velocity. Rotate source-dim world velocity vectors through the
        //     portal's rotation to get dest-dim world velocities, then apply via the
        //     dest physics pipeline. Without this, the airship arrives stationary,
        //     gravity pulls it back into the portal plane, and we re-transit -- causing
        //     the rapid oscillation that stresses the client renderer to the point of
        //     vanilla NPEs in SectionOcclusionGraph.
        applyMappedVelocity(dest, destContainer, sourceLinVel, sourceAngVel, portal);

        // 6. Teleport riders. Find entities standing on the airship (bbox-overlap in
        //    source dim) and send them to dest dim at portal-mapped positions. Note
        //    this runs *before* source removal so the rider's source-dim physics still
        //    sees solid blocks under them during the teleport handoff -- avoids a
        //    brief "falling through a vanished airship" frame.
        int ridersTeleported = teleportRiders(source, portal, destLevel);
        if (ridersTeleported > 0) {
            LOG.info("[IPL-TRANSIT] teleported {} rider(s)  uuid={}", ridersTeleported, uuid);
        }

        // 7. Remove source. This fires SubLevelObserver.onSubLevelRemoved synchronously;
        //    SubLevelTrackingSystem emits a StopTracking to all source-dim trackers.
        try {
            sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            // If source removal fails, we've already allocated dest -- that's a bad state.
            // Try to roll back dest. If that also fails, log loudly.
            LOG.error("[IPL-TRANSIT] failed to remove source for uuid={}; attempting dest rollback", uuid, t);
            try {
                destContainer.removeSubLevel(dest, SubLevelRemovalReason.REMOVED);
            } catch (Throwable t2) {
                LOG.error("[IPL-TRANSIT] rollback of dest also failed for uuid={}", uuid, t2);
            }
            return false;
        }

        LOG.info("[IPL-TRANSIT] complete  uuid={}", uuid);
        return true;
    }

    /**
     * Rebind any STALE physics-actor registrations on {@code sub} to the live block
     * entity currently in the chunk.
     *
     * <p><b>The bug this fixes (proven by the actor-diag {@code same=false}):</b> after a
     * transit, the dest chunk's block entities get RECREATED (a new object at the same pos)
     * by a chunk reprocess that does not re-fire {@code plot.onBlockChange}. Sable's actor
     * registry ({@code blockEntityActors}, a {@code pos -> BlockEntity} map) is populated at
     * copy time and keeps pointing at the OLD, now-orphaned block entity. The physics loop
     * iterates {@code getBlockEntityActors()} and ticks that stale object -- whose
     * {@code rotationSpeed} is frozen at 0 because it isn't the one in the world that ticks
     * -- so propellers read {@code active=false} and apply no thrust, even though the live
     * chunk BE is on a healthy kinetic network ({@code getSpeed()=40}). A never-transited
     * airship is unaffected (its actors were registered at assembly and never swapped),
     * which is why this only bit re-transited airships.
     *
     * <p>The fix: {@code plot.onBlockChange(pos, state)} re-reads {@code level.getBlockEntity}
     * and re-{@code put}s the live BE into the registry (idempotent). We only call it for
     * positions where the registered actor object differs from the live chunk BE, so for a
     * healthy airship this is a pure no-op (just identity comparisons) and is safe to run
     * every tick for every sub-level -- making it self-healing against any future swap
     * (e.g. a hold/unhold cycle when a player flies away and back), not just the transit.
     *
     * @return the number of stale actors rebound this call (0 = nothing was stale).
     */
    public static int resyncStaleActors(ServerSubLevel sub) {
        if (sub.isRemoved()) return 0;
        ServerLevelPlot plot = sub.getPlot();
        ServerLevel level = sub.getLevel();
        java.util.List<BlockPos> stale = null;
        for (var actor : plot.getBlockEntityActors()) {
            if (!(actor instanceof BlockEntity actorBe)) continue;
            BlockPos pos = actorBe.getBlockPos();
            BlockEntity chunkBe = level.getBlockEntity(pos);
            // Only treat as stale if the chunk currently holds a DIFFERENT (non-null) BE.
            // A transiently-null lookup (chunk momentarily unavailable) must NOT cause us
            // to evict a valid actor.
            if (chunkBe != null && chunkBe != actorBe) {
                if (stale == null) stale = new java.util.ArrayList<>(4);
                stale.add(pos);
            }
        }
        if (stale == null) return 0;
        for (BlockPos pos : stale) {
            try {
                plot.onBlockChange(pos, level.getBlockState(pos));
            } catch (Throwable t) {
                LOG.warn("[IPL-TRANSIT] failed to rebind stale actor at {} for {}",
                    pos, sub.getUniqueId(), t);
            }
        }
        return stale.size();
    }

    /**
     * Maps a sub-level's source-dim pose to the destination-dim pose via the portal's
     * transformation.
     *
     * <p>Position: {@code portal.transformPoint(sourcePos)}.
     * <p>Orientation: {@code portalRotation * sourceOrientation} (apply source first,
     * then portal). The rotationPoint and scale are passed through unchanged.
     */
    public static Pose3d computeMappedPose(Pose3d sourcePose, Portal portal) {
        // Position via the portal transform.
        Vec3 sourcePos = new Vec3(
            sourcePose.position().x(), sourcePose.position().y(), sourcePose.position().z()
        );
        Vec3 destPos = portal.transformPoint(sourcePos);

        // Orientation: compose portal rotation with source orientation.
        DQuaternion portalRot = portal.getRotationD();
        Quaterniond portalJoml = new Quaterniond(portalRot.x, portalRot.y, portalRot.z, portalRot.w);
        Quaterniond destOrient = new Quaterniond(portalJoml).mul(sourcePose.orientation());

        Pose3d destPose = new Pose3d();
        destPose.position().set(destPos.x, destPos.y, destPos.z);
        destPose.orientation().set(destOrient);
        // rotationPoint is typically the center of mass in plot-local coords;
        // shouldn't change when transiting dims. Copy through.
        destPose.rotationPoint().set(sourcePose.rotationPoint());
        // Scale is also pose-local; copy.
        destPose.scale().set(sourcePose.scale());
        return destPose;
    }

    /**
     * Rotate the source's world-space linear + angular velocity through the portal's
     * rotation and inject into the dest's physics body.
     *
     * <p>Portal rotation is a "world frame" rotation: a vector expressed in source-dim
     * world coordinates can be transformed to dest-dim world coordinates by applying
     * the portal's rotation quaternion. {@code portal.transformLocalVec} does exactly
     * this for direction vectors (no translation involved), which is what we want for
     * velocities.
     *
     * <p>The dest sub-level was just allocated and added to the pipeline at rest
     * (zero velocity), so {@code addLinearAndAngularVelocity} effectively *sets* the
     * velocity rather than incrementing it.
     */
    private static void applyMappedVelocity(
        ServerSubLevel dest,
        ServerSubLevelContainer destContainer,
        Vector3d sourceLinVel,
        Vector3d sourceAngVel,
        Portal portal
    ) {
        // Skip if essentially stationary (avoids float drift becoming kicks).
        if (sourceLinVel.lengthSquared() < 1e-8 && sourceAngVel.lengthSquared() < 1e-8) {
            return;
        }

        Vec3 lin = new Vec3(sourceLinVel.x, sourceLinVel.y, sourceLinVel.z);
        Vec3 ang = new Vec3(sourceAngVel.x, sourceAngVel.y, sourceAngVel.z);

        // transformLocalVec applies the portal's rotation+scale (no translation), which
        // is the correct treatment for a world-space velocity vector.
        Vec3 destLin = portal.transformLocalVec(lin);
        Vec3 destAng = portal.transformLocalVec(ang);

        PhysicsPipeline pipeline = destContainer.physicsSystem().getPipeline();
        pipeline.addLinearAndAngularVelocity(
            dest,
            new Vector3d(destLin.x, destLin.y, destLin.z),
            new Vector3d(destAng.x, destAng.y, destAng.z)
        );
    }

    /**
     * Find the first empty plot in a container by scanning occupancy bits. Returns
     * {x, z} local plot coordinates, or null if the grid is full.
     *
     * <p>Sable's container has its own {@code getFirstEmptyPlot} but it's private.
     * The pieces we need ({@code getOccupancy}, {@code getLogSideLength},
     * {@code getIndex}) are public, so we just replicate the algorithm.
     *
     * <p>Exposed package-private via {@link #findFirstEmptyPlotForMirror} for
     * hosted rehome operations to reuse.
     */
    static int @Nullable [] findFirstEmptyPlotForMirror(SubLevelContainer container) {
        return findFirstEmptyPlot(container);
    }

    private static int @Nullable [] findFirstEmptyPlot(SubLevelContainer container) {
        int sideLength = 1 << container.getLogSideLength();
        BitSet occupancy = container.getOccupancy();
        for (int x = 0; x < sideLength; x++) {
            for (int z = 0; z < sideLength; z++) {
                if (!occupancy.get(container.getIndex(x, z))) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Commit 2 / 3 stubs
    // -------------------------------------------------------------------------

    /**
     * Copy every non-air block (plus its block-entity NBT) from {@code src} plot to
     * {@code dst} plot. Returns the count of blocks copied.
     *
     * <p>Critically: each {@code chunk.setBlockState(...)} call cascades through
     * Sable's {@code SableCommonEvents.handleBlockChange} which:
     * <ul>
     *   <li>Updates the dest plot's mass tracker, heatmap, floating-block state.</li>
     *   <li>Fires Aero's {@code BalloonMap.updateNearbyBalloons} on each block change,
     *       rebuilding the dest-dim balloon (Aero hot-air-balloon controller) as the
     *       blocks land.</li>
     * </ul>
     * That cascade is the load-bearing piece: it's why we don't need to do any
     * Sable-specific or Aero-specific state copy — block placement triggers it.
     *
     * <p>Y-range handling: if the dest dimension has a smaller build height than the
     * source dim's blocks (e.g., overworld -> nether for an airship above Y=128),
     * out-of-range blocks are skipped with a warning. Phase 4 will reject the transit
     * entirely if too much of the airship would be clipped — for now, a partial copy
     * is better than a hard fail and matches what a player would expect from a
     * "low ceiling" portal interaction.
     */
    /** Exposed package-private wrapper for hosted rehome block-copy operations. */
    static int copyPlotBlocksPublic(
        ServerLevelPlot src, ServerLevelPlot dst,
        ServerLevel srcLevel, ServerLevel dstLevel
    ) {
        return copyPlotBlocks(src, dst, srcLevel, dstLevel);
    }

    private static int copyPlotBlocks(
        ServerLevelPlot src, ServerLevelPlot dst,
        ServerLevel srcLevel, ServerLevel dstLevel
    ) {
        int blocksCopied = 0;
        int blocksSkippedOutOfYRange = 0;

        int dstMinY = dstLevel.getMinBuildHeight();
        int dstMaxY = dstLevel.getMaxBuildHeight();

        // Accumulate every placed block so we can run a SECOND notify pass after
        // all placement, exactly as Sable's own SubLevelAssemblyHelper.moveBlocks
        // does. The placement above uses setBlockState(isMoving=true), which skips
        // neighbour updates; Create relies on those updates to (re)connect kinetic
        // networks (wheels, propellers). Placing without notifying leaves the
        // copied actors registered-but-disconnected -> they tick but read zero
        // kinetic speed -> no thrust. Doing notify in a second pass (not inline)
        // matches moveBlocks and ensures all blocks exist before neighbours fire.
        record Placed(BlockPos pos, LevelChunk chunk, BlockState state) {}
        java.util.List<Placed> placed = new java.util.ArrayList<>();

        // Suppress onPlace during the bulk placement, exactly as
        // SubLevelAssemblyHelper.moveBlocks does via setIgnoreOnPlace
        // (NeoForge: Level.captureBlockSnapshots = true gates the onPlace /
        // BE / neighbour side-effects in LevelChunk.setBlockState).
        //
        // WHY THIS IS LOAD-BEARING for Create kinetics: without it, every
        // setBlockState fires onPlace on a HALF-BUILT structure, and BEFORE
        // loadWithComponents restores the block entity's kinetic NBT. Create's
        // KineticBlockEntity then attaches to a partial/empty rotation network
        // (some neighbours not placed yet) and caches that broken state; the
        // later markAndNotifyBlock neighbour-notify pass does NOT repair it, so
        // getSpeed() stays 0 -> BasePropellerBlockEntity.rotationSpeed decays to
        // 0 -> isActive() false -> no thrust. Suppressing onPlace here, then
        // notifying once the whole structure exists (second pass below), lets
        // each KineticBlockEntity rebuild its network cleanly on first tick --
        // the same clean path a world reload takes (which is exactly why a
        // reload fixes dead propellers/wheels). This mirrors moveBlocks: both
        // working paths (assembly + load) avoid premature onPlace; the old copy
        // was the only path that fired it.
        dev.ryanhcode.sable.platform.SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(dstLevel, true);
        try {
        for (PlotChunkHolder srcHolder : src.getLoadedChunks()) {
            LevelChunk srcChunk = srcHolder.getChunk();
            ChunkPos srcChunkPos = srcChunk.getPos();

            // The block layout is plot-local. We want the same local layout in dst,
            // so translate the chunk position through plot coordinate spaces.
            ChunkPos localChunkPos = src.toLocal(srcChunkPos);
            ChunkPos dstChunkPos = dst.toGlobal(localChunkPos);

            // Ensure the dst plot has a chunk allocated at the corresponding position.
            if (dst.getChunkHolder(localChunkPos) == null) {
                dst.newEmptyChunk(dstChunkPos);
            }
            LevelChunk dstChunk = dst.getChunk(localChunkPos);
            if (dstChunk == null) {
                LOG.warn("[IPL-TRANSIT] dst chunk alloc failed at local {}", localChunkPos);
                continue;
            }

            // The block X/Z within a chunk is independent of which global chunk it
            // is. So we can iterate by section and local (lx, ly, lz) and add the
            // src/dst chunk's min-block offset for each side.
            LevelChunkSection[] sections = srcChunk.getSections();
            int sectionsCount = srcChunk.getSectionsCount();
            for (int sectionIdx = 0; sectionIdx < sectionsCount; sectionIdx++) {
                LevelChunkSection section = sections[sectionIdx];
                if (section == null || section.hasOnlyAir()) continue;

                int sectionY = srcChunk.getSectionYFromSectionIndex(sectionIdx);
                int sectionBaseY = sectionY << 4;

                for (int ly = 0; ly < 16; ly++) {
                    int worldY = sectionBaseY + ly;
                    if (worldY < dstMinY || worldY >= dstMaxY) {
                        // Skip blocks outside dest dim's vertical range. Count for log.
                        for (int lx = 0; lx < 16; lx++) {
                            for (int lz = 0; lz < 16; lz++) {
                                if (!section.getBlockState(lx, ly, lz).isAir()) {
                                    blocksSkippedOutOfYRange++;
                                }
                            }
                        }
                        continue;
                    }
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            BlockState state = section.getBlockState(lx, ly, lz);
                            if (state.isAir()) continue;

                            BlockPos srcWorldPos = new BlockPos(
                                srcChunkPos.getMinBlockX() + lx,
                                worldY,
                                srcChunkPos.getMinBlockZ() + lz
                            );
                            BlockPos dstWorldPos = new BlockPos(
                                dstChunkPos.getMinBlockX() + lx,
                                worldY,
                                dstChunkPos.getMinBlockZ() + lz
                            );

                            // Save BE NBT before setting state (which can destroy
                            // the source BE during the cascade).
                            BlockEntity srcBE = srcChunk.getBlockEntity(srcWorldPos);
                            CompoundTag beTag = null;
                            if (srcBE != null) {
                                beTag = srcBE.saveWithFullMetadata(srcLevel.registryAccess());
                                beTag.putInt("x", dstWorldPos.getX());
                                beTag.putInt("y", dstWorldPos.getY());
                                beTag.putInt("z", dstWorldPos.getZ());
                            }

                            // setBlockState(pos, state, isMoving) -- isMoving=true tells
                            // vanilla "skip neighbour-update side effects." Matches
                            // SubLevelAssemblyHelper.moveBlocks convention. The skipped
                            // neighbour updates are issued in the second pass below.
                            dstChunk.setBlockState(dstWorldPos, state, true);
                            blocksCopied++;
                            placed.add(new Placed(dstWorldPos, dstChunk, state));

                            // Restore BE state on dest, if any.
                            //
                            // NOTE: Sable's LevelChunkMixin already fires
                            // plot.onBlockChange on the setBlockState RETURN above
                            // (registering actors / lift-providers / reaction wheels),
                            // so we do NOT call onBlockChange ourselves here.
                            if (beTag != null) {
                                BlockEntity dstBE = dstChunk.getBlockEntity(dstWorldPos);
                                if (dstBE != null) {
                                    dstBE.loadWithComponents(beTag, dstLevel.registryAccess());
                                }
                            }
                        }
                    }
                }
            }
        }
        } finally {
            // Always re-enable onPlace, even if a setBlockState above threw.
            // Leaving captureBlockSnapshots=true on the dest level would
            // silently swallow onPlace/neighbour updates for ALL future block
            // changes in that dimension -- a nasty latent corruption. moveBlocks
            // relies on per-block try/catch for this; we use try/finally so the
            // flag is guaranteed cleared. The markAndNotifyBlock pass below
            // intentionally runs with onPlace RE-ENABLED and the structure whole.
            dev.ryanhcode.sable.platform.SableAssemblyPlatform.INSTANCE.setIgnoreOnPlace(dstLevel, false);
        }

        // SECOND PASS: notify neighbours for every placed block, mirroring
        // SubLevelAssemblyHelper.moveBlocks' second loop. This issues the
        // sendBlockUpdated / blockUpdated / updateNeighbourShapes cascade that the
        // isMoving=true placement skipped -- the cascade Create uses to (re)connect
        // kinetic networks. Without it, copied wheels/propellers tick but read zero
        // kinetic speed (no thrust), intermittently depending on whether some other
        // incidental block update happened to trigger Create's rescan. Done as a
        // separate pass so ALL blocks exist before any neighbour update fires
        // (a block updating mid-copy would see half-placed neighbours).
        // Flags 3 (BLOCK_UPDATE | NOTIFY_NEIGHBORS) + recursionLeft 512 match
        // moveBlocks exactly.
        for (Placed p : placed) {
            try {
                dev.ryanhcode.sable.api.SubLevelAssemblyHelper.markAndNotifyBlock(
                    dstLevel, p.pos(), p.chunk(),
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    p.state(), 3, 512
                );
            } catch (Throwable t) {
                LOG.warn("[IPL-TRANSIT] markAndNotifyBlock failed at {} -- kinetic reconnect "
                    + "may be incomplete for this block", p.pos(), t);
            }
        }

        // THIRD PASS: register block-entity tickers + start ticking each dest chunk,
        // exactly as the world-LOAD path does (ServerLevelPlot.load lines 551-552:
        // chunk.registerAllBlockEntitiesAfterLevelLoad(); level.startTickingChunk(chunk)).
        //
        // WHY THIS IS THE ACTUAL FIX (proven by the T+25 actor-diag): after a transit the
        // copied propeller has getSpeed()=40, hasNetwork=true, hasSource=true -- the Create
        // kinetic network IS reconnected (RotationPropagator wires it up during placement,
        // without needing the BE's own tick). But the propeller reports active=false /
        // thrust=0 because its rotationSpeed field, updated ONLY inside
        // BasePropellerBlockEntity.tick() via updateRotationSpeed(), never advances --
        // i.e. the block entity's tick() is not running on the dest. The chunk was created
        // empty (newEmptyChunk -> addChunkHolder calls registerAllBlockEntitiesAfterLevelLoad
        // on a chunk with NO block entities yet), and the BEs we then setBlockState in never
        // got entered into the level's block-entity tick loop. A world reload fixes it
        // precisely because load re-runs registerAllBlockEntitiesAfterLevelLoad AFTER the
        // BEs exist, then startTickingChunk. We replicate that here. Idempotent:
        // registerAllBlockEntitiesAfterLevelLoad rebinds tickers via tickersInLevel.compute
        // keyed by pos, so re-running it over already-present BEs is safe.
        java.util.Set<LevelChunk> tickChunks =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Placed p : placed) tickChunks.add(p.chunk());
        for (LevelChunk ch : tickChunks) {
            try {
                ch.registerAllBlockEntitiesAfterLevelLoad();
                dstLevel.startTickingChunk(ch);
            } catch (Throwable t) {
                LOG.warn("[IPL-TRANSIT] failed to (re)register block-entity tickers for dest "
                    + "chunk {} -- copied machinery may not tick", ch.getPos(), t);
            }
        }

        if (blocksSkippedOutOfYRange > 0) {
            LOG.warn(
                "[IPL-TRANSIT] {} blocks skipped because they fell outside dest dim {} Y range [{}, {})",
                blocksSkippedOutOfYRange,
                dstLevel.dimension().location(),
                dstMinY, dstMaxY
            );
        }

        return blocksCopied;
    }

    /**
     * Teleport entities standing on the airship to the dest dim at portal-mapped
     * positions. Returns the number successfully teleported.
     *
     * <p>Detection: any entity whose world-AABB overlaps the airship's world-AABB
     * (inflated by a small amount to catch entities standing on the top edge of the
     * deck). Iterates source dim entities only.
     *
     * <p>Excluded:
     * <ul>
     *   <li>{@link Portal} entities (we don't want to teleport portals).</li>
     *   <li>Passengers (entities with a non-null vehicle). Per Phase 0 decision,
     *       mounted-seat riders are deferred -- they'll end up dismounted in source
     *       dim when the source sub-level is removed below. Phase 2 or 3 will add
     *       proper seat-correspondence handling.</li>
     *   <li>Already-removed entities.</li>
     * </ul>
     *
     * <p>Position mapping uses {@link Portal#transformPoint}. Velocity is mapped via
     * {@link Portal#transformLocalVec} (rotation + scale, no translation -- correct
     * for direction vectors) and applied post-teleport via {@code setDeltaMovement}.
     *
     * <p>Order: called BEFORE source sub-level removal so the rider's source-dim
     * physics still sees solid blocks under them during the teleport handoff. The
     * destination already has copied blocks at this point so the rider lands on a
     * solid airship in dest dim.
     */
    private static int teleportRiders(ServerSubLevel source, Portal portal, ServerLevel destLevel) {
        AABB airshipBbox = source.boundingBox().toMojang();
        // Inflate slightly to catch entities standing on the top edge of the deck.
        AABB queryBbox = airshipBbox.inflate(0.5);

        ServerLevel sourceLevel = source.getLevel();
        List<Entity> entities = sourceLevel.getEntitiesOfClass(Entity.class, queryBbox);

        int teleported = 0;
        for (Entity entity : entities) {
            if (entity instanceof Portal) continue;
            if (entity.isRemoved()) continue;
            if (entity.getVehicle() != null) continue;

            Vec3 mappedPos = portal.transformPoint(entity.position());
            Vec3 mappedDelta = portal.transformLocalVec(entity.getDeltaMovement());

            try {
                Entity result = ServerTeleportationManager.teleportEntityGeneral(
                    entity, mappedPos, destLevel
                );
                // teleportEntityGeneral creates a NEW entity in the dest dim for
                // non-players (changeEntityDimension copies + removes), so apply the
                // mapped velocity to the returned entity, not the original.
                if (result != null && !result.isRemoved()) {
                    result.setDeltaMovement(mappedDelta);
                }
                teleported++;
            } catch (Throwable t) {
                LOG.warn(
                    "[IPL-TRANSIT] failed to teleport entity {} during transit of {}",
                    entity, source.getUniqueId(), t
                );
            }
        }
        return teleported;
    }
}
