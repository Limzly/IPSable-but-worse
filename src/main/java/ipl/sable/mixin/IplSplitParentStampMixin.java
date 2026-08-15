package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.transit.SableRehomeOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stamp a SPLIT sub-level's parent EAGERLY, at the moment core Sable records the split.
 *
 * <p>A split of a HOSTED ship (swivel bearing activation, heat-map split, nested assembly)
 * allocates the new sub-level directly in the hosting container — with no parent stamp.
 * Previously the parent was inherited one hosting-container tick later
 * ({@code SableRehomeOps.restoreParents}), leaving a window with no parent-chart image for
 * the split. The real split and source bodies both stay in the hosting Rapier chart, so their
 * synchronous swivel constraint uses normal Sable ownership.
 *
 * <p>{@code ServerSubLevel.setSplitFrom} is core Sable's single chokepoint where the split
 * relationship is recorded — called from {@code kickFromContainingSubLevel} for every
 * nested assembly, BEFORE control returns to the mod that triggered the split (and thus
 * before any constraint attach). We inherit the parent from the containing ship and
 * publish the fresh body's parent-chart image right here, so by the time the swivel
 * attaches its rotary constraint both bodies already share the hosting chart.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class IplSplitParentStampMixin {

    @Inject(method = "setSplitFrom", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$stampSplitParentEagerly(
        ServerSubLevel containingSubLevel, Pose3d originalPose, CallbackInfo ci
    ) {
        SableRehomeOps.onSplitAllocated((ServerSubLevel) (Object) this, containingSubLevel);
    }
}
