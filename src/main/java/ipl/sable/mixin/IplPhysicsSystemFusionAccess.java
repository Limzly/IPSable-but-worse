package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/**
 * Accessor surface the fused step driver (atlas M1, spec v3 §2.7) needs to re-drive
 * the private parts of {@link SubLevelPhysicsSystem}'s stock substep loop.
 */
@Pseudo
@Mixin(value = SubLevelPhysicsSystem.class, remap = false)
public interface IplPhysicsSystemFusionAccess {

    @Accessor(value = "currentSubstep", remap = false)
    void ipl$setCurrentSubstep(int substep);

    @Accessor(value = "queuedWakeUps", remap = false)
    Collection<ArbitraryPhysicsObject> ipl$queuedWakeUps();

    @Invoker(value = "updateAllPoses", remap = false)
    void ipl$updateAllPoses(ServerSubLevelContainer container);
}
