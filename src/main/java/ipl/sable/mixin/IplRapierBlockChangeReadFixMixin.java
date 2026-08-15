package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import ipl.sable.transit.IplTerrainReadOverride;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Complete the terrain-read override coverage for {@code RapierPhysicsPipeline.handleBlockChange}.
 *
 * <p>The changed block's own voxel bake reads through the pipeline's {@code LevelAccelerator}
 * (covered by {@code IplLevelAcceleratorOverrideMixin}), but the SIX NEIGHBOR voxels' collider
 * data is read via {@code this.level.getBlockState(pos)} — a direct hosting-level read. When a
 * parent-dim block change is forwarded into the hosting pipeline, those neighbor reads hit the
 * hosting dim's void and bake EMPTY colliders over six real terrain voxels per change. Digging
 * a few blocks under a ship hollowed out its whole support region — the
 * locked-ship-falls-through-the-world bug.
 */
@Pseudo
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class IplRapierBlockChangeReadFixMixin {

    @WrapOperation(
        method = "handleBlockChange",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"
        ),
        require = 1
    )
    private BlockState ipl$readNeighborStateFromOverride(
        ServerLevel level, BlockPos pos, Operation<BlockState> original
    ) {
        Level override = IplTerrainReadOverride.get();
        if (override == null) {
            return original.call(level, pos);
        }
        return override.getBlockState(pos);
    }
}
