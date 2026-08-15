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
 * dimensions (nether, end, etc). DH then reads/writes LOD data from
 * the wrong dimension's files.
 *
 * The config patch (ignoredDimensionCsv) only prevents DH from RENDERING
 * the hosting dim. It does not prevent DH from CREATING a DhLevel for
 * it. The path accumulation happens at DhLevel creation time, before
 * the config's ignore list is checked.
 *
 * This mixin cancels the DhLevel creation for ipl_sable:sublevels
 * entirely. Soft-applies via @Pseudo + require=0 so it no-ops if DH
 * is absent or the API changes.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.AbstractDhLevel", remap = false)
public class IplDhSkipHostingDimensionMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");

    /**
     * Cancel the constructor when the dimension is ipl_sable:sublevels.
     * We can't easily check the dimension parameter from a @Pseudo mixin
     * because we don't know the exact constructor signature. Instead, we
     * use a thread-local flag set by the server level load event.
     *
     * Actually, a simpler approach: check the current level being
     * processed via DH's own API. But since we can't reference DH types
     * at compile time, we use a static flag that gets set by our
     * IPModEntryClient when the hosting dimension is about to load.
     */
    @Inject(
        method = "<init>",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipHostingDimension(CallbackInfo ci) {
        // Check the static flag set by our dimension tracking
        if (ipl.sable.dh.DhDimensionTracker.isHostingDimensionLoading()) {
            LOG.debug("Skipping DhLevel creation for ipl_sable:sublevels");
            ci.cancel();
        }
    }
}
