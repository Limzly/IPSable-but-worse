package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.client.IplClientHostedLookup;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Client mirror of the parent plot cache bridge. */
@Mixin(value = ClientChunkCache.class, priority = 1200)
public abstract class IplParentPlotClientChunkCacheMixin {

    @Shadow @Final private ClientLevel level;

    @Inject(
        method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private void ipl$hostedPlotChunk(
        int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<LevelChunk> cir
    ) {
        if (IplDimAgnostic.isHostingLevel(this.level)) return;
        SubLevelContainer hosting = IplClientHostedLookup.getHostingContainerOrNull();
        if (hosting == null || !hosting.inBounds(x, z) || hosting.getPlot(x, z) == null) return;
        LevelChunk chunk = hosting.getChunk(new ChunkPos(x, z));
        if (chunk != null) cir.setReturnValue(chunk);
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true, require = 0)
    private void ipl$hostedPlotChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> cir) {
        if (IplDimAgnostic.isHostingLevel(this.level)) return;
        SubLevelContainer hosting = IplClientHostedLookup.getHostingContainerOrNull();
        if (hosting == null || !hosting.inBounds(x, z) || hosting.getPlot(x, z) == null) return;
        LevelChunk chunk = hosting.getChunk(new ChunkPos(x, z));
        if (chunk != null) cir.setReturnValue(chunk);
    }
}
