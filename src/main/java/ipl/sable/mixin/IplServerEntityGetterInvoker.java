package ipl.sable.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code ServerLevel.getEntities()} (protected) for the cross-level entity
 * lookups in {@link IplHostedEntityLookupMixin}. Reading through the RAW getter instead of
 * {@code ServerLevel.getEntity(UUID)} keeps the fallthrough non-recursive.
 */
@Mixin(ServerLevel.class)
public interface IplServerEntityGetterInvoker {

    @Invoker("getEntities")
    LevelEntityGetter<Entity> ipl$entityGetter();
}
