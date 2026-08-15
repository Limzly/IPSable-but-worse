package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplHostingTeleportGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/**
 * Player teleports never land in the hosting void — see
 * {@link IplHostingTeleportGuard}. Both teleport entry points are wrapped: the
 * relative-movement variant is the one teleport mods (Waystones via Balm) and vanilla
 * {@code /tp} use; the yaw/pitch variant covers spread/utility paths.
 */
@Mixin(ServerPlayer.class)
public abstract class IplHostingTeleportGuardMixin {

    @WrapMethod(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        require = 0)
    private boolean ipl$guardRelativeTeleport(
        ServerLevel level, double x, double y, double z,
        Set<RelativeMovement> movements, float yaw, float pitch, Operation<Boolean> original
    ) {
        return original.call(
            IplHostingTeleportGuard.redirect(level, x, y, z),
            x, y, z, movements, yaw, pitch);
    }

    @WrapMethod(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
        require = 0)
    private void ipl$guardAbsoluteTeleport(
        ServerLevel level, double x, double y, double z, float yaw, float pitch,
        Operation<Void> original
    ) {
        original.call(
            IplHostingTeleportGuard.redirect(level, x, y, z),
            x, y, z, yaw, pitch);
    }
}
