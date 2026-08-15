package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Make Sable's {@code ClientboundStartTrackingSubLevelPacket.handle} idempotent.
 *
 * <p>Upstream Sable's handler calls {@code clientContainer.allocateSubLevel(uuid, x, z, pose)}
 * unconditionally, and {@code SubLevelContainer.allocateSubLevel} throws
 * {@code IllegalArgumentException("Plot already exists at x, z")} if the plot slot is
 * occupied. So if the client receives StartTracking for a sub-level it's already tracking
 * -- which happens legitimately whenever a player goes out of range and comes back, or
 * crosses a portal and returns -- the client crashes.
 *
 * <p><b>Concrete trigger we hit:</b> portal traversal with an assembled airship. Player
 * crosses portal -> server emits StopTracking -> client should clear the sub-level. But
 * IP's portal-view rendering retains source-dim chunk/entity data for the duration of the
 * portal view (so you can see through the portal back into the source dim). When the
 * player re-enters the source dim, the server legitimately re-emits StartTracking. Plot
 * (0, 0) is still occupied client-side -> crash.
 *
 * <p>This mixin checks {@code container.getSubLevel(uuid)} before letting the handler
 * proceed. If the UUID is already tracked, cancel the handler -- the data being delivered
 * in the second StartTracking is essentially the same as the first (Sable's server
 * doesn't generate new state for a re-sync), so no client-side state needs updating.
 *
 * <p>This is a Sable robustness gap that would benefit any user, even without IP; the
 * mixin can stay even if upstream Sable later fixes it.
 *
 * <p>Diagnosis via {@code ipl.sable.mixin.debug.SableTrackingDebugMixin} confirmed both
 * emits come from the same source: {@code SubLevelTrackingSystem.tick() line 219}, the
 * "add players who SHOULD be tracking but aren't" branch. Server behavior is correct;
 * the gap is purely client-side.
 *
 * <p><b>Two collision modes are handled:</b>
 * <ol>
 *   <li><b>Same UUID re-tracked</b> -- the original case above. Skip the re-allocation.</li>
 *   <li><b>Different UUID, same plot</b> -- a stale client-side occupant sits on the
 *       plot the incoming sub-level wants. The server can't host two sub-levels on one
 *       plot (its own {@code allocateSubLevel} would have thrown), so a client occupant
 *       with a <i>different</i> UUID is necessarily stale -- a StopTracking we missed
 *       (commonly one redirected through a portal view). We evict the stale occupant so
 *       the server-authoritative incoming sub-level can take the slot. This is the exact
 *       crash in the 31May report: a persisted ghost mirror loaded onto plot (0,0) and
 *       the real airship's StartTracking then threw "Plot already exists at 0, 0".</li>
 * </ol>
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket", remap = false)
public abstract class SableStartTrackingDedupeMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-sable");

    /**
     * Record accessors. Sable's packet is a {@code record}; @Shadow binds to the
     * synthetic component accessors by name.
     */
    @Shadow public abstract UUID subLevelID();

    @Shadow public abstract long plotCoordinate();

    @Inject(
        method = "handle",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void ipl$dedupeStartTracking(PacketContext context, CallbackInfo ci) {
        Level level = context.level();
        if (level == null) return;
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        UUID uuid = this.subLevelID();

        // (1) Same-UUID re-track: already tracking → skip re-allocation. The server's
        // re-emit doesn't carry novel state we need to apply -- pose/bounds updates
        // arrive on separate packets.
        if (container.getSubLevel(uuid) != null) {
            LOG.debug("[IPL-SABLE] Skipping duplicate StartTracking for sub-level {} (already tracked)", uuid);
            ci.cancel();
            return;
        }

        // (2) Different-UUID-same-plot collision: evict the stale occupant so the
        // incoming (server-authoritative) sub-level can allocate, instead of letting
        // allocateSubLevel throw "Plot already exists at x, z" and crash the render
        // thread. Plot coords are decoded the same way the handler does
        // (ChunkPos.getX/getZ on the packed long).
        long plot = this.plotCoordinate();
        int x = ChunkPos.getX(plot);
        int z = ChunkPos.getZ(plot);
        SubLevel occupant = container.getSubLevel(x, z);
        if (occupant != null && !occupant.getUniqueId().equals(uuid)) {
            LOG.warn("[IPL-SABLE] StartTracking plot collision at ({}, {}): evicting stale "
                    + "occupant {} for incoming {}", x, z, occupant.getUniqueId(), uuid);
            try {
                container.removeSubLevel(x, z, SubLevelRemovalReason.REMOVED);
            } catch (Throwable t) {
                // Eviction failed -- cancel to avoid the hard "Plot already exists"
                // crash. The incoming sub-level stays untracked until the next emit.
                LOG.error("[IPL-SABLE] failed to evict stale occupant at ({}, {}); "
                        + "skipping StartTracking for {}", x, z, uuid, t);
                ci.cancel();
            }
        }
        // else: plot free -- let Sable's handler allocate normally.
    }
}
