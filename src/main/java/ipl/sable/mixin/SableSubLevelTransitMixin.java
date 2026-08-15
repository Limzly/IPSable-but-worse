package ipl.sable.mixin;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import ipl.sable.transit.SableTransitController;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drive {@link SableTransitController} at the TAIL of every
 * {@link ServerSubLevelContainer#tick()}.
 *
 * <p>Why TAIL: by the time {@code tick()} returns, Sable has already:
 * <ul>
 *   <li>Run the physics pipeline (so {@code logicalPose} is the end-of-tick pose).</li>
 *   <li>Processed sub-level removals queued during physics.</li>
 *   <li>Invoked all observers' {@code tick(container)} (tracking system has emitted
 *       this tick's bounds + movement packets).</li>
 * </ul>
 * That's the right window to detect "did the airship cross the portal this tick"
 * and to dispatch a transit -- any allocation/removal we do here lands cleanly
 * before the next tick begins.
 *
 * <p>Server-side only. The mixin file is registered in the common section of
 * {@code ipl_sable.mixins.json}; {@link ServerSubLevelContainer} only exists in
 * server contexts so the mixin can't bind on a dedicated client.
 *
 * <p>{@code @Pseudo} because we target a Sable class that might not be present at
 * compile-time-strict mixin validation; it's on the runtime classpath via the
 * sable jar in {@code deps/}.
 */
@Pseudo
@Mixin(value = ServerSubLevelContainer.class, remap = false)
public abstract class SableSubLevelTransitMixin {

    @Inject(method = "tick", at = @At("TAIL"), remap = false, require = 0)
    private void ipl$onContainerTickTail(CallbackInfo ci) {
        ServerSubLevelContainer self = (ServerSubLevelContainer) (Object) this;
        if (self.getLevel() instanceof ServerLevel) {
            // Dim-agnostic rehome sweep runs first: migrates freshly-assembled/loaded
            // sub-levels into ipl_sable:sublevels (and restores persisted parents on the
            // hosting container) before the transit controller looks at them.
            ipl.sable.transit.SableRehomeOps.sweep(self);
            SableTransitController.onContainerTick(self);
        }
    }
}
