package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.atlas.IplFusedStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Atlas M1 (spec v3 §2.7): gate the native world step. The fused driver runs every
 * pipeline's {@code physicsTick} per substep so each pipeline keeps its
 * contraption-pose and wake-up housekeeping, but only the ARMED call (the last
 * pipeline in the driver's iteration) actually steps the shared world — one native
 * step per substep, after all charts' kinematic poses are pushed.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class IplRapierStepArmMixin {

    @WrapOperation(
        method = "physicsTick",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"
        ),
        remap = false,
        require = 0
    )
    private void ipl$armWorldStep(long sceneHandle, double timeStep, Operation<Void> original) {
        if (IplFusedStep.STEP_ARMED) {
            original.call(sceneHandle, timeStep);
        }
    }
}
