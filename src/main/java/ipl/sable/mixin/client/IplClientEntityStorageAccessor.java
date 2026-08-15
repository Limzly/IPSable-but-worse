package ipl.sable.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the client entity manager for {@code IplClientPlotEntityTicking}. */
@Mixin(ClientLevel.class)
public interface IplClientEntityStorageAccessor {

    @Accessor("entityStorage")
    TransientEntitySectionManager<Entity> ipl$entityStorage();
}
