package ipl.sable.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplWorldFrameContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Keep hosted physics actor identity in its real hosting level. The context is only a
 * coordinate bridge for legacy terrain probes; Atlas images own rigid-body contact.
 *
 * <p>{@code IplHostedBeTickContextMixin} arms {@link IplWorldFrameContext} around
 * regular chunk BE ticks — but {@code sable$physicsTick} actors (Offroad wheel mounts, lift
 * providers, contraptions) run from {@code ServerSubLevel.prePhysicsTick} outside that wrap.
 * The bridge only redirects explicit world-frame terrain probes; the actor's {@code Level},
 * manager bucket, body, and constraints remain in {@code ipl_sable:sublevels}.
 */
@Pseudo
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class IplHostedPhysicsTickContextMixin {

    @WrapMethod(method = "prePhysicsTick", remap = false)
    private void ipl$worldFrameDuringPhysicsActors(
        SubLevelPhysicsSystem physicsSystem, RigidBodyHandle handle, double timeStep,
        Operation<Void> original
    ) {
        ServerSubLevel self = (ServerSubLevel) (Object) this;
        ServerLevel parent = IplDimAgnostic.isHosted(self)
            ? IplDimAgnostic.getServerParentLevel(self) : null;
        if (parent == null) {
            original.call(physicsSystem, handle, timeStep);
            return;
        }
        ServerLevel previous = IplWorldFrameContext.push(parent);
        try {
            original.call(physicsSystem, handle, timeStep);
        } finally {
            IplWorldFrameContext.pop(previous);
        }
    }
}
