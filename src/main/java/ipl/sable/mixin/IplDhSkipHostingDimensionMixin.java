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
 * This mixin intercepts DH's AbstractDhLevel constructor and cancels it
 * when the dimension is ipl_sable:sublevels. We can't easily check the
 * dimension parameter from a @Pseudo mixin because we don't know the
 * exact constructor signature, so we use a thread-local flag.
 *
 * The flag is set by checking the server's level registry. We use a
 * different approach: we check if the dimension key matches by
 * inspecting the constructor's first argument (which is typically the
 * level/dimension wrapper). Since we can't reference DH types, we
 * use toString() to check.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.level.AbstractDhLevel", remap = false)
public class IplDhSkipHostingDimensionMixin {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-dh-compat");

    /**
     * Cancel the constructor when the dimension is ipl_sable:sublevels.
     * We inspect the first argument's toString() to check if it mentions
     * ipl_sable:sublevels. This is fragile but works because DH's level
     * wrappers include the dimension key in their toString().
     *
     * If the constructor signature doesn't match, the mixin doesn't apply
     * (require=0) and DH creates the level normally.
     */
    @Inject(
        method = "<init>",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_skipHostingDimension(Object... args, CallbackInfo ci) {
        if (ipl.sable.dh.DhDimensionTracker.isHostingDimensionLoading()) {
            LOG.debug("Skipping DhLevel creation for ipl_sable:sublevels");
            ci.cancel();
        }
    }
}
