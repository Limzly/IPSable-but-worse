package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ENTITY TRACKING for hosted plots — make both hosting-owned and parent-owned plot-frame
 * entities sync to the players who track their ship.
 *
 * <p>Stock Sable already handles everything else about plot-space entities within one
 * level: {@code TrackedEntityMixin} projects the entity's position through the sub-level
 * pose for the distance check, and {@code ChunkMapMixin.isChunkTracked} answers plot
 * chunks from the sub-level's tracking players. What breaks hosted is only the INPUT: the
 * vanilla tracking tick feeds {@code level.players()} into
 * {@code TrackedEntity.updatePlayers}, and no player is ever physically inside
 * {@code ipl_sable:sublevels} — so no viewer was ever considered, and nothing in the
 * hosting dimension was synced to anyone.
 *
 * <p>Two pieces:
 * <ul>
 *   <li><b>Viewer sweep:</b> after the vanilla tracking tick on the hosting level, run
 *       {@code updatePlayers} for every tracked entity against the union of all hosted
 *       sub-levels' tracking players. Stock projection + plot-chunk gates then admit
 *       exactly the right viewers, and {@code seenBy} dedupe makes the sweep idempotent.
 *       (Static entities are fine: vanilla only re-evaluates viewers on section changes,
 *       the sweep re-evaluates every tick — the entity population here is tiny.)</li>
 *   <li><b>Dimension stamping:</b> every packet emitted by the hosting level's tracking
 *       tick (spawns from the sweep, {@code ServerEntity.sendChanges} movement, removes)
 *       is wrapped in IP's {@code PacketRedirection} so clients apply it to the hosting
 *       {@code ClientLevel} — where the plot chunks live client-side — instead of the
 *       player's current dimension. Same doctrine as {@code IplPlotChunkSendStampMixin}.</li>
 * </ul>
 */
@Mixin(ChunkMap.class)
public abstract class IplHostingEntityTrackingMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Int2ObjectMap<?> entityMap;

    /** Stamp every packet the hosting level's tracking tick emits with the hosting dim. */
    @WrapMethod(method = "tick()V")
    private void ipl$stampHostingTrackingTick(Operation<Void> original) {
        if (!IplDimAgnostic.isHostingLevel(this.level)) {
            original.call();
            return;
        }
        PacketRedirection.withForceRedirect(this.level, original::call);
    }

    /** Viewer sweep: hosted entities are watched by their ship's tracking players. */
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void ipl$hostedEntityViewerSweep(CallbackInfo ci) {
        if (this.entityMap.isEmpty()) return;

        if (IplDimAgnostic.isHostingLevel(this.level)) {
            List<ServerPlayer> viewers = ipl$unionTrackingPlayers();
            // Run even when empty: a viewer set shrinking to zero must still remove pairings.
            PacketRedirection.withForceRedirect(this.level, () -> {
                for (Object tracked : this.entityMap.values()) {
                    ((IplTrackedEntityInvoker) tracked).ipl$updatePlayers(viewers);
                }
            });
            return;
        }

        // Parent-owned entities may deliberately carry plot-local coordinates (stock Sable's
        // entity-on-sublevel contract). Vanilla uses those raw ~plot-grid coordinates to
        // decide visibility and repeatedly drops viewers, even though Sable correctly
        // projects position inside updatePlayer. Feed the owning ship's exact tracker set.
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(this.level);
        if (hosting == null) return;
        for (Object tracked : this.entityMap.values()) {
            Entity entity = ((IplTrackedEntityInvoker) tracked).ipl$getEntity();
            if (entity == null || entity.isRemoved()) continue;
            var plot = hosting.getPlot(entity.chunkPosition());
            if (!(plot != null && plot.getSubLevel() instanceof ServerSubLevel sub)
                || sub.isRemoved() || IplDimAgnostic.getServerParentLevel(sub) != this.level) {
                continue;
            }
            List<ServerPlayer> viewers = ipl$trackingPlayers(sub);
            ((IplTrackedEntityInvoker) tracked).ipl$updatePlayers(viewers);
        }
    }

    /** Removal broadcasts happen outside the tracking tick — stamp them too. */
    @WrapMethod(method = "removeEntity")
    private void ipl$stampHostedEntityRemoval(
        net.minecraft.world.entity.Entity entity, Operation<Void> original
    ) {
        if (IplDimAgnostic.isHostingLevel(this.level)) {
            PacketRedirection.withForceRedirect(this.level, () -> original.call(entity));
        } else {
            original.call(entity);
        }
    }

    @Unique
    private List<ServerPlayer> ipl$unionTrackingPlayers() {
        SubLevelContainer container = SubLevelContainer.getContainer((Level) this.level);
        if (container == null) return List.of();

        Set<UUID> seen = new HashSet<>();
        List<ServerPlayer> viewers = new ArrayList<>();
        var playerList = this.level.getServer().getPlayerList();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (!(sub instanceof ServerSubLevel hosted) || hosted.isRemoved()) continue;
            for (UUID uuid : hosted.getTrackingPlayers()) {
                if (!seen.add(uuid)) continue;
                ServerPlayer player = playerList.getPlayer(uuid);
                if (player != null) {
                    viewers.add(player);
                }
            }
        }
        return viewers;
    }

    @Unique
    private List<ServerPlayer> ipl$trackingPlayers(ServerSubLevel sub) {
        List<ServerPlayer> viewers = new ArrayList<>();
        var playerList = this.level.getServer().getPlayerList();
        for (UUID uuid : sub.getTrackingPlayers()) {
            ServerPlayer player = playerList.getPlayer(uuid);
            if (player != null) viewers.add(player);
        }
        return viewers;
    }
}
