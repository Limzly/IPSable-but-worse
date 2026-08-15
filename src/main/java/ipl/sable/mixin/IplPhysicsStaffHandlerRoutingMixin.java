package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** All server-side staff state follows hosted bodies, not player-visible dimensions. */
@Pseudo
@Mixin(
    targets = {
        "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffActionPacket",
        "dev.simulated_team.simulated.network.packets.physics_staff.PhysicsStaffDragPacket"
    },
    remap = false
)
public abstract class IplPhysicsStaffHandlerRoutingMixin {

    @WrapOperation(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;get(Lnet/minecraft/server/level/ServerLevel;)Ldev/simulated_team/simulated/content/physics_staff/PhysicsStaffServerHandler;",
            remap = false
        ),
        require = 0
    )
    private dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler ipl$forceHostingState(
        ServerLevel playerLevel,
        Operation<dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler> original
    ) {
        ServerLevel hosting = playerLevel.getServer() == null ? null
            : SableSubLevelDimension.getSableSubLevelsOrNull(playerLevel.getServer());
        return original.call(hosting == null ? playerLevel : hosting);
    }
}
