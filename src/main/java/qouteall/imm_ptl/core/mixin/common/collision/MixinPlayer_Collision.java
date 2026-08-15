package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import qouteall.imm_ptl.core.ducks.IEEntity;

// Priority 900 (below default 1000): Sable 2.0's player_standup.PlayerMixin @WrapOperation
// targets the Level.noCollision call INSIDE this overwritten method. Mixin only permits
// injecting into a merged method when the injector OUTRANKS the merger — at the default
// equal priority it hard-fails the whole apply (boot FATAL). At 900 our overwrite lands
// first and Sable's wrap composes on top of it: IP's portal-aware collision box + Sable's
// sublevel-aware noCollision, which is exactly the semantics both mods want.
@SuppressWarnings("resource")
@Mixin(value = Player.class, priority = 900)
public abstract class MixinPlayer_Collision {
    
    /**
     * @author qouteall
     * @reason mixin does not allow cancel in redirect
     */
    @Overwrite
    public boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose pose) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        
        AABB box = this_.getDimensions(pose).makeBoundingBox(this_.position());
        AABB activeCollisionBox = ((IEEntity) this_).ip_getActiveCollisionBox(box);
        if (activeCollisionBox == null) {
            return true;
        }
        return this_.level().noCollision(
            this_, activeCollisionBox.deflate(1.0E-7)
            // TODO check the issue of wrongly crouch after going through a scaling portal
            //  when head is touching ceiling because of floating point error
        );
    }
}
