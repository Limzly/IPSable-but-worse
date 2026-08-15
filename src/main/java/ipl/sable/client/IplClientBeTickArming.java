package ipl.sable.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplClientWorldFrameContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client half of {@code IplHostedBeTickContextMixin}: arm
 * {@link IplClientWorldFrameContext} around a hosted plot BE's CLIENT tick.
 *
 * <p>Separate class (all-common-types signature) so the merged common mixin never carries
 * client types in its own descriptors — the dedicated server takes the guard branch and
 * this class is never loaded there.
 */
public final class IplClientBeTickArming {

    private IplClientBeTickArming() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void tickArmed(
        BlockEntityTicker ticker, Level level, BlockPos pos, BlockState state,
        BlockEntity blockEntity, Operation<Void> original
    ) {
        ClientLevel parent = IplClientWorldFrameContext.resolveParentForPlotBe(level, pos);
        if (parent == null) {
            original.call(ticker, level, pos, state, blockEntity);
            return;
        }
        ClientLevel prev = IplClientWorldFrameContext.push(parent);
        try {
            original.call(ticker, level, pos, state, blockEntity);
        } finally {
            IplClientWorldFrameContext.pop(prev);
        }
    }
}
