package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Preserve honest hosting-level identity for hosted interactions while mapping only explicit
 * parent-frame terrain coordinates required by legacy assembly/disassembly handlers.
 *
 * <p>The BE-tick and physics-actor arming sites cover code that runs from ticks — but a
 * right-click handler runs synchronously from the interaction packet: Simulated's physics
 * assembler {@code placeIntoWorld} (ground checks, {@code getChunk}, build-height reads,
 * disassembly block placement — all via {@code this.getLevel()} = the hosting void), the
 * swivel bearing's split-into-physics-body action, throttle grips. With the context armed
 * for the click, {@code IplHostedWorldFrameRouterMixin} routes every world-frame access of
 * those handlers to the ship's actual dimension — structurally, for every mod. This is what
 * replaces the per-mod assembler mixin.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class IplHostedInteractionContextMixin {

    @WrapMethod(method = "useItemOn")
    private InteractionResult ipl$armWorldFrameForHostedUse(
        ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
        BlockHitResult hitResult, Operation<InteractionResult> original
    ) {
        ServerLevel parent = level instanceof ServerLevel serverLevel
            ? IplWorldFrameContext.resolveParentForPlotInteraction(serverLevel, hitResult.getBlockPos())
            : null;
        if (parent == null) {
            return original.call(player, level, stack, hand, hitResult);
        }
        ServerLevel previous = IplWorldFrameContext.push(parent);
        try {
            return original.call(player, level, stack, hand, hitResult);
        } finally {
            IplWorldFrameContext.pop(previous);
        }
    }
}
