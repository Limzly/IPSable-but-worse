package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Compile hosted sub-levels' section geometry against the {@code ipl_sable:sublevels}
 * {@code ClientLevel}.
 *
 * <p>Sable's vanilla render backend builds each sub-level's render data with the section
 * dispatcher of {@code Minecraft.levelRenderer} — i.e. whatever dimension the player happens
 * to be in. A hosted sub-level's plot chunks live only in the sublevels client world, so its
 * sections must be compiled by THAT world's renderer (each IP secondary world renderer owns a
 * {@code SectionRenderDispatcher} bound to its own level — the multi-dispatcher pattern
 * already used by {@code MyGameRenderer}/{@code MyRenderHelper}).
 */
@Pseudo
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class IplHostedRenderDataMixin {

    @WrapOperation(
        method = "createRenderData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getSectionRenderDispatcher()Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;"
        ),
        require = 0
    )
    private SectionRenderDispatcher ipl$useHostingWorldDispatcher(
        LevelRenderer cameraWorldRenderer, Operation<SectionRenderDispatcher> original,
        @Local(argsOnly = true) ClientSubLevel subLevel
    ) {
        if (IplDimAgnostic.isHostingLevel(subLevel.getLevel())) {
            org.slf4j.LoggerFactory.getLogger("ipl-hosted-gather").info(
                "[IPL-HOSTED-RENDERDATA] sub-level {} render data created with sublevels-dim section dispatcher",
                subLevel.getUniqueId());
            return ClientWorldLoader
                .getWorldRenderer(SableSubLevelDimension.SUBLEVELS)
                .getSectionRenderDispatcher();
        }
        return original.call(cameraWorldRenderer);
    }
}
