package ipl.sable.mixin;

import dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Sable's opaque native joint id for in-place staff motor-frame updates. */
@Pseudo
@Mixin(value = RapierConstraintHandle.class, remap = false)
public interface IplRapierConstraintHandleAccessor {

    @Accessor(value = "handle", remap = false)
    long ipl$getNativeHandle();
}
