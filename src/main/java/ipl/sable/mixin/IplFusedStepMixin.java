package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.atlas.IplFusedStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Atlas M1 (spec v3 §2.7): replace per-level physics stepping with the fused step.
 * Under the atlas natives all dimensions share ONE Rapier world; the stock
 * per-level {@code tickPipelinePhysics} would step it N times per tick. The first
 * system per server tick drives ALL systems' phases via {@link IplFusedStep};
 * every call is cancelled here.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public abstract class IplFusedStepMixin {

    @Inject(method = "tickPipelinePhysics", at = @At("HEAD"), cancellable = true, remap = false)
    private void ipl$fuseStep(CallbackInfo ci) {
        IplFusedStep.onTickPipelinePhysics((SubLevelPhysicsSystem) (Object) this);
        ci.cancel();
    }
}
