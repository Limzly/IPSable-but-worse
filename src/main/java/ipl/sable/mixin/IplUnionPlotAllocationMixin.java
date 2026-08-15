package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.Level;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;

/**
 * UNION-FREE PLOT ALLOCATION — make same-slot rehoming actually fire.
 *
 * <p>Every fresh assembly allocates in its parent dimension's plot grid, which is
 * (by design) always near-empty: ships are rehomed into {@code ipl_sable:sublevels}
 * one container-tick later, freeing the parent slot again. Stock
 * {@code getFirstEmptyPlot} therefore hands out the SAME first slot — local (0,0),
 * global (10000,10000) — for practically every parent-dim assembly. That slot is
 * almost always occupied in the HOSTING grid (the first ship ever hosted owns it),
 * so {@code SableRehomeOps}' same-slot rehome nearly always fell back to
 * first-free: the plot slot MOVED during rehome, every persisted plot-coordinate
 * reference went stale (Simulated's swivel {@code platePos}, constraint anchors —
 * "pos2 does not fall within the plot", or worse, a stale reference RESOLVING
 * against whatever other ship now owns the old slot), and the first-upload offset
 * compensation was skipped by its own plot-translation guard.
 *
 * <p>Fix at the source: a parent-dimension (non-hosting) server container picks the
 * first slot that is free in BOTH its own grid AND the hosting grid. The rehome's
 * same-slot claim then succeeds by construction, the twin keeps identical global
 * plot coordinates, and every stored plot-coordinate reference survives. Grid
 * geometry is identical across containers (same origin/side defaults); if it ever
 * differs, we bail to stock behavior (the rehome fallback still handles it).
 *
 * <p>Two parent dimensions assembling within the same tick can still race one
 * hosting slot; the loser takes the rehome fallback path, which now translates the
 * pose and mass baseline correctly (see {@code SableRehomeOps}).
 */
@Pseudo
@Mixin(value = SubLevelContainer.class, remap = false)
public abstract class IplUnionPlotAllocationMixin {

    @Shadow
    public abstract Level getLevel();

    @Inject(method = "getFirstEmptyPlot", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void ipl$firstSlotFreeInBothGrids(CallbackInfoReturnable<Vector2i> cir) {
        Level self = this.getLevel();
        if (self == null || self.isClientSide() || IplDimAgnostic.isHostingLevel(self)) {
            return; // hosting container allocates natively; client never allocates fresh slots
        }
        SubLevelContainer mine = (SubLevelContainer) (Object) this;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(self);
        if (hosting == null || hosting == mine) {
            return;
        }
        // Identical grid geometry is required for index-aligned occupancy comparison.
        if (hosting.getLogSideLength() != mine.getLogSideLength()
            || ((IplSubLevelContainerOriginAccessor) hosting).ipl$originX()
                != ((IplSubLevelContainerOriginAccessor) mine).ipl$originX()
            || ((IplSubLevelContainerOriginAccessor) hosting).ipl$originZ()
                != ((IplSubLevelContainerOriginAccessor) mine).ipl$originZ()) {
            return;
        }

        int side = 1 << mine.getLogSideLength();
        BitSet ours = mine.getOccupancy();
        BitSet theirs = hosting.getOccupancy();
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                int index = mine.getIndex(x, z);
                if (!ours.get(index) && !theirs.get(index)) {
                    cir.setReturnValue(new Vector2i(x, z));
                    return;
                }
            }
        }
        // Union exhausted (both grids full at every intersection) — let stock pick a
        // self-free slot; the rehome fallback handles the slot move.
    }
}
