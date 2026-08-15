package ipl.sable.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import ipl.sable.dim.IplDimAgnostic;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.client.Camera;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * TEMPORARY diagnostic for the dim-agnostic bring-up: for HOSTED sub-levels only, log what
 * the vanilla render data sees at compile time (dirty/total sections) and at draw time
 * (sections with non-empty compiled geometry for the layer). Remove once hosted rendering
 * is stable.
 */
@Pseudo
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class IplRenderDataProbeMixin {

    @Shadow @Final private ClientSubLevel subLevel;
    @Shadow @Final private ObjectList<SectionRenderDispatcher.RenderSection> allRenderSections;
    @Shadow @Final private ObjectList<SectionRenderDispatcher.RenderSection> dirtyRenderSections;

    @Unique private static long ipl$lastCompileLogMs = 0;
    @Unique private static long ipl$lastDrawLogMs = 0;
    @Unique private static long ipl$lastSetDirtyLogMs = 0;

    @Unique
    private boolean ipl$isHosted() {
        return IplDimAgnostic.isHostingLevel(this.subLevel.getLevel());
    }

    @Inject(method = "compileSections", at = @At("HEAD"), require = 0)
    private void ipl$probeCompile(
        PrioritizeChunkUpdates chunkUpdates, RenderRegionCache renderRegionCache, Camera camera, CallbackInfo ci
    ) {
        if (!ipl$isHosted()) return;
        long now = System.currentTimeMillis();
        if (now - ipl$lastCompileLogMs > 5000) {
            ipl$lastCompileLogMs = now;
            org.slf4j.LoggerFactory.getLogger("ipl-render-probe").info(
                "[IPL-RENDER-PROBE] compile uuid={} dirty={} all={}",
                this.subLevel.getUniqueId(), dirtyRenderSections.size(), allRenderSections.size());
        }
    }

    /**
     * FIX (dim-agnostic): compile hosted sections SYNCHRONOUSLY. The hosted sections get
     * re-dirtied every frame (source under investigation via the setDirty probe below), and
     * Sable's per-frame reschedule makes vanilla cancel the previous pending async task each
     * time — so the background compile never completes and sections stay UNCOMPILED forever.
     * The sync path compiles on the render thread immediately, which both proves the
     * cancellation theory and makes hosted ships render. Hosted plots are small (airships),
     * so per-frame sync compile cost is acceptable until the re-dirty churn is fixed.
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "compileSections",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;rebuildSectionAsync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
        ),
        require = 0
    )
    private void ipl$forceSyncCompileForHosted(
        SectionRenderDispatcher.RenderSection section,
        SectionRenderDispatcher dispatcher,
        RenderRegionCache regionCache,
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original
    ) {
        if (ipl$isHosted()) {
            dispatcher.rebuildSectionSync(section, regionCache);
        } else {
            original.call(section, dispatcher, regionCache);
        }
    }

    @Inject(method = "setDirty", at = @At("HEAD"), require = 0)
    private void ipl$probeSetDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        if (!ipl$isHosted()) return;
        long now = System.currentTimeMillis();
        if (now - ipl$lastSetDirtyLogMs < 5000) return;
        ipl$lastSetDirtyLogMs = now;
        org.slf4j.LoggerFactory.getLogger("ipl-render-probe").info(
            "[IPL-RENDER-PROBE] setDirty uuid={} section=({},{},{}) playerChanged={} — caller:",
            this.subLevel.getUniqueId(), x, y, z, playerChanged,
            new Throwable("re-dirty source"));
    }

    @Inject(method = "renderChunkedSubLevel", at = @At("HEAD"), require = 0)
    private void ipl$probeDraw(
        RenderType layer, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ, CallbackInfo ci
    ) {
        if (!ipl$isHosted()) return;
        long now = System.currentTimeMillis();
        if (now - ipl$lastDrawLogMs < 5000) return;
        ipl$lastDrawLogMs = now;

        int nonEmpty = 0;
        int uncompiled = 0;
        for (SectionRenderDispatcher.RenderSection section : allRenderSections) {
            SectionRenderDispatcher.CompiledSection compiled = section.getCompiled();
            if (compiled == SectionRenderDispatcher.CompiledSection.UNCOMPILED) uncompiled++;
            else if (!compiled.isEmpty(layer)) nonEmpty++;
        }
        org.slf4j.LoggerFactory.getLogger("ipl-render-probe").info(
            "[IPL-RENDER-PROBE] draw uuid={} layer={} nonEmpty={} uncompiled={} all={}",
            this.subLevel.getUniqueId(), layer, nonEmpty, uncompiled, allRenderSections.size());
    }
}
