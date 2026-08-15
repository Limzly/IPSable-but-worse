package qouteall.imm_ptl.core.mixin.common.miscellaneous;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.q_misc_util.Helper;

import java.util.function.BiFunction;
import java.util.function.Function;

@Mixin(BlockGetter.class)
public interface MixinBlockGetter {
        
        // avoid lagging due to long block traversal
        @ModifyVariable(
                method = "traverseBlocks",
                at = @At("HEAD"),
                argsOnly = true,
                index = 1
        )
        private static <T, C> Vec3 onTraverseBlocks(
                Vec3 originalArgument,
                Vec3 from, Vec3 _to, C context,
                BiFunction<C, BlockPos, T> tester, Function<C, T> onFail
        ) {
                if (from.distanceToSqr(_to) > (512 * 512)) {
                        // Many mods (Sound Physics Remastered, Sound Physics Perfected, etc.)
                        // raycast across portal boundaries for occlusion calculations. The
                        // 512-block clamp is correct behavior — it prevents the voxel
                        // traversal from walking the entire world — but the full stack
                        // trace logged by LimitedLogger is just noise in modpacks with
                        // sound mods. Log a one-line warning instead.
                        IPMcHelper.limitedLogger.invoke(() -> {
                                Helper.LOGGER.warn(
                                        "Immersive Portals: clamped a long block traversal from {} to {} "
                                                + "(likely a modded raycast crossing a portal boundary). "
                                                + "Clamped to 30 blocks to prevent lag.",
                                        from, _to
                                );
                        });
                        return _to.subtract(from).normalize().scale(30).add(from);
                }
                return _to;
        }
        
}
