package ipl.sable.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Exposes active sessions only for atomic portal-frame handoff. */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler", remap = false)
public interface IplStaffServerHandlerAccessorMixin {
    @Accessor(value = "draggingSessions", remap = false)
    Map<UUID, Object> ipl$getDraggingSessions();
}
