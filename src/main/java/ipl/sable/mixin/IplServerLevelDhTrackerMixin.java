package ipl.sable.mixin;

import com.mojang.datafixers.DataFixer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ipl.sable.dh.DhDimensionTracker;

/**
 * Sets a flag when the ipl_sable:sublevels hosting dimension is being
 * created. The flag is checked by IplDhSkipHostingDimensionMixin to
 * cancel DH's DhLevel creation for the hosting dimension.
 */
@Mixin(ServerLevel.class)
public class IplServerLevelDhTrackerMixin {

    @Inject(
        method = "<init>",
        at = @At("HEAD")
    )
    private void ip_onServerLevelInit(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension().location().toString().equals("ipl_sable:sublevels")) {
            DhDimensionTracker.setHostingDimensionLoading(true);
        }
    }

    @Inject(
        method = "<init>",
        at = @At("RETURN")
    )
    private void ip_onServerLevelInitReturn(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension().location().toString().equals("ipl_sable:sublevels")) {
            DhDimensionTracker.setHostingDimensionLoading(false);
        }
    }
}
