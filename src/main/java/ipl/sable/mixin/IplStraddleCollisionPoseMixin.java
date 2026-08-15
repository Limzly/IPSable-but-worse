package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Entity collision against the THROUGH-PORTAL portion of a straddling hosted sub-level.
 *
 * <p>{@code SubLevelEntityCollision.collide} gathers candidates via
 * {@code Sable.HELPER.getAllIntersecting} (which now includes foreign straddlers with
 * portal-MAPPED bounds — see {@code IplHostedIntersectionMixin}) and then reads
 * {@code logicalPose()}/{@code lastPose()} directly. For a ship whose parent is ANOTHER
 * dimension straddling into the entity's dimension, those poses are in the wrong frame —
 * this wrap substitutes the portal-mapped poses, so an entity on the dest side stands on /
 * collides with the through-part exactly where it renders. Ship-block reads at plot-grid
 * coordinates work from any dimension via the plot bridge.
 *
 * <p>Native-frame ships get a null offset and pass through unchanged. When the flip fires,
 * the parent becomes the entity's dimension, the offset vanishes, and the handoff is
 * seamless.
 */
@Pseudo
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class IplStraddleCollisionPoseMixin {

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;"
        ),
        require = 0
    )
    private static Pose3d ipl$mapLogicalPoseForForeignStraddler(
        SubLevel sub, Operation<Pose3d> original,
        @Local(argsOnly = true) Entity entity
    ) {
        Pose3d pose = original.call(sub);
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            sub, entity.level(), entity.getBoundingBox());
        return mapping != null ? mapping.mapPose(pose) : pose;
    }

    @WrapOperation(
        method = "collide",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        ),
        require = 0
    )
    private static Pose3dc ipl$mapLastPoseForForeignStraddler(
        SubLevel sub, Operation<Pose3dc> original,
        @Local(argsOnly = true) Entity entity
    ) {
        Pose3dc pose = original.call(sub);
        IplStraddlePoseMap.StraddleMapping mapping = IplStraddlePoseMap.getCollisionMappingInto(
            sub, entity.level(), entity.getBoundingBox());
        return mapping != null ? mapping.mapPose(pose) : pose;
    }

}
