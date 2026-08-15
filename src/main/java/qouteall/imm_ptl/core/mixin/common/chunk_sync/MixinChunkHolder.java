package qouteall.imm_ptl.core.mixin.common.chunk_sync;

import ipl.sable.SableBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEChunkHolder;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.List;

@Mixin(ChunkHolder.class)
public class MixinChunkHolder implements IEChunkHolder {
    
    @Shadow
    @Final
    private LevelHeightAccessor levelHeightAccessor;
    
    @SuppressWarnings({"unchecked", "rawtypes"})
    @ModifyVariable(
        method = "broadcast",
        at = @At("HEAD"),
        argsOnly = true
    )
    private Packet<?> modifyPacket(Packet<?> packet) {
        ServerLevel serverWorld = (ServerLevel) levelHeightAccessor;
        return PacketRedirection.createRedirectedMessage(
            serverWorld.getServer(),
            serverWorld.dimension(),
            ((Packet) packet)
        );
    }
    
    /**
     * Does not mixin {@link net.minecraft.server.level.ChunkMap#getPlayers(ChunkPos, boolean)}
     * because the current chunk map tracking implementation should coexist with vanilla tracking
     * and avoid deeply interfering with vanilla chunk tracking.
     *
     * <p>Sable plot-chunk guard: when {@code chunkPos} is inside Sable's sub-level plot grid,
     * defer to the original {@code playerProvider.getPlayers} call. That call now routes through
     * {@code MixinChunkMap_C}'s @Inject HEAD cancellable, which yields to Sable's @Inject for
     * plot chunks and returns the sub-level's tracked players. Without this guard,
     * {@code ImmPtlChunkTracking.getPlayersViewingChunk} returns an empty list for plot chunks
     * (IP's chunk-tracking doesn't track Sable's far-out plot grid), and block-change broadcasts
     * for sub-level interiors -- specifically the {@link net.minecraft.network.protocol.game
     * .ClientboundBlockUpdatePacket} emitted from {@code ChunkHolder.broadcastChanges} when a
     * player breaks/places blocks on an airship -- get sent to nobody. Symptom: block-break
     * appears to succeed via client prediction, then the broken block reappears once the
     * client's prediction expires, because the authoritative server state (also air) never
     * reached the client.
     */
    @Redirect(
        method = "broadcastChanges",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkHolder$PlayerProvider;getPlayers(Lnet/minecraft/world/level/ChunkPos;Z)Ljava/util/List;"
        )
    )
    private List<ServerPlayer> redirectGetPlayers(ChunkHolder.PlayerProvider playerProvider, ChunkPos chunkPos, boolean boundaryOnly) {
        Level level = (Level) levelHeightAccessor;
        if (SableBridge.isPlotChunk(level, chunkPos)) {
            // Defer to the original call; goes through MixinChunkMap_C @Inject -> Sable @Inject ->
            // sub-level trackers. The invocation here is a new INVOKE in this handler's bytecode,
            // not the redirected site, so no recursion.
            return playerProvider.getPlayers(chunkPos, boundaryOnly);
        }
        return ImmPtlChunkTracking.getPlayersViewingChunk(
            level.dimension(),
            chunkPos.x, chunkPos.z,
            boundaryOnly
        );
    }
}
