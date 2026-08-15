package ipl.sable.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ipl.sable.dh.DhDimensionTracker;

/**
 * Sets a flag when the ipl_sable:sublevels hosting dimension's ServerLevel
 * has been created. The flag is checked by IplDhSkipHostingDimensionMixin
 * to cancel DH's DhLevel creation for the hosting dimension.
 *
 * We inject at RETURN because we can't inject at HEAD on a constructor
 * (requires static handler). DH creates its DhLevel after the ServerLevel
 * constructor returns, so setting the flag at RETURN is early enough.
 *
 * The flag stays set for a brief period (one server tick) and then gets
 * cleared by DhDimensionTracker's own tick logic.
 */
@Mixin(ServerLevel.class)
public class IplServerLevelDhTrackerMixin {

    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void ip_onServerLevelCreated(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        String dimId = self.dimension().location().toString();
        if (dimId.equals("ipl_sable:sublevels")) {
            // Set the flag. DH's DhLevel creation happens after ServerLevel
            // construction, so this flag will be set in time.
            // The flag auto-clears after 1 second (see DhDimensionTracker).
            DhDimensionTracker.setHostingDimensionLoading(true, 1000);
        }
    }
}
