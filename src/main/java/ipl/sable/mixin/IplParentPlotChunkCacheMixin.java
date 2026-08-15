package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stock Sable keeps boarded entities in their parent level while their coordinates become plot
 * local. Atlas keeps plot chunks in the hosting dimension, so parent-level chunk cache reads
 * must resolve a live hosted plot through the shared hosting container. This is an exact
 * coordinate-to-plot lookup, not a body/nearby-world heuristic; arbitrary addons therefore
 * retain their own parent-level {@code WorldAttached}, entity, packet and renderer state.
 */
@Mixin(value = ServerChunkCache.class, priority = 1200)
public abstract class IplParentPlotChunkCacheMixin {

    @Shadow @Final private ServerLevel level;

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$hostedPlotChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = ipl$hostedPlotChunk(x, z);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(
        method = "getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private void ipl$hostedPlotChunk(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        LevelChunk chunk = ipl$hostedPlotChunk(x, z);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private void ipl$hostedPlotChunkAccess(
        int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<ChunkAccess> cir
    ) {
        LevelChunk chunk = ipl$hostedPlotChunk(x, z);
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$hasHostedPlotChunk(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (IplDimAgnostic.isHostingLevel(this.level)) return;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(this.level);
        if (hosting == null || !hosting.inBounds(x, z) || hosting.getPlot(x, z) == null) return;
        if (hosting.getChunk(new ChunkPos(x, z)) != null) cir.setReturnValue(true);
    }

    private LevelChunk ipl$hostedPlotChunk(int x, int z) {
        if (IplDimAgnostic.isHostingLevel(this.level)) return null;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(this.level);
        if (hosting == null || !hosting.inBounds(x, z) || hosting.getPlot(x, z) == null) return null;
        return hosting.getChunk(new ChunkPos(x, z));
    }
}
