package ipl.sable.mixin;

import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.transit.IplShipNetherPortal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SHIP-BORNE PORTAL feature hook: restore anchored portal shapes to world coordinates and
 * release their anchors before a ship is disassembled back into blocks.
 *
 * <p>This is the one piece of the old per-mod assembler mixin that was a FEATURE
 * integration, not a bug patch: the level-identity bugs it papered over (disassembly target,
 * ground/build-height checks, {@code getChunk}) are now covered structurally by the
 * interaction-armed world-frame router. Portal-anchor release, however, is our own ship-
 * borne-portal lifecycle and needs the disassembly transform (goal + rotation), which only
 * exists at this chokepoint — the single static method every Simulated disassembly path
 * (assembler block, commands) funnels through.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
public abstract class IplDisassemblyPortalRestoreMixin {

    @Inject(method = "disassembleSubLevel", at = @At("HEAD"), remap = false, require = 0)
    private static void ipl$restoreAnchoredPortals(
        Level level, SubLevel subLevel, BlockPos subLevelAnchor, BlockPos goal,
        Rotation rotation, boolean playSound, CallbackInfo ci
    ) {
        ServerLevel parent = IplDimAgnostic.getServerParentLevel(subLevel);
        if (parent != null) {
            IplShipNetherPortal.restoreOnDisassembly(parent, subLevel, subLevelAnchor, goal, rotation);
        }
    }
}
