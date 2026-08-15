package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a straddling hosted sub-level's DEST-SIDE projection is being drawn
 * ({@link IplStraddleRenderState} active for it), every render-pose read returns the
 * portal-mapped pose — placing the same compiled plot geometry on the far side of the
 * portal. Both overloads are overridden: the no-arg variant has a cached fast-path that
 * does not delegate to the float variant.
 */
@Pseudo
@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class IplClientSubLevelPoseOverrideMixin {

    @ModifyReturnValue(method = "renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;",
        at = @At("RETURN"), require = 0)
    private Pose3dc ipl$overrideRenderPoseNoArg(Pose3dc original) {
        Pose3dc override = IplStraddleRenderState.getPoseFor(this);
        return override != null ? override : original;
    }

    @ModifyReturnValue(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
        at = @At("RETURN"), require = 0)
    private Pose3dc ipl$overrideRenderPoseFloat(Pose3dc original, float partialTick) {
        Pose3dc override = IplStraddleRenderState.getPoseFor(this);
        return override != null ? override : original;
    }
}
