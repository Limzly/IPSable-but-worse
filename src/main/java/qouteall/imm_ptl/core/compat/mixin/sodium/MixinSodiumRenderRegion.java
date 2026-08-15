package qouteall.imm_ptl.core.compat.mixin.sodium;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IESodiumRenderRegion;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.Helper;

import java.util.Map;

@Mixin(value = RenderRegion.class, remap = false)
public class MixinSodiumRenderRegion implements IESodiumRenderRegion {
    @Shadow
    @Final
    private ChunkRenderList renderList;

    @Shadow
    @Final
    private Map<TerrainRenderPass, MultiDrawBatch> cachedBatches;
    
    @Unique
    private @Nullable ObjectArrayList<ChunkRenderList> chunkRenderListsForPortalRendering = null;

    @Unique
    private @Nullable ObjectArrayList<Map<TerrainRenderPass, MultiDrawBatch>> portalCachedBatches = null;
    
    /**
     * @author qouteall
     * @reason With ImmPtl, the world rendering process is as follows:
     * 1. render solid things
     * 2. render portal recursively (will increase frame counter)
     * 3. render transparent things
     * When rendering the world in portal (to-same-world portal),
     * the frame counter increases, then in
     * {@link SortedRenderLists.Builder#add(RenderSection)} it will reset the ChunkRenderList,
     * which makes upcoming transparent block rendering in outer world to break.
     * So use separate ChunkRenderList for each portal rendering layer.
     */
    @Overwrite
    public ChunkRenderList getRenderList() {
        if (!PortalRendering.isRendering()) {
            return renderList;
        }
        
        RenderRegion this_ = (RenderRegion) (Object) this;
        
        if (chunkRenderListsForPortalRendering == null) {
            chunkRenderListsForPortalRendering = new ObjectArrayList<>();
        }
        
        int layer = PortalRendering.getPortalLayer();
        int index = layer - 1;
        ChunkRenderList result = Helper.arrayListComputeIfAbsent(
            chunkRenderListsForPortalRendering,
            index,
            () -> new ChunkRenderList(this_)
        );
        
        return result;
    }

    /**
     * @author qouteall
     * @reason Sodium stores command batches on shared regions, while IP stores visible lists
     * per portal depth. A command batch must use the same context as its visible list.
     */
    @Overwrite
    public MultiDrawBatch getCachedBatch(TerrainRenderPass pass) {
        Map<TerrainRenderPass, MultiDrawBatch> batches = ip_getCachedBatches();
        MultiDrawBatch batch = batches.get(pass);
        if (batch == null) {
            batch = new MultiDrawBatch(ModelQuadFacing.COUNT * 256 + 1);
            batches.put(pass, batch);
        }
        return batch;
    }

    @Inject(method = "clearAllCachedBatches", at = @At("HEAD"))
    private void ip_clearPortalCachedBatches(CallbackInfo ci) {
        if (portalCachedBatches == null) return;
        for (Map<TerrainRenderPass, MultiDrawBatch> batches : portalCachedBatches) {
            if (batches == null) continue;
            for (MultiDrawBatch batch : batches.values()) batch.clear();
        }
    }

    @Inject(method = "clearCachedBatchFor", at = @At("HEAD"))
    private void ip_clearPortalCachedBatchFor(TerrainRenderPass pass, CallbackInfo ci) {
        if (portalCachedBatches == null) return;
        for (Map<TerrainRenderPass, MultiDrawBatch> batches : portalCachedBatches) {
            if (batches == null) continue;
            MultiDrawBatch batch = batches.get(pass);
            if (batch != null) batch.clear();
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void ip_deletePortalCachedBatches(CallbackInfo ci) {
        if (portalCachedBatches == null) return;
        for (Map<TerrainRenderPass, MultiDrawBatch> batches : portalCachedBatches) {
            if (batches == null) continue;
            for (MultiDrawBatch batch : batches.values()) batch.delete();
        }
        portalCachedBatches.clear();
    }

    @Unique
    private Map<TerrainRenderPass, MultiDrawBatch> ip_getCachedBatches() {
        if (!PortalRendering.isRendering()) return cachedBatches;
        if (portalCachedBatches == null) portalCachedBatches = new ObjectArrayList<>();
        return Helper.arrayListComputeIfAbsent(
            portalCachedBatches,
            PortalRendering.getPortalLayer() - 1,
            Reference2ReferenceOpenHashMap::new
        );
    }

    @Override
    public void ip_clearPortalCachedBatchesForList(Object list) {
        if (portalCachedBatches == null || chunkRenderListsForPortalRendering == null) return;
        int index = -1;
        for (int i = 0; i < chunkRenderListsForPortalRendering.size(); i++) {
            if (chunkRenderListsForPortalRendering.get(i) == list) {
                index = i;
                break;
            }
        }
        if (index < 0 || index >= portalCachedBatches.size()) return;
        Map<TerrainRenderPass, MultiDrawBatch> batches = portalCachedBatches.get(index);
        if (batches == null) return;
        for (MultiDrawBatch batch : batches.values()) batch.clear();
    }
}
