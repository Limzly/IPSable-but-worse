package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.SableSubLevelDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Route DEFERRED block logic for hosted plot positions to the HOSTING level.
 *
 * <p>Block-update cascades run on whatever ServerLevel the interaction came from (the
 * player's dimension), but a hosted plot's tick infrastructure lives ONLY with the hosting
 * level: {@code LevelChunkTicks} containers are registered there
 * ({@code ServerLevelPlot.newChunk -> registerTickContainerInLevel}), as is the
 * ENTITY_TICKING chunk status that {@code LevelTicks}' run-gate
 * ({@code isPositionTickingWithEntitiesLoaded}) checks. A tick scheduled from another
 * dimension lands in a stale or missing container and never executes — silently (the
 * pre-rehome original ship leaves a registered-but-never-collected container in its
 * assembly dimension at the same plot slot, so not even the vanilla "not loaded position"
 * warning fires). Net effect on ships: redstone DUST (immediate) worked while everything
 * scheduled-tick-based (repeater, lamp-off, observers) and block-event-based (pistons,
 * note blocks) misbehaved.
 *
 * <p>Three fixes, one class:
 * <ul>
 *   <li>{@code scheduleTick} ×4 (block/fluid × with/without priority): interface defaults
 *       on {@code LevelAccessor}, so overrides are MERGED into ServerLevel (the proven
 *       {@code getPlayerByUUID} pattern) routing plot-bound positions to the hosting
 *       level. Also fixes fluid spread on ships.</li>
 *   <li>{@code blockEvent}: queue plot-bound events on the hosting level.</li>
 *   <li>{@code runBlockEvents}' broadcast: vanilla sends the event packet to players
 *       within 64 blocks of the PLOT coordinates in the queueing dimension — i.e. nobody,
 *       ever. For plot positions, send to the owning sub-level's tracking players instead
 *       (the hosting tick's PacketRedirection wrap dim-stamps it).</li>
 * </ul>
 */
@Mixin(value = ServerLevel.class, priority = 1200)
public abstract class IplPlotDeferredLogicMixin implements LevelAccessor {

    /** The hosting level when {@code pos} is a hosted plot position reached from elsewhere. */
    @Unique
    private ServerLevel ipl$plotRouteTarget(BlockPos pos) {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerLevel hosting = SableSubLevelDimension.getSableSubLevelsOrNull(self.getServer());
        if (hosting == null || hosting == self) return null;

        SubLevelContainer container = SubLevelContainer.getContainer((Level) hosting);
        if (container == null || container.getPlot(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return null;
        }
        return hosting;
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
        ServerLevel target = ipl$plotRouteTarget(pos);
        if (target != null) {
            target.scheduleTick(pos, block, delay);
        } else {
            LevelAccessor.super.scheduleTick(pos, block, delay);
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        ServerLevel target = ipl$plotRouteTarget(pos);
        if (target != null) {
            target.scheduleTick(pos, block, delay, priority);
        } else {
            LevelAccessor.super.scheduleTick(pos, block, delay, priority);
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        ServerLevel target = ipl$plotRouteTarget(pos);
        if (target != null) {
            target.scheduleTick(pos, fluid, delay);
        } else {
            LevelAccessor.super.scheduleTick(pos, fluid, delay);
        }
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        ServerLevel target = ipl$plotRouteTarget(pos);
        if (target != null) {
            target.scheduleTick(pos, fluid, delay, priority);
        } else {
            LevelAccessor.super.scheduleTick(pos, fluid, delay, priority);
        }
    }

    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void ipl$routePlotBlockEvent(
        BlockPos pos, Block block, int eventID, int eventParam, CallbackInfo ci
    ) {
        ServerLevel target = ipl$plotRouteTarget(pos);
        if (target != null) {
            target.blockEvent(pos, block, eventID, eventParam);
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "runBlockEvents",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V"
        ),
        require = 0
    )
    private void ipl$broadcastPlotBlockEvent(
        PlayerList playerList, Player except, double x, double y, double z,
        double radius, ResourceKey<Level> dimension, Packet<?> packet,
        Operation<Void> original
    ) {
        ServerLevel self = (ServerLevel) (Object) this;
        SubLevelContainer container = SubLevelContainer.getContainer((Level) self);
        int chunkX = ((int) Math.floor(x)) >> 4;
        int chunkZ = ((int) Math.floor(z)) >> 4;
        if (container != null && container.inBounds(chunkX, chunkZ)
            && container.getPlot(chunkX, chunkZ) != null) {
            List<ServerPlayer> tracking = container.getPlayersTracking(new ChunkPos(chunkX, chunkZ));
            if (!tracking.isEmpty()) {
                for (ServerPlayer player : tracking) {
                    player.connection.send(packet);
                }
                return;
            }
        }
        original.call(playerList, except, x, y, z, radius, dimension, packet);
    }
}
