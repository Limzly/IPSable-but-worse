package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(value = ChunkRenderList.class, remap = false)
public class MixinSodiumChunkRenderList {
    @Inject(method = "reset", at = @At("TAIL"))
    private void ip_clearPortalCachedBatches(int frame, boolean sorted, CallbackInfo ci) {
        if (!PortalRendering.isRendering()) return;
        ChunkRenderList self = (ChunkRenderList) (Object) this;
        RenderRegion region = self.getRegion();
        ((IESodiumRenderRegion) region).ip_clearPortalCachedBatchesForList(self);
    }
}
