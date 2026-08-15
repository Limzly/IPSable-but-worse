package ipl.sable.mixin;

import dev.ryanhcode.sable.api.physics.object.rope.RopeHandle;
import dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The rope's native handle is protected; the portal-seam manager needs its rope id. */
@Pseudo
@Mixin(value = RopePhysicsObject.class, remap = false)
public interface IplRopeObjectAccess {

    @Accessor(value = "handle", remap = false)
    RopeHandle ipl$handle();
}
