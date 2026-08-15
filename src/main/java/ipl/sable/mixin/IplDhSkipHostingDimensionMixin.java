package ipl.sable.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents Distant Horizons from creating a DhLevel for the
 * ipl_sable:sublevels hosting dimension.
 *
 * DH's LocalSaveStructure accumulates data paths from all previously
 * registered dimensions. The ipl_sable:sublevels hosting dimension is
 * patient zero. Its creation pollutes the path list for all subsequent
 * dimensions (nether, end, etc).
 *
 * This mixin cancels the AbstractDhLevel constructor when the
 * DhDimensionTracker flag is set (which happens when the hosting
 * dimension's ServerLevel is created).
 *
 * We can't use varargs here because CallbackInfo must be the last
 * parameter. Instead we use a no-arg handler and rely on the flag.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.AbstractDhLevel", remap = false)
public class IplDhSkipHostingDimensionMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");

    @Inject(
        method = "<init>",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipHostingDimension(CallbackInfo ci) {
        if (ipl.sable.dh.DhDimensionTracker.isHostingDimensionLoading()) {
            LOG.debug("Skipping DhLevel creation for ipl_sable:sublevels");
            ci.cancel();
        }
    }
}
