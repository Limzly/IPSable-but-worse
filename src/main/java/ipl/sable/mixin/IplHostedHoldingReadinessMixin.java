package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Held sub-levels in the HOSTING dimension are always ready to load.
 *
 * <p>Vanilla readiness requires every chunk in the ship's bounds to be loaded in the
 * container's level — sensible for parent-dim containers (restore when a player
 * approaches), but never true in the hosting dimension whose chunks at parent-frame
 * coordinates only load via physics tickets of ALREADY-LIVE ships. Hosted ships are
 * always-live: once their holding chunk is in memory (boot restore), they release
 * immediately; their terrain comes from the parent dim via the ticket pre-enrollment.
 */
@Pseudo
@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class IplHostedHoldingReadinessMixin {

    @Inject(method = "canLoadSubLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private static void ipl$hostedAlwaysReady(
        ServerLevel level, SubLevelData data, CallbackInfoReturnable<Boolean> cir
    ) {
        if (IplDimAgnostic.isHostingLevel(level)) {
            cir.setReturnValue(true);
        }
    }
}
