package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * THE PLOT BRIDGE — make the plot grid a universal address space readable from every
 * dimension.
 *
 * <p>Sable's gameplay code (entity↔sub-level collision clipping, block-placement overlap
 * checks, {@code getContaining} lookups, raycasts) transforms positions into plot-grid
 * coordinates and then reads blocks/plots through the CURRENT level's container, because in
 * stock Sable a sub-level's chunks are embedded in the same level the interaction happens
 * in. Post-rehome the chunks live only in {@code ipl_sable:sublevels}, so those reads find
 * an empty grid slot: no collision, free block placement inside ships, broken lookups.
 *
 * <p>Plot-slot allocation is globally coordinated by the hosting container, so a vacant
 * slot in a parent container can never shadow a DIFFERENT sub-level — bridging a failed
 * lookup to the hosting container is unambiguous. Both public chunk-coordinate
 * {@code getPlot} overloads are bridged; {@code getChunk}/{@code getChunkHolder}/
 * {@code getPlayersTracking}/{@code getContaining} all route through them, and Sable's
 * server/client chunk-cache mixins route vanilla {@code Level.getBlockState/setBlock} at
 * plot coords through {@code container.getChunk} — so parent-dim block reads of hosted
 * ships work everywhere. The private {@code getLocalPlot} used by the ALLOCATION guard is
 * deliberately NOT bridged (a parent allocating a fresh sub-level on a slot the hosting
 * grid occupies must not throw "Plot already exists"; rehome frees the slot a tick later).
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplPlotBridgeMixin {

    @Shadow
    public abstract Level getLevel();

    @ModifyReturnValue(
        method = "getPlot(II)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;",
        at = @At("RETURN"),
        require = 1
    )
    private LevelPlot ipl$bridgePlotByChunkXZ(LevelPlot original, int chunkX, int chunkZ) {
        LevelPlot resolved = original != null ? original : ipl$hostingPlot(chunkX, chunkZ);
        // Universal packet bridge: the first hosted-plot resolution inside a modded
        // packet handler names the handler's ship and arms its world-frame context.
        ipl.sable.dim.IplWorldFrameContext.notifyPlotResolved(resolved);
        return resolved;
    }

    @ModifyReturnValue(
        method = "getPlot(Lnet/minecraft/world/level/ChunkPos;)Ldev/ryanhcode/sable/sublevel/plot/LevelPlot;",
        at = @At("RETURN"),
        require = 1
    )
    private LevelPlot ipl$bridgePlotByChunkPos(LevelPlot original, ChunkPos pos) {
        LevelPlot resolved = original != null ? original : ipl$hostingPlot(pos.x, pos.z);
        ipl.sable.dim.IplWorldFrameContext.notifyPlotResolved(resolved);
        return resolved;
    }

    @Unique
    private LevelPlot ipl$hostingPlot(int chunkX, int chunkZ) {
        Level self = this.getLevel();
        if (self == null || IplDimAgnostic.isHostingLevel(self)) {
            return null; // hosting container resolves its own plots; no recursion
        }
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(self);
        if (hosting == null || hosting == (Object) this) return null;
        return hosting.getPlot(chunkX, chunkZ);
    }

    /**
     * Removals route to the container that OWNS the sub-level: command paths call
     * {@code requireSubLevelContainer(sourceLevel).removeSubLevel(hostedShip, ...)},
     * but a hosted ship belongs to the hosting dimension's container — removing from
     * the parent's container is a wrong-owner no-op that leaves the ship alive.
     * Same-owner calls (rehome's own removals included) pass through untouched.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
        method = "removeSubLevel(Ldev/ryanhcode/sable/sublevel/SubLevel;"
            + "Ldev/ryanhcode/sable/sublevel/storage/SubLevelRemovalReason;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ipl$routeRemoveToOwningContainer(
        dev.ryanhcode.sable.sublevel.SubLevel subLevel,
        dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason reason,
        org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci
    ) {
        Level self = this.getLevel();
        if (self == null || subLevel == null || subLevel.getLevel() == self) return;
        SubLevelContainer owner = SubLevelContainer.getContainer(subLevel.getLevel());
        if (owner == null || owner == (Object) this) return;
        owner.removeSubLevel(subLevel, reason);
        ci.cancel();
    }

    /**
     * FRAME-SCOPED enumeration: inside an armed or deferred world frame (addon packet
     * handlers via the universal packet bridge, hosted BE ticks, interactions),
     * {@code getAllSubLevels()} on a parent-level container includes hosted ships whose
     * parent is that level — so addon enumerations (Photomancy's camera capture and
     * sub-level list, and anything else that lists "the ships in this world") see the
     * dim-agnostic truth. Deliberately NOT blanket: Sable's own tick/holding/physics
     * machinery runs OUTSIDE frames and keeps stock semantics — a blanket bridge here
     * would re-open the parent-holding-serializes-hosted-ships disease.
     */
    @ModifyReturnValue(method = "getAllSubLevels()Ljava/util/List;", at = @At("RETURN"), require = 0)
    private java.util.List<dev.ryanhcode.sable.sublevel.SubLevel> ipl$frameScopedHostedEnumeration(
        java.util.List<dev.ryanhcode.sable.sublevel.SubLevel> original
    ) {
        if (!ipl.sable.dim.IplWorldFrameContext.frameActive()) return original;
        Level self = this.getLevel();
        if (!(self instanceof net.minecraft.server.level.ServerLevel)
            || IplDimAgnostic.isHostingLevel(self)) {
            return original;
        }
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(self);
        if (hosting == null || hosting == (Object) this) return original;

        java.util.List<dev.ryanhcode.sable.sublevel.SubLevel> out = null;
        for (dev.ryanhcode.sable.sublevel.SubLevel sub : hosting.getAllSubLevels()) {
            if (sub.isRemoved() || IplDimAgnostic.getParentLevel(sub) != self) continue;
            if (original.contains(sub)) continue;
            if (out == null) out = new java.util.ArrayList<>(original);
            out.add(sub);
        }
        return out != null ? out : original;
    }

    /**
     * By-UUID lookups are bridged too: tools resolve their target from the level they're
     * used in (e.g. Simulated's physics staff does {@code getContainer(player.level())
     * .getSubLevel(uuid)} and silently no-ops on null). Gated to hosted sub-levels whose
     * {@code parentLevel} is this dimension — a tool in the nether must not resolve an
     * overworld airship.
     */
    @ModifyReturnValue(
        method = "getSubLevel(Ljava/util/UUID;)Ldev/ryanhcode/sable/sublevel/SubLevel;",
        at = @At("RETURN"),
        require = 1
    )
    private dev.ryanhcode.sable.sublevel.SubLevel ipl$bridgeSubLevelByUuid(
        dev.ryanhcode.sable.sublevel.SubLevel original, java.util.UUID uuid
    ) {
        if (original != null) return original;
        Level self = this.getLevel();
        if (self == null || IplDimAgnostic.isHostingLevel(self)) return original;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(self);
        if (hosting == null || hosting == (Object) this) return original;

        dev.ryanhcode.sable.sublevel.SubLevel hosted = hosting.getSubLevel(uuid);
        if (hosted != null && !hosted.isRemoved()) {
            if (IplDimAgnostic.getParentLevel(hosted) == self) {
                return hosted;
            }
            // Also resolve ships STRADDLING INTO this dimension: their through-part is
            // interactable here (the staff's drag packet resolves the target by UUID from
            // the player's level — without this, dragging the through-part silently
            // no-ops server-side while working fine from the parent side).
            if (ipl.sable.transit.IplStraddlePoseMap.getMappingInto(hosted, self) != null) {
                return hosted;
            }
        }
        return original;
    }
}
