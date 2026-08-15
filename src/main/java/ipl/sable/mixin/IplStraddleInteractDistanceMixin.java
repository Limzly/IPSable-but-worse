package ipl.sable.mixin;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Interaction distance for the THROUGH-PORTAL part of straddling ships.
 *
 * <p>Sable's interaction-distance fix ({@code interaction_distance.PlayerMixin}) measures
 * plot-position interactions by inverse-transforming the player's eye with the sub-level's
 * UNMAPPED pose — wrong frame for a foreign straddler, so server-side validation rejects
 * breaks/placements on the through-part as "too far" (silently; the client pick was
 * correct). This adds the mapped-frame check. Composition is safe in ANY callback order:
 * both Sable's handler and this one only cancel on SUCCESS, falling through otherwise.
 *
 * <p>Runs on both sides: the server validates real interactions; the client gates swing
 * prediction.
 */
@Mixin(value = Player.class, priority = 1200)
public abstract class IplStraddleInteractDistanceMixin {

    @Shadow
    public abstract double blockInteractionRange();

    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$canInteractWithMappedBlock(
        BlockPos pos, double slop, CallbackInfoReturnable<Boolean> cir
    ) {
        Player self = (Player) (Object) this;
        SubLevel owner = dev.ryanhcode.sable.Sable.HELPER.getContaining(self.level(), pos);
        if (owner == null) return;

        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            owner, self.level(), self.getBoundingBox());
        if (mapping == null) return; // native frame — Sable's own handler covers it

        Pose3d mapped = mapping.mapPose(owner.logicalPose());
        Vec3 eyeLocal = mapped.transformPositionInverse(self.getEyePosition());
        double range = this.blockInteractionRange() + slop;
        if (new AABB(pos).distanceToSqr(eyeLocal) < range * range) {
            cir.setReturnValue(true);
        }
    }
}
