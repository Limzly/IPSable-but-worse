package ipl.sable.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code Entity.setLevel} for re-homing freshly constructed entities (drop items,
 * XP orbs) that hosted-BE code built against the hosting level but that belong in the
 * routed parent level. See {@code IplHostedWorldFrameRouterMixin#ipl$routeAddFreshEntity}.
 */
@Mixin(Entity.class)
public interface IplEntityLevelInvoker {

    @Invoker("setLevel")
    void ipl$invokeSetLevel(Level level);
}
