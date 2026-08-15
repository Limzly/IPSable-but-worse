package ipl.sable.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Mutable previous client endpoint needed to preserve render velocity across a frame map. */
@Mixin(value = SubLevel.class, remap = false)
public interface IplSubLevelLastPoseAccessor {

    @Accessor("lastPose")
    Pose3d ipl$getLastPose();
}
