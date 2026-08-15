package ipl.sable.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.duck.IplSubLevelDuck;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HOSTED PLOT-ENTITY RENDER PASS — draw entities living in hosting-dimension plot space
 * (item frames, seats, stuck plunger projectiles) embedded on their ship, in the
 * dimension the ship occupies.
 *
 * <p>Stock Sable renders plot-space entities through the ordinary level entity loop —
 * they live in the SAME level as the viewer, and {@code LevelRendererMixin
 * .renderEntityOnSubLevel} + {@code EntityRenderDispatcherMixin} transform their plot
 * coordinates through the containing sub-level's render pose. Hosted, those entities live
 * in the hosting {@code ClientLevel}, which no vanilla entity loop ever iterates — so a
 * synced hosted entity still never rendered.
 *
 * <p>This pass iterates the hosting client level's entities during the PARENT level's
 * render (the same phase the hosted BE pass uses), filters to entities whose plot's
 * sub-level is parented to the level being rendered, and dispatches them through the real
 * {@code LevelRenderer.renderEntity} — Sable's own transform hooks then place them on the
 * ship exactly like stock. Light is sampled by Sable's entity render mixins from the
 * entity's own (hosting) level at plot coordinates — the plot-local light, as stock.
 */
@Mixin(LevelRenderer.class)
public abstract class IplHostedPlotEntityRenderMixin {

    @Shadow
    private @Nullable ClientLevel level;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Inject(
        method = "renderLevel",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;globalBlockEntities:Ljava/util/Set;",
            shift = At.Shift.BEFORE,
            ordinal = 0
        )
    )
    private void ipl$renderHostedPlotEntities(
        DeltaTracker deltaTracker, boolean bl, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelView, Matrix4f projection, CallbackInfo ci
    ) {
        ClientLevel hosting = IplClientHostedLookup.getHostingClientLevelOrNull();
        if (hosting == null || hosting == this.level || this.level == null) {
            return; // no hosting world, or we ARE rendering the hosting dim
        }
        SubLevelContainer container = SubLevelContainer.getContainer(hosting);
        if (container == null) return;

        Vec3 cameraPosition = camera.getPosition();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = new PoseStack();
        var bufferSource = this.renderBuffers.bufferSource();

        for (Entity entity : hosting.entitiesForRendering()) {
            if (entity instanceof Player) continue;
            if (entity.isRemoved()) continue;

            LevelPlot plot = container.getPlot(
                SectionPos.blockToSectionCoord(entity.getBlockX()),
                SectionPos.blockToSectionCoord(entity.getBlockZ()));
            if (plot == null) continue;
            SubLevel sub = plot.getSubLevel();
            if (sub == null || sub.isRemoved()) continue;
            if (((IplSubLevelDuck) sub).ipl$getParentLevel() != this.level) {
                continue; // ship parented to a different dimension's render pass
            }

            ((IplLevelRendererEntityInvoker) this).ipl$renderEntity(
                entity, cameraPosition.x, cameraPosition.y, cameraPosition.z,
                partialTick, poseStack, bufferSource);
        }
    }
}
