package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Sable's render-pose baseline for an atomic cross-portal handoff. */
@Mixin(value = SubLevelSnapshotInterpolator.class, remap = false)
public interface IplSnapshotInterpolatorAccessor {

    @Accessor("runningSnapshot")
    Pose3d ipl$getRunningSnapshot();
}
