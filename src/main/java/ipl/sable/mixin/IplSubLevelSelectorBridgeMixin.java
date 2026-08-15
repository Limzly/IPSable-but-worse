package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import ipl.sable.dim.IplDimAgnostic;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Sub-level command selectors see HOSTED ships. Every {@code /sable} command taking a
 * sub_level argument resolves through {@code SubLevelSelector.getSubLevels}, which
 * enumerates the COMMAND SOURCE level's container — empty post-rehome (hosted ships
 * live in the hosting dimension's container with a parent pointer; the same disease
 * as the ship-portal anchor lookup). The wrap augments the candidate pool with hosted
 * ships whose effective parent is the source level, BEFORE the selector's own
 * filtering/sorting/limits run — so @nearest-style modifiers stay correct.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.command.argument.SubLevelSelector", remap = false)
public abstract class IplSubLevelSelectorBridgeMixin {

    @WrapOperation(
        method = "getSubLevels",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getAllSubLevels()Ljava/util/List;",
            remap = false
        ),
        require = 0
    )
    private List<ServerSubLevel> ipl$includeHostedShips(
        ServerSubLevelContainer container, Operation<List<ServerSubLevel>> original
    ) {
        List<ServerSubLevel> base = original.call(container);
        Level self = ((SubLevelContainer) container).getLevel();
        if (self == null || IplDimAgnostic.isHostingLevel(self)) return base;
        SubLevelContainer hosting = IplDimAgnostic.getHostingContainerFor(self);
        if (hosting == null || hosting == (Object) container) return base;

        List<ServerSubLevel> out = new ArrayList<>(base);
        for (SubLevel sub : hosting.getAllSubLevels()) {
            if (!(sub instanceof ServerSubLevel serverSub) || serverSub.isRemoved()) continue;
            if (IplDimAgnostic.getParentLevel(serverSub) != self) continue;
            if (!out.contains(serverSub)) out.add(serverSub);
        }
        return out;
    }
}
