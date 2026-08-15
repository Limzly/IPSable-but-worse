package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.client.IplStraddleRenderState;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Block hit-outline at the THROUGH-PORTAL position of a straddling ship.
 *
 * <p>Sable's outline mixin (priority 2000) transforms the outline by
 * {@code subLevel.renderPose()} — the unmapped pose, which for a foreign straddler draws
 * the outline at the source-frame position (~the portal offset away). This wrap sits
 * OUTSIDE Sable's (priority 2100; last-applied wraps are outermost in the MixinExtras
 * chain) and brackets the highlight call with {@link IplStraddleRenderState} for the
 * targeted ship — our existing {@code renderPose()} override then feeds Sable's own
 * outline math the portal-mapped pose.
 */
@Mixin(value = LevelRenderer.class, priority = 2100)
public abstract class IplStraddleOutlineMixin {

    @Shadow
    @Nullable
    private ClientLevel level;

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;onDrawHighlight(Lnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/Camera;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/client/DeltaTracker;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)Z",
            remap = false
        ),
        require = 0
    )
    private boolean ipl$mapOutlinePoseForForeignStraddler(
        LevelRenderer context, Camera camera, HitResult target, DeltaTracker deltaTracker,
        PoseStack poseStack, MultiBufferSource bufferSource, Operation<Boolean> original
    ) {
        if (this.level != null && target instanceof BlockHitResult blockTarget) {

            SubLevel owner = dev.ryanhcode.sable.Sable.HELPER.getContaining(
                this.level, blockTarget.getBlockPos());
            if (owner instanceof ClientSubLevel) {
                // Only THROUGH-half blocks render their outline at the mapped pose;
                // a source-half block's outline belongs at the ship's native pose
                // (same plot-space cut as pick/interaction — keep-filter truth).
                java.util.function.Predicate<net.minecraft.core.BlockPos> keep =
                    ipl.sable.transit.IplStraddlePoseMap.getSourceHalfKeepFilter(
                        owner, this.level);
                boolean throughHalf = keep != null && !keep.test(blockTarget.getBlockPos());
                if (!throughHalf) {
                    return original.call(context, camera, target, deltaTracker,
                        poseStack, bufferSource);
                }
                for (IplClientHostedLookup.StraddleProjection proj :
                        IplClientHostedLookup.getStraddleProjectionsInto(this.level)) {
                    if (proj.sub() == owner) {
                        IplStraddleRenderState.set(
                            proj.sub(), proj.mappedPose(), proj.destPlane(), proj.portal());
                        try {
                            return original.call(context, camera, target, deltaTracker,
                                poseStack, bufferSource);
                        } finally {
                            IplStraddleRenderState.clear();
                        }
                    }
                }
            }
        }
        return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
    }
}
