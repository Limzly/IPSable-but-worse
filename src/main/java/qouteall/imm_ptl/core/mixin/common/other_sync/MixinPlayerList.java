package qouteall.imm_ptl.core.mixin.common.other_sync;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.SableBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.Helper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Mixin(value = PlayerList.class, priority = 800)
public class MixinPlayerList {
    @Shadow
    @Final
    private List<ServerPlayer> players;
    
    @Shadow
    @Final
    private MinecraftServer server;
    
    @Inject(method = "Lnet/minecraft/server/players/PlayerList;sendLevelInfo(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/level/ServerLevel;)V", at = @At("RETURN"))
    private void onSendWorldInfo(ServerPlayer player, ServerLevel world, CallbackInfo ci) {
        if (!ServerTeleportationManager.of(player.server).isFiringMyChangeDimensionEvent) {
            GlobalPortalStorage.onPlayerLoggedIn(player);
        }
    }
    
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void onOnPlayerConnect(
        Connection connection, ServerPlayer player,
        CommonListenerCookie commonListenerCookie, CallbackInfo ci
    ) {
        ImmPtlChunkTracking.immediatelyUpdateForPlayer(player);
        
        // debug
        Helper.LOGGER.info("Player login {} {}", player.level().getGameTime(), player);
    }
    
    //with redirection
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(
        method = "Lnet/minecraft/server/players/PlayerList;broadcastAll(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/resources/ResourceKey;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    public void sendToDimension(Packet<?> packet, ResourceKey<Level> dimension, CallbackInfo ci) {
        for (ServerPlayer player : players) {
            if (player.level().dimension() == dimension) {
                PacketRedirection.sendRedirectedMessage(
                    player,
                    dimension,
                    (Packet<ClientGamePacketListener>) packet
                );
            }
        }
        
        ci.cancel();
    }
    
    /**
     * correct the player reference, so that in
     * {@link qouteall.imm_ptl.core.mixin.common.position_sync.MixinServerGamePacketListenerImpl#teleport(double, double, double, float, float, Set)}
     * the player's dimension will be correct
     */
    @Redirect(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V"
        )
    )
    private void onRestoreFrom(ServerPlayer newPlayer, ServerPlayer that, boolean keepEverything) {
        newPlayer.restoreFrom(that, keepEverything);
        
        newPlayer.connection.player = newPlayer;
    }
    
    /**
     * Was @Overwrite (qouteall: "mostly for sound events, make incompat fail fast").
     *
     * <p>Conflict #5 of 5 from audit/phase4_classified.md: Sable's
     * {@code mixin/plot/PlayerListMixin} also @Overwrites this method for sub-level-aware
     * distance broadcasting. Two @Overwrites on the same method trip Mixin's @Author check
     * during apply phase -> game refuses to boot.
     *
     * <p>Replaced with MixinExtras @WrapMethod. The wrapper composes cleanly with Sable's
     * @Overwrite (no @Author conflict). At runtime, every caller of {@code broadcast} routes
     * through this method; we do not invoke {@code original.call(...)} (which would run
     * Sable's @Overwrite body) because Sable's body sends raw packets without
     * {@link PacketRedirection} wrapping, breaking cross-portal sound delivery. Instead the
     * body below merges both algorithms.
     *
     * <p>Recipient set is the union of:
     * <ul>
     *   <li><b>IP path</b> ({@link ImmPtlChunkTracking} watch records): players viewing the
     *       source chunk via IP's portal-aware tracking, including cross-dimensional viewers.
     *       Sable's coord-distance scan can't find these because they're filtered by dimension.</li>
     *   <li><b>Sable path</b> ({@link SableBridge#distanceSquaredWithSubLevels}): players on
     *       airships (or any sub-level) whose visible world position is within range of the
     *       broadcast source. IP's chunk-watch can't find these because plot chunks aren't
     *       tracked by IP. Skipped entirely when Sable isn't on the classpath, reducing to
     *       exact upstream IP behavior.</li>
     * </ul>
     *
     * <p>Deduplication via the {@code sent} set ensures a player caught by both paths only
     * receives the packet once. All deliveries go through
     * {@link PacketRedirection#createRedirectedMessage} so cross-portal viewers attribute
     * the packet to the correct dimension client-side.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapMethod(method = "broadcast")
    private void ip_broadcastMerged(
        @Nullable Player excludingPlayer,
        double x, double y, double z, double distance,
        ResourceKey<Level> dimension, Packet<?> packet,
        Operation<Void> original
    ) {
        // Intentionally not calling original.call(...) -- Sable's @Overwrite body sends raw
        // packets without PacketRedirection wrapping, which would break cross-portal dimension
        // attribution. Sable's logic is reimplemented below via SableBridge.

        Set<ServerPlayer> sent = new HashSet<>();

        // (1) IP path: chunk-watch routing, cross-portal aware.
        ChunkPos chunkPos = new ChunkPos(BlockPos.containing(new Vec3(x, y, z)));
        var recs = ImmPtlChunkTracking.getWatchRecordForChunk(dimension, chunkPos.x, chunkPos.z);
        if (recs != null) {
            for (ImmPtlChunkTracking.PlayerWatchRecord rec : recs.values()) {
                if (rec.isLoadedToPlayer
                    && rec.player != excludingPlayer
                    && ImmPtlChunkTracking.isPlayerWatchingChunkWithinRadius(
                        rec.player, dimension, chunkPos.x, chunkPos.z, (int) distance + 16)) {
                    ip_sendRedirected(rec.player, dimension, packet);
                    sent.add(rec.player);
                }
            }
        }

        // (2) Sable path: sub-level-aware coordinate-distance scan for players IP missed.
        //     SableBridge.PRESENT short-circuits when Sable isn't loaded -- behavior collapses
        //     to upstream IP (recs-only delivery) in that case.
        if (SableBridge.PRESENT) {
            double maxDistSq = distance * distance;
            for (ServerPlayer player : players) {
                if (player == excludingPlayer || sent.contains(player)) continue;
                if (player.level().dimension() != dimension) continue;
                double distSq = SableBridge.distanceSquaredWithSubLevels(
                    player.level(), player.position(), x, y, z);
                if (distSq < maxDistSq) {
                    ip_sendRedirected(player, dimension, packet);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Unique
    private void ip_sendRedirected(ServerPlayer player, ResourceKey<Level> dimension, Packet<?> packet) {
        player.connection.send(
            PacketRedirection.createRedirectedMessage(
                player.getServer(), dimension, (Packet<ClientGamePacketListener>) packet
            )
        );
    }
}
