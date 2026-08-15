package ipl.sable.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code Level.getEntities()} (protected) for the client-side cross-world entity
 * lookups in {@code IplHostedClientEntityLookupMixin}. Reading through the RAW getter keeps
 * the fallthrough non-recursive.
 */
@Mixin(Level.class)
public interface IplLevelEntityGetterInvoker {

    @Invoker("getEntities")
    LevelEntityGetter<Entity> ipl$entityGetter();
}
