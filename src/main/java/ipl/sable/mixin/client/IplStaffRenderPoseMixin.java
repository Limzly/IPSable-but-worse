package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import ipl.sable.client.IplStraddleStaffPick;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Own every pose read used by staff held-item aim, lock marker, and client drag anchor.
 * Simulated uses separate render/client classes, so broad target coverage is intentional.
 */
@Pseudo
@Mixin(
    targets = {
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffRenderHandler",
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer",
        "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler"
    },
    remap = false
)
public abstract class IplStaffRenderPoseMixin {

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            remap = false
        ),
        require = 0
    )
    private static Pose3dc ipl$portalPose(ClientSubLevel sub, Operation<Pose3dc> original) {
        return IplStraddleStaffPick.mapStaffRenderPose(sub, original.call(sub));
    }

    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;",
            remap = false
        ),
        require = 0
    )
    private static Pose3dc ipl$portalPosePt(
        ClientSubLevel sub, float partialTick, Operation<Pose3dc> original
    ) {
        return IplStraddleStaffPick.mapStaffRenderPose(sub, original.call(sub, partialTick));
    }

    /** The server stores staff sessions in the hosting dimension; resolve their owner in portal passes too. */
    @WrapOperation(
        method = "onRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"
        ),
        require = 0
    )
    private static Player ipl$findBeamOwnerAcrossPortalWorlds(
        ClientLevel level, UUID uuid, Operation<Player> original
    ) {
        Player found = original.call(level, uuid);
        if (found != null) return found;
        for (ClientLevel world : qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds()) {
            found = world.getPlayerByUUID(uuid);
            if (found != null) return found;
        }
        return null;
    }

}
