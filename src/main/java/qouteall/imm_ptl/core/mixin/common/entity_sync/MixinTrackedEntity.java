package qouteall.imm_ptl.core.mixin.common.entity_sync;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEEntityTrackerEntry;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.List;
import java.util.Map;
import java.util.Set;

//NOTE must redirect all packets about entities
@SuppressWarnings({"JavadocReference", "resource"})
@Mixin(ChunkMap.TrackedEntity.class)
public abstract class MixinTrackedEntity implements IETrackedEntity {
    @Shadow
    @Final
    private ServerEntity serverEntity;
    @Shadow
    @Final
    private Entity entity;
    
    @Shadow
    public abstract void broadcastRemoved();
    
    @Shadow
    protected abstract int getEffectiveRange();
    
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;
    
    @Shadow
    private SectionPos lastSectionPos;
    
    @Redirect(
        method = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;broadcast(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void onSendToOtherNearbyPlayers(
        ServerPlayerConnection entityTrackingListener, Packet<?> packet
    ) {
        PacketRedirection.withForceRedirect(
            ((ServerLevel) entity.level()),
            () -> {
                entityTrackingListener.send(packet);
            }
        );
    }
    
    @SuppressWarnings("rawtypes")
    @Redirect(
        method = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;broadcastAndSend(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void onSendToNearbyPlayers(
        ServerGamePacketListenerImpl serverPlayNetworkHandler,
        Packet packet
    ) {
        PacketRedirection.sendRedirectedPacket(
            serverPlayNetworkHandler, packet, entity.level().dimension()
        );
    }
    
    // IP manages entity tracking exclusively via ip_updateEntityTrackingStatus() (below),
    // called per-tick from qouteall.imm_ptl.core.chunk_loading.EntitySync.update(server).
    // Vanilla's updatePlayer/updatePlayers MUST NOT run alongside it -- their distance-based
    // add/remove of `seenBy` competes with IP's portal-aware tracking and produces per-chunk-
    // boundary Add/Remove packet ping-pong on the client (visible as F3 entity-count flicker
    // and intermittent entity rendering).
    //
    // The vanilla per-tick tracker loop is cancelled in MixinChunkMap_E. But ChunkMap.move(
    // ServerPlayer) -- fired every time a player crosses a chunk boundary -- ALSO iterates
    // every tracked entity and calls tracker.updatePlayer(player). That second call site is
    // not intercepted in MixinChunkMap_E and remains active.
    //
    // Originally IP handled this with @Overwrite(updatePlayer) + @Overwrite(updatePlayers) to
    // empty bodies, making the move() call a no-op. Sable's
    // dev.ryanhcode.sable.mixin.entity.entity_tracking.TrackedEntityMixin has a @Redirect on
    // the Entity.position() INVOKE inside the vanilla updatePlayer body; with IP's @Overwrite
    // to empty body, the INVOKE doesn't exist and Sable's require=1 trips InjectionError at
    // apply phase -> server refuses to boot when Sable+IP are both present.
    //
    // Resolution: @Inject HEAD cancellable instead of @Overwrite. The vanilla body remains in
    // bytecode (so Sable's @Redirect can bind successfully -- bind is what counts toward
    // require, not execution), but IP's @Inject cancels at HEAD before vanilla's distance
    // logic runs. Net behavior matches the original @Overwrite exactly; Sable's @Redirect
    // is technically bound but never fires because vanilla's body never executes.
    @Inject(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("HEAD"), cancellable = true)
    private void ip_cancelUpdatePlayer(ServerPlayer player, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "updatePlayers(Ljava/util/List;)V",
            at = @At("HEAD"), cancellable = true)
    private void ip_cancelUpdatePlayers(List<ServerPlayer> list, CallbackInfo ci) {
        ci.cancel();
    }
    
    @Override
    public Entity ip_getEntity() {
        return entity;
    }
    
    /**
     * {@link ChunkMap.TrackedEntity#updatePlayer(ServerPlayer)}
     * This only checks the players viewing the chunk.
     * so this is more efficient in this aspect.
     * However, in vanilla, if both the entity and player doesn't move, it won't update.
     * But in ImmPtl, it constantly updates because portals can change at any time and
     * that can changes entity visibility at anytime.
     */
    @IPVanillaCopy
    @Override
    public void ip_updateEntityTrackingStatus() {
        IEChunkMap chunkMap = (IEChunkMap)
            ((ServerLevel) entity.level()).getChunkSource().chunkMap;

        // Sable compat: for a sub-level entity (e.g. a Create seat on an airship),
        // the watch-record lookup must use the VISIBLE chunk -- where players
        // actually are relative to the airship -- not the raw ~20M-block plot
        // chunk, where no player ever has a watch record. Route through the
        // effective-tracking-chunk seam (SableBridge). Without this, seenBy stays
        // empty and the entity is tracked to nobody (the seat's SetPassengers
        // never reaches the riding player). No-op when Sable is absent.
        long remappedChunk = ipl.sable.SableBridge.effectiveTrackingChunkPos(entity);
        int trackChunkX = remappedChunk != ipl.sable.SableBridge.NO_REMAP
            ? net.minecraft.world.level.ChunkPos.getX(remappedChunk)
            : entity.chunkPosition().x;
        int trackChunkZ = remappedChunk != ipl.sable.SableBridge.NO_REMAP
            ? net.minecraft.world.level.ChunkPos.getZ(remappedChunk)
            : entity.chunkPosition().z;

        var watchRecMap = ImmPtlChunkTracking.getWatchRecordForChunk(
            entity.level().dimension(),
            trackChunkX, trackChunkZ
        );
        
        // no need to clamp it with render distance, as we check chunk watch records now
        int effectiveRange = getEffectiveRange();
        
        seenBy.removeIf(connection -> {
            ServerPlayer player = connection.getPlayer();
            boolean shouldRemove = !watches(entity, watchRecMap, effectiveRange, player);
            if (shouldRemove) {
                PacketRedirection.withForceRedirect(
                    ((ServerLevel) entity.level()),
                    () -> {
                        this.serverEntity.removePairing(player);
                    }
                );
            }
            return shouldRemove;
        });
        
        if (watchRecMap != null) {
            for (var e : watchRecMap.entrySet()) {
                ServerPlayer player = e.getKey();
                ImmPtlChunkTracking.PlayerWatchRecord rec = e.getValue();
                
                if (recWatches(entity, effectiveRange, rec, player)) {
                    if (seenBy.add(player.connection)) {
                        PacketRedirection.withForceRedirect(
                            ((ServerLevel) entity.level()),
                            () -> {
                                this.serverEntity.addPairing(player);
                            }
                        );
                    }
                }
            }
        }
    }
    
    @Unique
    private static boolean watches(
        Entity entity,
        @Nullable Map<ServerPlayer, ImmPtlChunkTracking.PlayerWatchRecord> watchRec,
        int effectiveRange,
        ServerPlayer player
    ) {
        if (watchRec == null) {
            return false;
        }
        
        if (entity == player) {
            return false;
        }
        
        ImmPtlChunkTracking.PlayerWatchRecord rec = watchRec.get(player);
        
        return recWatches(entity, effectiveRange, rec, player);
    }
    
    @Unique
    private static boolean recWatches(
        Entity entity, int effectiveRange,
        ImmPtlChunkTracking.PlayerWatchRecord rec, ServerPlayer player
    ) {
        if (rec == null) {
            return false;
        }
        
        if (!rec.isLoadedToPlayer) {
            // when player logging in standing on cross portal collision
            // we need to send add entity packet early,
            // otherwise cross portal collision will not work early enough
            // but if the portal is big (center can be 4 chunks away from player)
            // the new slow chunk sending mechanism will slow its packet sent
            // TODO find a solution to this
            return false;
        }
        
        if (entity == player) {
            return false;
        }
        
        return rec.distanceToSource * 16 + 8 <= effectiveRange;
    }
    
    @Override
    public void ip_onDimensionRemove() {
        for (ServerPlayerConnection connection : seenBy) {
            serverEntity.removePairing(connection.getPlayer());
        }
        seenBy.clear();
    }
    
    @Override
    public void ip_resendSpawnPacketToTrackers() {
        // avoid sending wrong position delta update packet
        ((IEEntityTrackerEntry) serverEntity).ip_updateTrackedEntityPosition();
        
        Packet spawnPacket = entity.getAddEntityPacket(serverEntity);
        Packet<ClientGamePacketListener> redirected = PacketRedirection.createRedirectedMessage(
            entity.getServer(),
            entity.level().dimension(), spawnPacket
        );
        seenBy.forEach(handler -> {
            handler.send(redirected);
        });
    }
    
    @Override
    public void ip_stopTrackingToAllPlayers() {
        broadcastRemoved();
    }
    
    @Override
    public void ip_sendChanges() {
        serverEntity.sendChanges();
    }
    
    @Override
    public SectionPos ip_getLastSectionPos() {
        return lastSectionPos;
    }
    
    @Override
    public void ip_setLastSectionPos(SectionPos arg) {
        lastSectionPos = arg;
    }
    
}
