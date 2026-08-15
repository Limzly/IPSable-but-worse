package ipl.sable.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor for the package-private prediction handler (see IplPredictionBridgeMixin). */
@Mixin(ClientLevel.class)
public interface IplClientLevelPredictionAccessor {

    @Accessor("blockStatePredictionHandler")
    BlockStatePredictionHandler ipl$getBlockStatePredictionHandler();
}
