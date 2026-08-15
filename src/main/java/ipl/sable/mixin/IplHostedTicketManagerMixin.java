package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make Sable's physics chunk-ticket manager parent-aware for hosted sub-levels.
 *
 * <p>Two problems with a sub-level whose container lives in {@code ipl_sable:sublevels} but
 * whose pose is in parent-dim coordinates:
 *
 * <ol>
 *   <li><b>Instant unload:</b> {@code isChunkLoadedEnough(hostingLevel, poseChunk)} is false
 *       (no players, no tickets, pose coords aren't in the plot grid), so
 *       {@code update()} would immediately {@code moveToUnloaded} every hosted ship, and the
 *       ticket cleanup pass would thrash terrain sections every tick. Fix: the hosting dim is
 *       always "loaded enough" — hosted ships stay active; stale terrain tickets still expire
 *       through the 20-tick outdated path.</li>
 *   <li><b>Terrain collision:</b> the stock per-sub-level loop cannot see hosted plots. The
 *       parent chart therefore enrolls native parent-dimension chunks using that level's own
 *       coordinate system and height profile. Atlas sessions separately enroll the mapped
 *       destination image region in its destination chart.</li>
 * </ol>
 */
@Pseudo
@Mixin(value = PhysicsChunkTicketManager.class, remap = false)
public abstract class IplHostedTicketManagerMixin {

    @ModifyReturnValue(method = "isChunkLoadedEnough", at = @At("RETURN"), require = 1)
    private static boolean ipl$hostingDimAlwaysLoadedEnough(
        boolean original, ServerLevel level, int x, int z
    ) {
        if (original) return true;
        return IplDimAgnostic.isHostingLevel(level);
    }

    @Unique
    private static long ipl$lastTerrainLogMs = 0;

    // ======================================================================
    // Per-scene model (spec §2.2 phase 1): terrain enrolls in the PARENT's
    // own manager — native chunks, native coords, native height profile.
    // ======================================================================

    @org.spongepowered.asm.mixin.Shadow(remap = false)
    private java.util.Map<SectionPos, dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket>
        physicsChunks;

    /**
     * Enroll terrain sections around every hosted ship whose PARENT is this manager's
     * level. The stock per-sub-level loop never sees hosted ships (they live in the
     * hosting container), so this replicates exactly its ticket semantics for them:
     * get-or-create the ticket (feeding the section to THIS pipeline on creation — real
     * chunk, no read override) and REFRESH lastInhabitedTick, so the 20-tick expiry
     * doesn't churn terrain in and out of the scene.
     *
     * <p>Deliberately NOT replicated: the moveToUnloaded branch. Holding/unload semantics
     * for hosted ships stay with the hosting container (IplHostedHoldingMixin et al.).
     */
    @Inject(method = "update", at = @At("HEAD"), require = 1)
    private void ipl$enrollHostedShipTerrain(
        ServerLevel level,
        ServerSubLevelContainer container,
        SubLevelPhysicsSystem system,
        PhysicsPipeline pipeline,
        double timeStep,
        CallbackInfo ci
    ) {
        if (!ipl.sable.dim.IplSceneOwnership.isEnabled()) return;
        if (IplDimAgnostic.isHostingLevel(level)) return;

        BoundingBox3d b = new BoundingBox3d();
        BoundingBox3d b2 = new BoundingBox3d();
        Vector3d velocity = new Vector3d();
        long gameTime = level.getGameTime();
        int enrolledShips = 0;

        // Atlas indexes bodies by parent once per hosting tick. Scanning every hosted
        // ship from every parent chart made terrain enrollment O(charts * ships).
        for (ServerSubLevel subLevel : ipl.sable.dim.IplSceneOwnership.hostedInParent(level)) {
            if (subLevel.isRemoved()) continue;

            // Parent-pointer load gate: a ship whose parent chunk is unloaded goes
            // DORMANT (native body Fixed) and is skipped entirely — always-live plot
            // chunks would otherwise simulate it in mid-air against terrain that was
            // never baked (nether ship, everyone in the overworld → falls into void).
            if (ipl.sable.atlas.IplHostedTerrainGate.tick(level, pipeline, subLevel)) {
                continue;
            }
            enrolledShips++;

            // Same bounds expansion as the stock loop (incl. fall-velocity prediction);
            // per-body calls forward to the owning pipeline through the ownership guard.
            b.set(subLevel.boundingBox());
            b2.set(b);
            if (subLevel.lastPose().position()
                .distanceSquared(subLevel.logicalPose().position()) > 0.05 * 0.05) {
                pipeline.getLinearVelocity(subLevel, velocity.zero()).mul(timeStep);
                b2.move(0.0, Mth.clamp(velocity.y,
                    -PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE,
                    PhysicsChunkTicketManager.MAX_PREDICTION_DISTANCE), 0.0);
                b.expandTo(b2);
            }
            b.expand(1.0, b);

            ipl$enrollSections(level, pipeline, b, gameTime);
        }

        // Atlas image colliders need destination-chart terrain around their mapped region.
        int[] imageRegions = {0};
        ipl.sable.transit.IplAtlasStraddleSession.forEachSessionInto(level, (ship, mapping) -> {
            // Enclosing AABB of the portal-mapped ship bounds (rotation-capable: the 8
            // corners go through the full isometry).
            BoundingBox3d cb = mapping.mapAabb(ship.boundingBox());
            cb.expand(1.0, cb);
            ipl$enrollSections(level, pipeline, cb, gameTime);
            imageRegions[0]++;
        });
        enrolledShips += imageRegions[0];

        long now = System.currentTimeMillis();
        if (enrolledShips > 0 && now - ipl$lastTerrainLogMs > 5000) {
            ipl$lastTerrainLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-terrain").info(
                "[IPL-SCENE-TERRAIN] {} enrolled native terrain for {} hosted ship/image region(s)",
                level.dimension().location(), enrolledShips);
        }
    }

    /**
     * Get-or-create + REFRESH tickets for every section in {@code bounds} (the stock
     * addTicket semantics — refresh matters: without it the 20-tick expiry churns terrain
     * in and out of the scene).
     */
    @Unique
    private void ipl$enrollSections(
        ServerLevel level, PhysicsPipeline pipeline, BoundingBox3d bounds, long gameTime
    ) {
        BoundingBox3i chunkBounds = bounds.chunkBoundsFrom();
        for (int x = chunkBounds.minX(); x <= chunkBounds.maxX(); x++) {
            for (int z = chunkBounds.minZ(); z <= chunkBounds.maxZ(); z++) {
                if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) continue;
                for (int y = chunkBounds.minY(); y <= chunkBounds.maxY(); y++) {
                    int index = level.getSectionIndexFromSectionY(y);
                    if (index < 0 || index >= level.getSectionsCount()) continue;

                    SectionPos sectionPos = SectionPos.of(x, y, z);
                    dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket ticket =
                        this.physicsChunks.get(sectionPos);
                    if (ticket == null) {
                        LevelChunk chunkAt = level.getChunk(x, z);
                        pipeline.handleChunkSectionAddition(
                            chunkAt.getSection(index), x, y, z, false);
                        ticket = new dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicket(
                            sectionPos, gameTime, null);
                        this.physicsChunks.put(sectionPos, ticket);
                    }
                    ticket.setLastInhabitedTick(gameTime);
                }
            }
        }
    }
}
