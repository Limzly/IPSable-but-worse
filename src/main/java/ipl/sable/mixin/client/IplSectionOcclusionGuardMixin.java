package ipl.sable.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Guard the section-compiled occlusion notification against an uninitialized graph.
 *
 * <p>IP's secondary-world renderers (the {@code ipl_sable:sublevels} hosting dimension
 * and other non-active client worlds) receive section lifecycle traffic BEFORE their
 * first render pass: on (re)join, a hosted sub-level's finalize packet triggers
 * {@code ClientSubLevel.updateRenderData}, whose {@code close()} of the previous render
 * data finalizes sections — and {@code SectionOcclusionGraph.onSectionCompiled}
 * dereferences update state that only exists after the renderer's first occlusion
 * update. Result: the long-standing removal/rejoin NPE ("Cannot read field events
 * because AtomicReference.get() is null"), disconnecting the client.
 *
 * <p>The notification is a best-effort refresh hint: a renderer whose graph state is
 * null computes the FULL occlusion graph on its first real render anyway, so dropping
 * the hint for that window is precisely the behavior it would have had. Guarded with a
 * scoped catch rather than a field peek — the null lives in a private atomic whose
 * initialization timing is vanilla's business, and any failure mode here reduces to
 * "skip the hint".
 */
@Mixin(LevelRenderer.class)
public abstract class IplSectionOcclusionGuardMixin {

    @WrapOperation(
        method = "addRecentlyCompiledSection",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;onSectionCompiled(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;)V"
        ),
        require = 0
    )
    private void ipl$guardUninitializedGraph(
        SectionOcclusionGraph graph, SectionRenderDispatcher.RenderSection section,
        Operation<Void> original
    ) {
        try {
            original.call(graph, section);
        } catch (NullPointerException e) {
            // Pre-first-render notification on a secondary-world renderer: the graph
            // will be built whole on first render; the hint is safely droppable.
        }
    }
}
