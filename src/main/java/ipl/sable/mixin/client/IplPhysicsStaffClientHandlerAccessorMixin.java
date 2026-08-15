package ipl.sable.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler", remap = false)
public interface IplPhysicsStaffClientHandlerAccessorMixin {
    @Accessor(value = "beams", remap = false)
    Object2ObjectMap<UUID, Object> ipl$getBeams();
}
