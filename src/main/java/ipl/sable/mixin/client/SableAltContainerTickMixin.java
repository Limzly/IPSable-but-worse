package ipl.sable.mixin.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * Tick Sable's {@link SubLevelContainer} on every loaded {@link ClientLevel}, not just the
 * one matching {@code Minecraft.level}. Required for cross-dim sub-level interpolation.
 *
 * <p><b>The bug this fixes:</b> Sable's
 * {@code dev.ryanhcode.sable.mixin.plot.MinecraftMixin.sable$tickPlotContainer} hooks
 * {@code Minecraft.tick()} with:
 * <pre>
 *   if (this.level != null) {
 *       SableClient.NETWORK_EVENT_LOOP.runAllTasks();
 *       if (!this.pause) {
 *           ((SubLevelContainerHolder) this.level).sable$getPlotContainer().tick();
 *       }
 *   }
 * </pre>
 * It only ticks the {@code ClientSubLevelContainer} attached to the <i>current</i>
 * {@code Minecraft.level}. Inside that container's {@code tick()} is
 * {@code interpolation.tick()} which advances the per-sub-level pose interpolator. When
 * IP is showing a cross-dim portal view, the player is in dim A but they're watching a
 * sub-level in dim B; the dim-B {@code ClientSubLevelContainer} accumulates incoming
 * snapshots from the server (via our cross-dim packet routing) but its interpolation
 * never advances -- so the rendered pose stays frozen at whatever was last interpolated.
 *
 * <p>Observed symptom: airship visible through the portal but appears motionless. On
 * dim transition back to A, Minecraft.level becomes A's ClientLevel again, A's container
 * starts ticking, interpolation suddenly advances to the latest accumulated snapshot,
 * and the airship visibly snaps to its current pose. The "snap on return" was the dead
 * giveaway that snapshots were arriving but interpolation wasn't running.
 *
 * <p><b>The fix:</b> after Sable's hook runs (at {@code Minecraft.tick} TAIL), iterate
 * IP's alt {@code ClientLevel}s (everything in {@link ClientWorldLoader#getClientWorlds})
 * other than the current one and call {@code container.tick()} on each. That advances
 * the interpolation state on alt-dim containers so a sub-level visible cross-dim through
 * a portal renders with live motion.
 *
 * <p>Cost is bounded: per non-current ClientLevel per tick, {@code container.tick()} just
 * advances an interpolation tick counter and runs through whichever sub-levels exist
 * there. Cheap if no sub-levels live in that dim.
 *
 * <p>Client-only mixin (under the {@code "client"} key in {@code ipl_sable.mixins.json}).
 */
@Mixin(Minecraft.class)
public abstract class SableAltContainerTickMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-sable");

    @Inject(method = "tick", at = @At("TAIL"))
    private void ipl$tickAltSableContainers(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        ClientLevel current = mc.level;
        if (current == null) return;

        try {
            boolean hostingTicked = false;
            if (ClientWorldLoader.getIsInitialized()) {
                for (ClientLevel alt : ClientWorldLoader.getClientWorlds()) {
                    if (alt == current) continue;
                    SubLevelContainer container = SubLevelContainer.getContainer((Level) alt);
                    if (container == null) continue;
                    container.tick();
                    if (ipl.sable.dim.IplDimAgnostic.isHostingLevel(alt)) {
                        hostingTicked = true;
                        // Flywheel visual safety net: plot BEs missed by the chunk hooks
                        // (Sable's own chunk pipeline / late parent sync) get queued into
                        // their parent's visualization world here.
                        ipl.sable.client.IplClientFlywheelReroute.sweepHostedContainer(container);
                    }
                }
            }
            // Sable ticks only Minecraft.level before this TAIL hook. Hosted ships all
            // interpolate in the dedicated hosting container, which is normally neither
            // the player's current world nor an IP alt world. Tick it exactly once so
            // staff snapshots advance every client tick instead of arriving in a buffer
            // that renders intermittently/frozen.
            if (!hostingTicked && !ipl.sable.dim.IplDimAgnostic.isHostingLevel(current)) {
                SubLevelContainer hosting = ipl.sable.client.IplClientHostedLookup
                    .getHostingContainerOrNull();
                if (hosting != null) {
                    hosting.tick();
                    ipl.sable.client.IplClientFlywheelReroute.sweepHostedContainer(hosting);
                }
            }
            ipl.sable.client.IplParentDimSync.applyPendingHandoffs();
            ipl.sable.client.IplParentDimSync.clientHeartbeat();
        } catch (Throwable t) {
            // Don't let a single dim's tick failure kill the whole render loop. Log at warn
            // so we notice if it starts spamming.
            IPL$LOG.warn("[IPL-SABLE] alt container tick failed", t);
        }
    }
}
