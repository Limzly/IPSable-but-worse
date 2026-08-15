package ipl.sable.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ipl.sable.dh.DhDimensionTracker;
import ipl.sable.dim.SableSubLevelDimension;

/**
 * Sets a flag when the {@code ipl_sable:sublevels} hosting dimension's
 * {@code ServerLevel} has been created. The flag is checked by
 * {@link IplDhSkipHostingDimensionMixin} to cancel DH's
 * {@code getOrLoadLevel} call for the hosting dimension.
 *
 * <p>We inject at RETURN (not HEAD) because:
 * <ul>
 *   <li>We need {@code this} to be initialized so we can call
 *       {@code self.dimension()} on it. At HEAD on a constructor, {@code this}
 *       is {@code uninitializedThis} per JVM §4.10.1.6 and the verifier
 *       rejects any {@code invokevirtual} on it.</li>
 *   <li>DH's DhLevel creation happens <em>after</em> {@code ServerLevel}
 *       construction completes (the chain is
 *       {@code ServerLevel.<init>} → {@code LevelEvent.Load} →
 *       {@code ServerApi.serverLevelLoadEvent} →
 *       {@code getOrLoadLevel} → {@code new DhServerLevel}). So setting
 *       the flag at RETURN of {@code ServerLevel.<init>} is early enough.</li>
 * </ul>
 *
 * <p>The whole chain is synchronous on the server thread, so the flag is
 * guaranteed to still be set when {@link IplDhSkipHostingDimensionMixin}
 * fires microseconds later. The 1-second deadline in
 * {@link DhDimensionTracker} is just a safety net for the unlikely case
 * where DH's call site changes shape in a future version and stops being
 * synchronous.
 *
 * <p>The flag is cleared lazily by
 * {@link DhDimensionTracker#isHostingDimensionLoading()} on read — that
 * method checks the deadline and clears the flag if it has expired. There
 * is no separate tick handler.
 */
@Mixin(ServerLevel.class)
public class IplServerLevelDhTrackerMixin {

    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void ip_onServerLevelCreated(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        // Use the canonical ResourceKey constant rather than a hardcoded
        // string. If the dimension ID ever changes, this stays in sync.
        if (self.dimension() == SableSubLevelDimension.SUBLEVELS) {
            // Set the flag. DH's DhLevel creation happens synchronously
            // after this constructor returns (LevelEvent.Load fires next,
            // which triggers ServerApi.serverLevelLoadEvent →
            // getOrLoadLevel → new DhServerLevel). The 1-second deadline
            // is a safety net only — see DhDimensionTracker.
            DhDimensionTracker.setHostingDimensionLoading(true);
        }
    }
}
