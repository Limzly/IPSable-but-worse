package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler$PhysicsBeam", remap = false)
public interface IplPhysicsStaffBeamInvokerMixin {
    @Invoker(value = "render", remap = false)
    void ipl$render(
        Vec3 start, Vec3 end, PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    );
}
