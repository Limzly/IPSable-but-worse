package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import ipl.sable.SableBridge;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.chunk_loading.PlayerChunkLoading;
import qouteall.imm_ptl.core.ducks.IEChunkMap;

import java.util.List;

@Mixin(value = ChunkMap.class, priority = 1100)
public abstract class MixinChunkMap_C implements IEChunkMap {
    
    @Shadow
    @Final
    private ServerLevel level;
    
    @Shadow
    protected abstract ChunkHolder getVisibleChunkIfPresent(long long_1);
    
    @Shadow
    @Final
    private ThreadedLevelLightEngine lightEngine;
    
    @Shadow
    abstract int getPlayerViewDistance(ServerPlayer serverPlayer);
    
    @Override
    public int ip_getPlayerViewDistance(ServerPlayer player) {
        return getPlayerViewDistance(player);
    }
    
    @Override
    public ServerLevel ip_getWorld() {
        return level;
    }
    
    @Override
    public ThreadedLevelLightEngine ip_getLightingProvider() {
        return lightEngine;
    }
    
    @Override
    public ChunkHolder ip_getChunkHolder(long chunkPosLong) {
        return getVisibleChunkIfPresent(chunkPosLong);
    }
    
    /**
     * packets will be sent on {@link PlayerChunkLoading}
     */
    @Inject(
        method = "applyChunkTrackingView",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onUpdateChunkTracking(
        ServerPlayer serverPlayer, ChunkTrackingView chunkTrackingView, CallbackInfo ci
    ) {
        ci.cancel();
    }
    
    /**
     * @author qouteall
     * @reason
     */
    @Overwrite
    private void onChunkReadyToSend(LevelChunk chunk) {
        ImmPtlChunkTracking.onChunkProvidedDeferred(chunk);
    }

    /**
     * Originally @Overwrite (credit Nick1st: "otherwise an empty list will always be returned here").
     * Replaced with @Inject HEAD cancellable to coexist with Sable's @Inject HEAD cancellable
     * at sable.mixins.json:plot.ChunkMapMixin#sable$getPlayers, which previously couldn't bind
     * because the @Overwrite erased the method body (conflict #3 of 5 in audit/phase4_classified.md).
     *
     * The Sable guard makes the two paths mutually exclusive by chunk position:
     *   - Plot chunks (inside Sable's sub-level plot grid, far out at ~10000 chunks origin):
     *     IP yields and Sable's @Inject cancels with the sub-level's tracked players.
     *   - All other chunks: IP cancels with ImmPtl's portal-aware tracking.
     *
     * SableBridge.isPlotChunk(...) returns false when Sable isn't on the classpath, so with
     * Sable absent every chunk routes through IP -- identical behavior to the prior @Overwrite.
     *
     * The guard makes this fix robust regardless of which mixin's HEAD code Mixin emits first
     * at runtime: even if IP's @Inject runs before Sable's, IP yields for plot chunks and
     * lets Sable's @Inject (which is guaranteed to be in the same method's bytecode) cancel
     * with the correct sub-level trackers.
     */
    @Inject(method = "getPlayers", at = @At("HEAD"), cancellable = true)
    private void ip_overrideGetPlayers(
        ChunkPos pos, boolean boundaryOnly,
        CallbackInfoReturnable<List<ServerPlayer>> cir
    ) {
        if (SableBridge.isPlotChunk(level, pos)) {
            return;  // defer to Sable's @Inject HEAD cancellable for sub-level plot chunks
        }
        cir.setReturnValue(
            ImmPtlChunkTracking.getPlayersViewingChunk(level.dimension(), pos.x, pos.z, boundaryOnly)
        );
        cir.cancel();
    }
}
