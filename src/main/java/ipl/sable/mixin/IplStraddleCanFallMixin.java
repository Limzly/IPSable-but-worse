package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinhelpers.CanFallAtleastHelper;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.transit.IplStraddlePoseMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Predicate;

/**
 * Frame-correct sneak-edge ground probing on straddling ships.
 *
 * <p>{@code CanFallAtleastHelper.canFallAtleastWithSubLevels} backs Sable's
 * shift-walk edge detection ({@code PlayerMixin.maybeBackOffFromEdge} and the
 * {@code canFallAtLeast} noCollision redirect). It transforms the ground-probe box
 * by {@code subLevel.lastPose()} — the UNMAPPED pose. For a rider standing on the
 * THROUGH-part (the portal-mapped frame), every probe inverse-transforms into the
 * wrong plot region, finds air, and concludes "would slide off" in every direction —
 * crouching froze the player completely. Same fix as
 * {@link IplStraddleCollisionPoseMixin}, at this helper's own pose read: substitute
 * the portal-mapped pose when the collision mapping is active for the probe box.
 *
 * <p>The candidate-block iterable also gets the straddle keep-filter (same authority
 * as {@link IplStraddleBlockClipMixin}): the culled half's blocks must not count as
 * ground, or sneaking could walk off onto phantom deck at the seam.
 */
@Pseudo
@Mixin(value = CanFallAtleastHelper.class, remap = false)
public abstract class IplStraddleCanFallMixin {

    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"
        ),
        require = 0
    )
    private static Pose3dc ipl$mapProbePoseForStraddler(
        SubLevel sub, Operation<Pose3dc> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) AABB aabb
    ) {
        Pose3dc pose = original.call(sub);
        IplStraddlePoseMap.StraddleMapping mapping =
            IplStraddlePoseMap.getCollisionMappingInto(sub, level, aabb);
        return mapping != null ? mapping.mapPose(pose) : pose;
    }

    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"
        ),
        require = 0
    )
    private static Iterable<BlockPos> ipl$dropWrongHalfGround(
        BlockPos min, BlockPos max, Operation<Iterable<BlockPos>> original,
        @Local(argsOnly = true) Level level, @Local(argsOnly = true) AABB aabb,
        @Local SubLevel subLevel
    ) {
        Iterable<BlockPos> all = original.call(min, max);
        Predicate<BlockPos> keep =
            IplStraddlePoseMap.getBlockCollisionKeepFilter(subLevel, level, aabb);
        if (keep == null) return all;
        return IplStraddlePoseMap.filterBlocks(all, keep);
    }
}
