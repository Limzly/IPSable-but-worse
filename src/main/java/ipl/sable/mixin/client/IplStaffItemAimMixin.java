package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.SimulatedClient;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import ipl.sable.client.IplStaffBeamRoutes;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Aim the held Creative Physics Staff where its BEAM goes, not at the raw native joint.
 *
 * <p>Stock computes the barrel aim from {@code renderPose().transformPosition(dragLocalAnchor)}
 * — the joint's native world position. When the joint is reached through a portal (a
 * cross-dimension grab, or a same-dimension construction held inside the portal) that position
 * is in the wrong place/dimension, so the staff points at nothing. We redirect that single
 * aim query to the beam's first endpoint (the entrance aperture, or the joint for a direct
 * grab), so the staff looks into the portal exactly where the beam leaves it.
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItemRenderer",
    remap = false
)
public abstract class IplStaffItemAimMixin {

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/companion/math/Pose3dc;transformPosition(Lorg/joml/Vector3d;)Lorg/joml/Vector3d;",
            remap = false
        ),
        require = 0
    )
    private Vector3d ipl$aimAlongBeam(Pose3dc pose, Vector3d local, Operation<Vector3d> original) {
        PhysicsStaffClientHandler handler = SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        PhysicsStaffClientHandler.ClientDragSession session = handler.getDragSession();
        LocalPlayer player = Minecraft.getInstance().player;
        if (session != null && player != null
            && session.dragSubLevel() instanceof ClientSubLevel sub) {
            Vector3dc anchor = session.dragLocalAnchor();
            Vec3 aim = IplStaffBeamRoutes.staffAimPoint(
                player, sub, new Vec3(anchor.x(), anchor.y(), anchor.z()),
                AnimationTickHolder.getPartialTicks()
            );
            // Item renderer normalizes this target into a barrel direction. Keep its
            // target in the beam's visible frame, including an aperture endpoint.
            if (aim != null) {
                if (aim.distanceToSqr(player.getEyePosition(AnimationTickHolder.getPartialTicks())) > 1.0e-8) {
                    return new Vector3d(aim.x, aim.y, aim.z);
                }
            }
        }
        return original.call(pose, local);
    }
}
