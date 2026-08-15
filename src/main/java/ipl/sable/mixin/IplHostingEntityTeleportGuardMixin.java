package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplHostingTeleportGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/**
 * Non-player entity teleports (warp plates, command teleports of mobs/items) get the
 * same hosting-void redirect as players — see {@link IplHostingTeleportGuard}.
 * {@code ServerPlayer} overrides this method; its own wrap covers the player path.
 */
@Mixin(Entity.class)
public abstract class IplHostingEntityTeleportGuardMixin {

    @WrapMethod(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        require = 0)
    private boolean ipl$guardEntityTeleport(
        ServerLevel level, double x, double y, double z,
        Set<RelativeMovement> movements, float yaw, float pitch, Operation<Boolean> original
    ) {
        return original.call(
            IplHostingTeleportGuard.redirect(level, x, y, z),
            x, y, z, movements, yaw, pitch);
    }
}
