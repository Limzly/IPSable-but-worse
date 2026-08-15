package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Frame-correct {@code getInBlockState} for riders of straddling ships.
 *
 * <p>Sable's {@code entity_sublevel_collision.EntityMixin} (priority 1100) overwrites
 * {@code Entity.getInBlockState} to sample intersecting sub-levels via
 * {@code logicalPose().transformPositionInverse(position)}. For an entity on the
 * THROUGH-PORTAL part of a straddling ship, the unmapped inverse transform interprets the
 * entity's dest-dim position in the source frame — off by the portal offset — sampling the
 * wrong plot block. {@code getInBlockState} feeds {@code onClimbable} (ladder physics:
 * capped ascent + slowed fall) and other in-block behaviors, so a wrong sample produces
 * phantom movement semantics.
 *
 * <p>Priority 1200 so this applies AFTER Sable's overwrite and wraps the pose read inside
 * the overwritten body. Native-frame ships map to a null offset and pass through unchanged.
 */
@Mixin(value = Entity.class, priority = 1200)
public abstract class IplStraddleInBlockPoseMixin {

    /**
     * WILDCARD wrap: every {@code SubLevel.logicalPose()} read in ANY method of Entity —
     * crucially including Sable's MERGED mixin handlers ({@code sable$preGetOnPos}, the
     * {@code getInBlockState} overwrite body, sprint particles, ...). Their mangled handler
     * names cannot be targeted directly, and adding our own callbacks to the same methods
     * proved futile: Sable's cancellable injects run first regardless of our mixin priority
     * and cancel before our code executes. Wrapping the INVOKE sidesteps ordering entirely —
     * Sable's own code computes with the portal-mapped pose for foreign straddlers, fixing
     * getOnPos (travel's "unloaded chunk below" -0.098 velocity pin, ground friction, jump
     * factor) and getInBlockState (climbable semantics) in one place.
     *
     * <p>Vanilla Entity methods never reference Sable types, so this only matches merged
     * Sable handler code. Native-frame ships map to a null offset and pass through.
     */
    @WrapOperation(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;",
            remap = false
        ),
        require = 0
    )
    private Pose3d ipl$mapEntityContextPose(SubLevel sub, Operation<Pose3d> original) {
        Pose3d pose = original.call(sub);
        Entity entity = (Entity) (Object) this;
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            sub, entity.level(), entity.getBoundingBox());
        return mapping != null ? mapping.mapPose(pose) : pose;
    }
}
