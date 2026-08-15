package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code LevelRenderer.renderEntity} (private) for the hosted plot-entity render
 * pass ({@link IplHostedPlotEntityRenderMixin}). Dispatching through the real method keeps
 * Sable's own {@code LevelRendererMixin.renderEntityOnSubLevel} hook in the path — it is
 * what transforms a plot-space entity's coordinates through its sub-level render pose.
 */
@Mixin(LevelRenderer.class)
public interface IplLevelRendererEntityInvoker {

    @Invoker("renderEntity")
    void ipl$renderEntity(
        Entity entity, double camX, double camY, double camZ,
        float partialTick, PoseStack poseStack, MultiBufferSource bufferSource
    );
}
