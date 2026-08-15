package ipl.sable.transit;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dim-agnostic phase 2 (REFACTOR_SPEC.md section 19.3 Option B): rehome every
 * {@link ServerSubLevel} from its parent dimension into the dedicated hosting dimension
 * {@code ipl_sable:sublevels}, and (phase 6) flip {@code parentLevel} on portal crossings
 * instead of copying plot blocks across dimensions.
 *
 * <p><b>Rehoming (migrate-at-assembly):</b> Sable's assembly/load paths are left completely
 * untouched — a sub-level is born embedded in its parent dim exactly as stock Sable creates it
 * (this is what fixed the v1 {@code assembleBlocks} blocker). One container-tick later, the
 * sweep notices an un-hosted sub-level and atomically: allocates a same-UUID sub-level in the
 * hosting container at an identical pose, copies plot blocks + block entities with the proven
 * {@link SableTransitOps#copyPlotBlocksPublic 3-pass copy}, relocates plot-resident entities
 * (item frames, seats), transfers physics velocity verbatim, stamps {@code parentLevel}, and
 * removes the parent-dim original. World coordinates never change, so riders standing on deck
 * and the visual pose are unaffected.
 *
 * <p><b>Parent restore:</b> hosted sub-levels persist their parent dim in the sub-level
 * {@code user_data} NBT ({@value #PARENT_DIM_KEY}). On world load, the duck field starts
 * out pointing at the hosting level; the sweep restores it from NBT (idempotent).
 *
 * <p><b>Hosted transit (parent flip):</b> when a hosted airship's OBB fully crosses a portal
 * in its parent dim, transit is just: remap pose through the portal, flip {@code parentLevel},
 * teleport riders, force re-track. No block copying, no container move — the unifying move.
 */
public final class SableRehomeOps {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable-rehome");

    /** user_data key persisting the parent dimension id of a hosted sub-level. */
    public static final String PARENT_DIM_KEY = "ipl_parent_dim";

    private static volatile boolean loggedEnabled = false;

    private SableRehomeOps() {}

    /**
     * Per-container-tick driver, called from {@code SableSubLevelTransitMixin} right before
     * the transit controller. Cheap no-op for containers with nothing to do.
     *
     * <ul>
     *   <li>On the hosting container: restore {@code parentLevel} from user data for any
     *       freshly-deserialized sub-level.</li>
     *   <li>On every other container: rehome at most one un-hosted sub-level per tick
     *       (bounds the copy cost; assemblies are rare).</li>
     * </ul>
     */
    public static void sweep(ServerSubLevelContainer container) {
        if (!(container.getLevel() instanceof ServerLevel level)) return;

        if (IplDimAgnostic.isHostingLevel(level)) {
            bootRestoreHosted(container, level);
            restoreParents(container, level);
            // Low-frequency client parent re-stamp: the one-shot setParent RPC can race
            // the client's StartTracking allocation; a ship whose client parent stays
            // null is filtered out of the hosted render gather — it EXISTS (physics,
            // collision) but is invisible. The client retries pending stamps per tick;
            // this sweep guarantees a fresh stamp eventually reaches every tracker even
            // if the original RPC was dropped or expired.
            restampClientParents(container, level);
            return;
        }

        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(level.getServer());
        if (hosting == null) return; // dim not loaded; phase-0 kill-switch already warned

        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel airship)) continue;
            if (airship.isRemoved()) continue;

            try {
                rehome(airship, container, level, hosting);
            } catch (Throwable t) {
                LOG.error("[IPL-REHOME] failed to rehome uuid={} from {}; leaving in legacy model",
                    airship.getUniqueId(), level.dimension().location(), t);
            }
            break; // at most one rehome per container tick
        }
    }

    private static boolean bootRestoreDone = false;

    /** Reset on server stop so singleplayer relaunch re-restores. */
    public static void resetBootRestore() {
        bootRestoreDone = false;
    }

    /**
     * One-shot eager restore of every held sub-level in the hosting dimension.
     *
     * <p>Sable restores held sub-levels when the container level loads a chunk at their
     * holding position. In the hosting dim those chunk loads only happen via physics
     * tickets of already-live ships — at a fresh boot there are none, so nothing ever
     * restores (and a NEW ship flying near an OLD save's holding position produced
     * surprise "ghost" restores). Here we enumerate the holding region files on disk
     * (r.&lt;regionX&gt;.&lt;regionZ&gt;) and feed every covered chunk position through the
     * public {@code updateChunkStatus(pos, loaded=true)} API; the readiness bypass
     * ({@code IplHostedHoldingReadinessMixin}) then releases everything on the next
     * {@code processChanges}.
     */
    private static void bootRestoreHosted(ServerSubLevelContainer container, ServerLevel hosting) {
        if (bootRestoreDone) return;
        bootRestoreDone = true;

        try {
            java.nio.file.Path dimFolder = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                hosting.dimension(),
                hosting.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
            java.nio.file.Path subLevelsFolder = dimFolder.resolve("sublevels");
            if (!java.nio.file.Files.isDirectory(subLevelsFolder)) {
                LOG.info("[IPL-BOOT-RESTORE] no holding storage at {} (nothing saved yet)", subLevelsFolder);
                return;
            }

            // Holding-chunk region files are "r.<regionX>.<regionZ>" (a numeric third
            // component means a sub-level storage file — skip those).
            java.util.regex.Pattern regionPattern =
                java.util.regex.Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)(?:\\.[a-zA-Z]\\w*)?$");

            int regions = 0;
            var holdingMap = container.getHoldingChunkMap();
            try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.list(subLevelsFolder)) {
                for (java.nio.file.Path file : (Iterable<java.nio.file.Path>) files::iterator) {
                    java.util.regex.Matcher m = regionPattern.matcher(file.getFileName().toString());
                    if (!m.matches()) continue;
                    regions++;
                    int regionX = Integer.parseInt(m.group(1));
                    int regionZ = Integer.parseInt(m.group(2));
                    for (int cx = 0; cx < 32; cx++) {
                        for (int cz = 0; cz < 32; cz++) {
                            holdingMap.updateChunkStatus(
                                new net.minecraft.world.level.ChunkPos(
                                    (regionX << 5) + cx, (regionZ << 5) + cz), true);
                        }
                    }
                }
            }

            int before = container.getAllSubLevels().size();
            holdingMap.processChanges();
            int released = container.getAllSubLevels().size() - before;
            LOG.info("[IPL-BOOT-RESTORE] scanned {} region file(s), released {} held sub-level(s)",
                regions, released);
        } catch (Throwable t) {
            LOG.error("[IPL-BOOT-RESTORE] failed", t);
        }
    }

    /**
     * Restore the duck {@code parentLevel} from persisted user data for hosted sub-levels whose
     * parent is unset (== hosting level, the constructor default after deserialization).
     */
    private static void restoreParents(ServerSubLevelContainer container, ServerLevel hosting) {
        MinecraftServer server = hosting.getServer();
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (!(subLevel instanceof ServerSubLevel hosted)) continue;
            if (hosted.isRemoved()) continue;

            IplSubLevelDuck duck = (IplSubLevelDuck) hosted;
            if (duck.ipl$getParentLevel() != null
                && !IplDimAgnostic.isHostingLevel(duck.ipl$getParentLevel())) {
                continue; // parent already resolved
            }

            CompoundTag userData = hosted.getUserDataTag();
            if (userData == null || !userData.contains(PARENT_DIM_KEY)) {
                // A sub-level SPLIT off a hosted ship is allocated directly in the hosting
                // container with no parent stamp — inherit the parent from its split source.
                UUID splitFrom = hosted.getSplitFromSubLevel();
                SubLevel splitSource = splitFrom != null ? container.getSubLevel(splitFrom) : null;
                if (splitSource != null) {
                    ServerLevel inherited = IplDimAgnostic.getServerParentLevel(splitSource);
                    if (inherited != null) {
                        stampParent(hosted, inherited, hosting);
                        LOG.info("[IPL-REHOME] split sub-level {} inherited parent {} from {}",
                            hosted.getUniqueId(), inherited.dimension().location(), splitFrom);
                        continue;
                    }
                }
                LOG.warn("[IPL-REHOME] hosted sub-level {} has no persisted parent dim; "
                    + "it will not appear in any dimension until reassigned", hosted.getUniqueId());
                continue;
            }

            ResourceKey<Level> parentKey = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(userData.getString(PARENT_DIM_KEY))
            );
            ServerLevel parent = server.getLevel(parentKey);
            if (parent == null) {
                LOG.warn("[IPL-REHOME] persisted parent dim {} for {} is not loaded",
                    parentKey.location(), hosted.getUniqueId());
                continue;
            }

            duck.ipl$setParentLevel(parent);
            duck.ipl$setHostingLevel(hosting);
            LOG.info("[IPL-REHOME] restored parent {} for hosted sub-level {}",
                parentKey.location(), hosted.getUniqueId());
        }
    }

    /**
     * Atomically rehome one sub-level into the hosting dimension. Mirrors
     * {@link SableTransitOps#executeTransit} step-for-step, minus the portal mapping
     * (pose/velocity transfer verbatim — world coordinates are unchanged).
     */
    private static void rehome(
        ServerSubLevel source,
        ServerSubLevelContainer sourceContainer,
        ServerLevel parentLevel,
        ServerLevel hosting
    ) {
        ServerSubLevelContainer hostingContainer = SubLevelContainer.getContainer(hosting);
        if (hostingContainer == null) {
            LOG.warn("[IPL-REHOME] hosting dim has no SubLevelContainer; skipping");
            return;
        }

        // SAME-SLOT rehome: allocate the twin at the source's GLOBAL plot coordinates
        // whenever that slot is free in the hosting grid. Every persisted plot-coordinate
        // reference then survives the rehome — constraint anchors, mods' stored link/plate
        // positions (Simulated's swivel bearing kept the split part's platePos and
        // re-attached its rotary constraint against the OLD slot's coordinates: with a
        // moved slot, Sable's anchor validation threw "pos2 does not fall within the plot"
        // and crashed the server). Slot delta zero also means the verbatim pose carries
        // over exactly.
        int[] plotXZ = null;
        {
            ChunkPos srcCenter = source.getPlot().getCenterChunk();
            int logPlot = hostingContainer.getLogPlotSize();
            int globalX = srcCenter.x >> logPlot;
            int globalZ = srcCenter.z >> logPlot;
            int localX = globalX - ((ipl.sable.mixin.IplSubLevelContainerOriginAccessor) hostingContainer).ipl$originX();
            int localZ = globalZ - ((ipl.sable.mixin.IplSubLevelContainerOriginAccessor) hostingContainer).ipl$originZ();
            int side = 1 << hostingContainer.getLogSideLength();
            if (localX >= 0 && localX < side && localZ >= 0 && localZ < side
                && !hostingContainer.getOccupancy().get(hostingContainer.getIndex(localX, localZ))) {
                plotXZ = new int[]{localX, localZ};
            } else {
                LOG.warn("[IPL-REHOME] same-slot ({},{}) unavailable in hosting grid for uuid={}; "
                        + "falling back to first-free (stored plot references may go stale)",
                    globalX, globalZ, source.getUniqueId());
            }
        }
        if (plotXZ == null) {
            plotXZ = SableTransitOps.findFirstEmptyPlotForMirror(hostingContainer);
        }
        if (plotXZ == null) {
            LOG.warn("[IPL-REHOME] hosting plot grid full; uuid={} stays in legacy model",
                source.getUniqueId());
            return;
        }

        // Slot block delta (dst − src): zero for a same-slot rehome (the normal case since
        // union-free allocation), the plot-grid translation otherwise. Everything expressed
        // in plot coordinates must move by exactly this delta: the pose's rotation point
        // AND the seeded mass baseline below. (Blocks are copied local→local, so their
        // global plot coordinates shift by the same delta.)
        ChunkPos srcMinChunk = source.getPlot().getChunkMin();
        int logPlot = hostingContainer.getLogPlotSize();
        int dstMinChunkX = (plotXZ[0]
            + ((ipl.sable.mixin.IplSubLevelContainerOriginAccessor) hostingContainer).ipl$originX()) << logPlot;
        int dstMinChunkZ = (plotXZ[1]
            + ((ipl.sable.mixin.IplSubLevelContainerOriginAccessor) hostingContainer).ipl$originZ()) << logPlot;
        double slotDeltaX = (double) (dstMinChunkX - srcMinChunk.x) * 16.0;
        double slotDeltaZ = (double) (dstMinChunkZ - srcMinChunk.z) * 16.0;

        UUID uuid = source.getUniqueId();
        Pose3d pose = new Pose3d(source.logicalPose());
        if (slotDeltaX != 0.0 || slotDeltaZ != 0.0) {
            // Cross-slot fallback: the rotation point is a PLOT coordinate — translate it
            // with the blocks so the verbatim world mapping still holds in the new slot.
            // (The old verbatim copy left it pointing at the SOURCE slot; the first mass
            // upload then "corrected" it without position compensation — the size-scaled
            // assembly offset.)
            pose.rotationPoint().add(slotDeltaX, 0.0, slotDeltaZ);
            LOG.warn("[IPL-REHOME] cross-slot rehome for uuid={}: translated rotation point by ({}, {});"
                + " mod-persisted plot references (swivel plate positions etc.) cannot be translated"
                + " and may go stale", uuid, slotDeltaX, slotDeltaZ);
        }
        Vector3d linVel = new Vector3d(source.latestLinearVelocity);
        Vector3d angVel = new Vector3d(source.latestAngularVelocity);

        LOG.info("[IPL-REHOME] rehoming uuid={} {} -> {} plot=({},{}) pos=({},{},{})",
            uuid, parentLevel.dimension().location(), hosting.dimension().location(),
            plotXZ[0], plotXZ[1],
            pose.position().x(), pose.position().y(), pose.position().z());

        // 1. Allocate the hosted twin (same UUID, identical pose). Physics enrolls it in the
        //    HOSTING pipeline; the block-copy cascade below populates mass/CoM before the next
        //    physics step, same proven ordering as executeTransit.
        ServerSubLevel hosted =
            (ServerSubLevel) hostingContainer.allocateSubLevel(uuid, plotXZ[0], plotXZ[1], pose);

        // 2. Stamp parent/hosting (duck + persistent NBT) BEFORE block copy, so any observer
        //    firing during the copy already sees the correct parent.
        stampParent(hosted, parentLevel, hosting);

        // 2.5 Seed the twin's merged-mass baseline from the settled SOURCE tracker
        //     (slot-translated). The fresh tracker's first upload otherwise null-baselines
        //     lastCenterOfMass and jumps rotationPoint from the copied ship CoM to the
        //     FIRST copied block's partial CoM without position compensation — shifting
        //     the ship by R·(shipCoM − firstBlockCoM): the +0.5-along-facing assembly
        //     offset that grew with size and vanished for single blocks. With the baseline
        //     seeded, every upload during the copy walks the invariant-preserving path
        //     (position += R·ΔCoM), and the final mapping equals the source mapping by
        //     construction.
        seedMassBaseline(source, hosted, slotDeltaX, slotDeltaZ);

        // 3. Copy blocks + block entities (3-pass: place, notify, register tickers).
        int blocksCopied = SableTransitOps.copyPlotBlocksPublic(
            source.getPlot(), hosted.getPlot(), parentLevel, hosting);

        // 4. Relocate plot-resident entities (item frames, seats, hanging entities moved into
        //    the plot at assembly). Without this, removeSubLevel(REMOVED) deletes them.
        int entitiesMoved = relocatePlotEntities(source, hosted, parentLevel, hosting);

        // 4.5 DIAGNOSTIC ONLY (an earlier active re-anchor here made freshly assembled
        //     ships vanish — pose mutation at this point is not safe against the live
        //     enrollment/upload ordering). Log the source-vs-new-slot pose relation so the
        //     size-scaled assembly height offset can be pinned from a runtime trace:
        //     position, rotation point, both plots' anchors and the new-slot self CoM.
        {
            Pose3d hostedPose = hosted.logicalPose();
            org.joml.Vector3dc comNew = hosted.getSelfMassTracker().getCenterOfMass();
            ChunkPos srcCenter = source.getPlot().getCenterChunk();
            ChunkPos dstCenter = hosted.getPlot().getCenterChunk();
            LOG.info("[IPL-REHOME-POSE] uuid={} pos=({},{},{}) rp=({},{},{}) srcPlotMin=({},{}) "
                    + "dstPlotMin=({},{}) comNew={}",
                uuid,
                String.format("%.3f", hostedPose.position().x()),
                String.format("%.3f", hostedPose.position().y()),
                String.format("%.3f", hostedPose.position().z()),
                String.format("%.3f", hostedPose.rotationPoint().x()),
                String.format("%.3f", hostedPose.rotationPoint().y()),
                String.format("%.3f", hostedPose.rotationPoint().z()),
                srcCenter.getMinBlockX(), srcCenter.getMinBlockZ(),
                dstCenter.getMinBlockX(), dstCenter.getMinBlockZ(),
                comNew == null ? "null" : String.format("(%.3f,%.3f,%.3f)", comNew.x(), comNew.y(), comNew.z()));
        }

        // 5. Transfer physics velocity verbatim (no portal rotation — same world frame).
        if (linVel.lengthSquared() > 1e-8 || angVel.lengthSquared() > 1e-8) {
            PhysicsPipeline pipeline = hostingContainer.physicsSystem().getPipeline();
            pipeline.addLinearAndAngularVelocity(hosted, linVel, angVel);
        }

        // 6. Remove the parent-dim original. Tracking emits StopTracking to its trackers (their
        //    clients drop the parent-dim copy); the hosted twin re-syncs them into the
        //    sublevels-dim client container on the next hosting-container tick.
        try {
            sourceContainer.removeSubLevel(source, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            LOG.error("[IPL-REHOME] failed to remove parent-dim original uuid={}; "
                + "rolling back hosted twin", uuid, t);
            try {
                hostingContainer.removeSubLevel(hosted, SubLevelRemovalReason.REMOVED);
            } catch (Throwable t2) {
                LOG.error("[IPL-REHOME] rollback of hosted twin also failed for uuid={}", uuid, t2);
            }
            return;
        }

        // 7. Atlas keeps the real body in the hosting dimension. Publish its parent-chart
        // image now; no body/rope/joint/third-party registry migration is permitted.
        ipl.sable.atlas.IplAtlasBodyImages.reconcile(hosted);

        LOG.info("[IPL-REHOME] complete uuid={} blocks={} entities={}", uuid, blocksCopied, entitiesMoved);

        // The hosted twin now owns its FINAL plot slot — safe to run any assembly
        // portal capture that was queued against the pre-rehome ship.
        IplShipNetherPortal.onRehomeComplete(hosted, parentLevel);
    }

    /**
     * Hosted cross-portal transit: the parent flip. Pose is remapped through the portal,
     * velocities rotated, riders teleported, {@code parentLevel} flipped, trackers reset.
     * Plot chunks do not move.
     *
     * @return true if the flip completed.
     */
    public static boolean executeHostedTransit(ServerSubLevel hosted, Portal portal) {
        ServerLevel hosting = hosted.getLevel();
        MinecraftServer server = hosting.getServer();
        if (server == null) return false;

        ServerLevel oldParent = IplDimAgnostic.getServerParentLevel(hosted);
        if (oldParent == null) {
            LOG.warn("[IPL-FLIP] aborted: hosted sub-level {} has no resolvable parent", hosted.getUniqueId());
            return false;
        }

        ServerLevel newParent = server.getLevel(portal.getDestDim());
        if (newParent == null || IplDimAgnostic.isHostingLevel(newParent)) {
            LOG.warn("[IPL-FLIP] aborted: invalid destination dim {} for {}",
                portal.getDestDim().location(), hosted.getUniqueId());
            return false;
        }

        ServerSubLevelContainer hostingContainer = SubLevelContainer.getContainer(hosting);
        if (hostingContainer == null) return false;
        PhysicsPipeline pipeline = hostingContainer.physicsSystem().getPipeline();
        UUID uuid = hosted.getUniqueId();
        Pose3d sourcePose = new Pose3d(hosted.logicalPose());
        Pose3d mappedPose = SableTransitOps.computeMappedPose(sourcePose, portal);

        LOG.info("[IPL-FLIP] firing uuid={} {} -> {} destPos=({},{},{})",
            uuid, oldParent.dimension().location(), newParent.dimension().location(),
            mappedPose.position().x(), mappedPose.position().y(), mappedPose.position().z());

        // The real body changes frame only after Atlas permits the group rehome. Preserve
        // its physical velocity by rotating both velocity vectors through that same portal.
        Vector3d linear = pipeline.getLinearVelocity(hosted, new Vector3d());
        Vector3d angular = pipeline.getAngularVelocity(hosted, new Vector3d());
        Vec3 mappedLin = portal.transformLocalVec(new Vec3(linear.x, linear.y, linear.z));
        Vec3 mappedAng = portal.transformLocalVec(new Vec3(angular.x, angular.y, angular.z));

        // Teleport riders BEFORE moving the pose, while the deck is still under them.
        int riders = teleportRiders(hosted, oldParent, newParent, portal);

        // Move only this body's native frame. Connected bodies are deliberately NOT
        // teleported: Atlas treats a portal as a window, so an active cross-aperture joint
        // remains represented by source geometry plus image colliders until its own body
        // legitimately completes transit.
        pipeline.teleport(hosted, mappedPose.position(), mappedPose.orientation());
        hosted.logicalPose().set(mappedPose);
        // A subsequent hosting tick must begin entirely in the destination frame. Merely
        // forgetting PortalCrossingDetector's cached trail is insufficient: captureTrail
        // seeds a missing trail from lastPose, which would otherwise still be source-space
        // and could create a fictitious segment through a chained or self-recursive portal.
        hosted.updateLastPose();
        pipeline.resetVelocity(hosted);
        pipeline.addLinearAndAngularVelocity(hosted,
            new Vector3d(mappedLin.x, mappedLin.y, mappedLin.z),
            new Vector3d(mappedAng.x, mappedAng.y, mappedAng.z));

        // Retire every old-parent portal image before publishing the new parent image.
        // Otherwise the completed session's P(body) and the destination's identity(body)
        // coexist in one chart for a broad-phase step. That produces two contact histories
        // for one rigid body: the invisible original and the visible, slightly divergent
        // twin reported on oblique/high-speed crossings.
        for (StraddleKey key : IplAtlasStraddleSession.sessionKeysFor(uuid)) {
            IplAtlasStraddleSession.clear(key, "parent-flip");
            IplStraddleSessionSync.onSessionEnd(server, key, "parent-flip");
        }

        stampParent(hosted, newParent, hosting);
        hosted.updateBoundingBox();
        ipl.sable.atlas.IplAtlasBodyImages.reconcile(hosted);

        // Keep existing trackers through the flip. Removing them here creates a visible gap:
        // the destination projection is gone as soon as the ship clears the portal, while a
        // new full-sync is not sent until the tracking system's next tick. The handoff packet
        // moves their existing client object into the destination frame immediately; normal
        // tracking then retains in-range viewers and removes only viewers that truly left.
        // The old tracked set only contains source-side viewers. A cross-dimension exit can
        // reveal the fully crossed body to destination portal viewers before the tracking tick
        // adds them, so hand off to both sets now instead of leaving a one-way invisible ship.
        queueParentHandoff(hosted, newParent, portal);

        LOG.info("[IPL-FLIP] complete uuid={} riders={}", uuid, riders);
        return true;
    }

    /**
     * HANDOFF COALESCING.
     *
     * <p>A handoff describes ONE fact: which chart a body's client object now lives in.
     * Only the last one in a tick is true. Emitting it straight from the flip fanned out a
     * custom-payload RPC to every tracking player, every dragging player and every ImmPtl
     * chunk viewer of the destination chunk, once per flip. A body in a portal loop, or a
     * rigid assembly whose members all flip through the same portal, turns that into a
     * burst of near-identical payloads inside a single tick: every packet but the last one
     * describes a chart the body has already left, and the burst itself is what shows up on
     * the client as a custom-payload error.
     *
     * <p>So the flip records its intent and the tick flushes the LAST intent per body.
     * One packet per body per tick, carrying the chart the body actually ended the tick in.
     */
    private record PendingHandoff(ServerSubLevel body, ServerLevel destination, Portal portal) {}

    private static final java.util.Map<UUID, PendingHandoff> PENDING_HANDOFFS =
        new java.util.LinkedHashMap<>();

    private static void queueParentHandoff(
        ServerSubLevel body, ServerLevel destination, Portal portal
    ) {
        PENDING_HANDOFFS.put(body.getUniqueId(), new PendingHandoff(body, destination, portal));
    }

    /** Emits one handoff per body that flipped this tick. Called from the tail-of-tick sweep. */
    public static void flushParentHandoffs(MinecraftServer server) {
        if (server == null || PENDING_HANDOFFS.isEmpty()) return;
        List<PendingHandoff> batch = new ArrayList<>(PENDING_HANDOFFS.values());
        PENDING_HANDOFFS.clear();
        for (PendingHandoff pending : batch) {
            ServerSubLevel body = pending.body();
            if (body == null || body.isRemoved()) continue;
            try {
                sendParentHandoff(server, body, pending.destination(), pending.portal());
            } catch (Throwable t) {
                LOG.warn("[IPL-FLIP] failed to emit parent handoff for uuid={}",
                    body.getUniqueId(), t);
            }
        }
    }

    /** Drops queued handoffs; server shutdown has no client left to inform. */
    public static void clearPendingHandoffs() {
        PENDING_HANDOFFS.clear();
    }

    private static void sendParentHandoff(
        MinecraftServer server, ServerSubLevel body, ServerLevel destination, Portal portal
    ) {
        java.util.Set<UUID> recipients = new java.util.HashSet<>(body.getTrackingPlayers());
        recipients.addAll(IplGrabChain.getDraggingPlayers(body.getUniqueId()));
        net.minecraft.world.level.ChunkPos destinationChunk = new net.minecraft.world.level.ChunkPos(
            net.minecraft.core.BlockPos.containing(body.logicalPose().position().x(),
                body.logicalPose().position().y(), body.logicalPose().position().z())
        );
        for (ServerPlayer viewer : ImmPtlChunkTracking.getPlayersViewingChunk(
            destination.dimension(), destinationChunk.x, destinationChunk.z, false
        )) {
            recipients.add(viewer.getUUID());
        }
        String transform = encodePortalTransform(portal);
        String portalNbt = IplStraddleSessionSync.encodePortal(portal);
        for (UUID recipient : recipients) {
            ServerPlayer player = server.getPlayerList().getPlayer(recipient);
            if (player == null) continue;
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                player,
                "ipl.sable.client.IplParentDimSync.RemoteCallables.handoff",
                body.getUniqueId().toString(), destination.dimension().location().toString(), transform,
                portalNbt
            );
        }
    }

    /** Serializes the exact crossing transform for clients that do not track the source portal. */
    private static String encodePortalTransform(Portal portal) {
        Vec3 origin = portal.getOriginPos();
        Vec3 destination = portal.getDestPos();
        qouteall.q_misc_util.my_util.DQuaternion rotation = portal.getRotationD();
        return String.join(";",
            Double.toHexString(origin.x), Double.toHexString(origin.y), Double.toHexString(origin.z),
            Double.toHexString(destination.x), Double.toHexString(destination.y), Double.toHexString(destination.z),
            Double.toHexString(rotation.x), Double.toHexString(rotation.y),
            Double.toHexString(rotation.z), Double.toHexString(rotation.w),
            Double.toHexString(portal.getScaling()),
            Double.toHexString(portal.getNormal().x), Double.toHexString(portal.getNormal().y),
            Double.toHexString(portal.getNormal().z),
            portal.getUUID().toString()
        );
    }

    /**
     * Seed the hosted twin's {@code MergedMassTracker} baseline from the settled SOURCE
     * tracker (slot-translated), so the block copy's first mass upload cannot
     * null-baseline and jump the rotation point without position compensation. See the
     * call site in {@link #rehome} and {@code IplMergedMassBaselineAccessor} for the math.
     */
    private static void seedMassBaseline(
        ServerSubLevel source, ServerSubLevel hosted, double slotDeltaX, double slotDeltaZ
    ) {
        try {
            if (!(source.getMassTracker() instanceof dev.ryanhcode.sable.api.physics.mass.MergedMassTracker srcTracker)
                || !(hosted.getMassTracker() instanceof dev.ryanhcode.sable.api.physics.mass.MergedMassTracker dstTracker)) {
                return;
            }
            org.joml.Vector3dc srcCoM = srcTracker.getCenterOfMass();
            if (srcCoM == null) {
                return; // source ship has no solid blocks — nothing to seed
            }
            Object accessor = dstTracker;
            if (!(accessor instanceof ipl.sable.mixin.IplMergedMassBaselineAccessor access)) {
                LOG.warn("[IPL-REHOME] mass tracker accessor mixin missing; "
                    + "first-upload offset protection inactive for {}", hosted.getUniqueId());
                return;
            }
            access.ipl$setLastCenterOfMass(new Vector3d(srcCoM).add(slotDeltaX, 0.0, slotDeltaZ));
            access.ipl$setLastInertiaTensor(new org.joml.Matrix3d(srcTracker.getInertiaTensor()));
            access.ipl$setLastMass(srcTracker.getMass());
        } catch (Throwable t) {
            LOG.warn("[IPL-REHOME] mass-baseline seeding failed for {}; "
                + "first-upload offset may reappear", hosted.getUniqueId(), t);
        }
    }

    /**
     * A sub-level SPLIT off a hosted ship was just recorded (core Sable's
     * {@code ServerSubLevel.setSplitFrom}, fired from {@code kickFromContainingSubLevel}
     * for every nested assembly BEFORE control returns to the splitting mod — see
     * {@code IplSplitParentStampMixin}). Inherit the parent eagerly and publish the fresh
     * body's parent-chart image. Both real bodies remain together in the hosting pipeline,
     * so synchronous swivel constraints use normal Sable ownership.
     */
    public static void onSplitAllocated(ServerSubLevel split, ServerSubLevel containing) {
        try {
            if (containing == null || split == null || split.isRemoved()) return;
            if (!IplDimAgnostic.isHosted(split)) return; // parent-dim split rehomes normally
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(containing);
            if (parent == null) return;
            ServerLevel hosting = split.getLevel();

            IplSubLevelDuck duck = (IplSubLevelDuck) split;
            if (duck.ipl$getParentLevel() != null
                && !IplDimAgnostic.isHostingLevel(duck.ipl$getParentLevel())) {
                return; // already stamped
            }

            stampParent(split, parent, hosting);
            ipl.sable.atlas.IplAtlasBodyImages.reconcile(split);
            LOG.info("[IPL-REHOME] split sub-level {} eagerly inherited parent {} from {}",
                split.getUniqueId(), parent.dimension().location(), containing.getUniqueId());
        } catch (Throwable t) {
            LOG.error("[IPL-REHOME] eager split parent stamp failed for {}",
                split != null ? split.getUniqueId() : null, t);
        }
    }

    private static int restampCounter = 0;

    /**
     * Every ~5 seconds, re-send the client parent stamp for every hosted sub-level to
     * every player tracking it. Idempotent client-side (same-parent stamps change
     * nothing); closes every "client parent never arrived" race for good.
     */
    private static void restampClientParents(ServerSubLevelContainer container, ServerLevel hosting) {
        if (++restampCounter < 100) return;
        restampCounter = 0;

        MinecraftServer server = hosting.getServer();
        if (server == null) return;
        for (SubLevel sub : container.getAllSubLevels()) {
            if (!(sub instanceof ServerSubLevel hosted) || hosted.isRemoved()) continue;
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(hosted);
            if (parent == null) continue;
            for (UUID trackerUuid : hosted.getTrackingPlayers()) {
                ServerPlayer viewer = server.getPlayerList().getPlayer(trackerUuid);
                if (viewer == null) continue;
                qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(
                    viewer,
                    "ipl.sable.client.IplParentDimSync.RemoteCallables.setParent",
                    hosted.getUniqueId().toString(),
                    parent.dimension().location().toString()
                );
            }
        }
    }

    /** Duck + persistent-NBT parent stamp. */
    private static void stampParent(ServerSubLevel hosted, ServerLevel parent, ServerLevel hosting) {
        IplSubLevelDuck duck = (IplSubLevelDuck) hosted;
        duck.ipl$setParentLevel(parent);
        duck.ipl$setHostingLevel(hosting);

        CompoundTag userData = hosted.getUserDataTag();
        if (userData == null) {
            userData = new CompoundTag();
            hosted.setUserDataTag(userData);
        }
        userData.putString(PARENT_DIM_KEY, parent.dimension().location().toString());
    }

    /**
     * Move entities physically living inside the source plot's chunks (offset coords in the
     * parent dim) to the corresponding position in the hosted plot. Skips players, portals,
     * and passengers (their vehicles move; vanilla re-seats passengers on its own).
     */
    private static int relocatePlotEntities(
        ServerSubLevel source, ServerSubLevel hosted,
        ServerLevel parentLevel, ServerLevel hosting
    ) {
        ServerLevelPlot srcPlot = source.getPlot();
        ServerLevelPlot dstPlot = hosted.getPlot();

        int moved = 0;
        for (PlotChunkHolder holder : new ArrayList<>(srcPlot.getLoadedChunks())) {
            ChunkPos srcChunkPos = holder.getChunk().getPos();
            ChunkPos localPos = srcPlot.toLocal(srcChunkPos);
            ChunkPos dstChunkPos = dstPlot.toGlobal(localPos);

            double dx = dstChunkPos.getMinBlockX() - srcChunkPos.getMinBlockX();
            double dz = dstChunkPos.getMinBlockZ() - srcChunkPos.getMinBlockZ();

            AABB chunkBox = new AABB(
                srcChunkPos.getMinBlockX(), parentLevel.getMinBuildHeight(), srcChunkPos.getMinBlockZ(),
                srcChunkPos.getMinBlockX() + 16.0, parentLevel.getMaxBuildHeight(), srcChunkPos.getMinBlockZ() + 16.0
            );

            for (Entity entity : parentLevel.getEntitiesOfClass(Entity.class, chunkBox)) {
                if (entity instanceof Portal) continue;
                if (entity instanceof ServerPlayer) continue;
                if (entity.isRemoved()) continue;
                if (entity.getVehicle() != null) continue;

                Vec3 newPos = entity.position().add(dx, 0.0, dz);
                try {
                    Entity result = ServerTeleportationManager.teleportEntityGeneral(entity, newPos, hosting);
                    if (result != null && !result.isRemoved()) {
                        result.setDeltaMovement(entity.getDeltaMovement());
                    }
                    moved++;
                } catch (Throwable t) {
                    LOG.warn("[IPL-REHOME] failed to relocate plot entity {} for {}",
                        entity, source.getUniqueId(), t);
                }
            }
        }
        return moved;
    }

    /**
     * Teleport entities standing on the hosted airship (bbox overlap in the PARENT dim — the
     * hosted variant of {@link SableTransitOps}'s rider teleport, which queries
     * {@code source.getLevel()} and would otherwise look in the hosting dim).
     *
     * <p>Partitioned by the crossing plane (declarative-straddle phase 3): with the
     * rehome firing at MAJORITY-crossed, riders on the still-out minority part must NOT
     * be yanked through — they keep standing on the reverse session's mapped image,
     * which the through-part collision/interaction family already supports. Only riders
     * whose center is past the plane map. At a fully-CROSSED transit every rider passes
     * the test, so the fast-crossing fallback path is unchanged by construction.
     */
    private static int teleportRiders(
        ServerSubLevel hosted, ServerLevel oldParent, ServerLevel newParent, Portal portal
    ) {
        AABB queryBbox = hosted.boundingBox().toMojang().inflate(0.5);
        List<Entity> entities = oldParent.getEntitiesOfClass(Entity.class, queryBbox);

        Vec3 planePoint = portal.getOriginPos();
        Vec3 sourceToDest = portal.getNormal().scale(-1.0);

        int teleported = 0;
        for (Entity entity : entities) {
            if (entity instanceof Portal) continue;
            if (entity.isRemoved()) continue;
            if (entity.getVehicle() != null) continue;
            // Bounding-box CENTER, matching the collision family's frame selection —
            // a rider left behind must be the same rider collision hands the mapped image.
            if (entity.getBoundingBox().getCenter().subtract(planePoint).dot(sourceToDest) < 0.0) {
                continue; // still on the minority/native side of the plane
            }

            Vec3 mappedPos = portal.transformPoint(entity.position());
            Vec3 mappedDelta = portal.transformLocalVec(entity.getDeltaMovement());

            try {
                Entity result = ServerTeleportationManager.teleportEntityGeneral(entity, mappedPos, newParent);
                if (result != null && !result.isRemoved()) {
                    result.setDeltaMovement(mappedDelta);
                }
                teleported++;
            } catch (Throwable t) {
                LOG.warn("[IPL-FLIP] failed to teleport rider {} during flip of {}",
                    entity, hosted.getUniqueId(), t);
            }
        }
        return teleported;
    }

}
