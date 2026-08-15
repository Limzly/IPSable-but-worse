package ipl.sable.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Accesses Simulated's private rotate-mode gate without duplicating its handler state. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public interface IplPhysicsStaffClientHandlerStateAccessorMixin {
    @Invoker(value = "isRotating", remap = false)
    boolean ipl$isRotating();
}
