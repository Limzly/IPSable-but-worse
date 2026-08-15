package ipl.sable.mixin.client;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam", remap = false)
public interface IplPhysicsStaffBeamAccessorMixin {
    @Accessor(value = "previousStart", remap = false)
    Vec3 ipl$getPreviousStart();

    @Accessor(value = "start", remap = false)
    Vec3 ipl$getStart();

    @Accessor(value = "previousEnd", remap = false)
    Vec3 ipl$getPreviousEnd();

    @Accessor(value = "end", remap = false)
    Vec3 ipl$getEnd();
}
