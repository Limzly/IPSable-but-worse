package ipl.sable.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Calls Simulated's normal release path, including its STOP_DRAG packet. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public interface IplPhysicsStaffClientHandlerControlMixin {

    @Invoker(value = "stopDragging", remap = false)
    void ipl$stopDragging();
}
