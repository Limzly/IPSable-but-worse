package ipl.sable.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code LaunchedPlungerEntity.tick} guards its TARGET_POS update with
 * {@code if (!level.isClientSide && other != null)} — on the client that condition never
 * passes, so every client tick locally clobbers the server-synced TARGET_POS with
 * {@code Vec3.ZERO}. The renderer only reads TARGET_POS as a fallback when the partner
 * plunger's client entity is missing or removed (which our plot-space entity tracking can
 * produce transiently), and in that exact case it draws the rope to the clobbered (0,0,0)
 * instead of the partner's synced plot position.
 *
 * <p>Fix: capture TARGET_POS at tick entry and restore it at tick exit when the client
 * zeroed a previously-valid target on a still-paired plunger (OTHER_PLUNGER_ID != -1).
 * Unpaired plungers keep the intended zeroing.
 */
@Pseudo
@Mixin(
    targets = "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
    remap = false)
public abstract class IplPlungerClientTargetFixMixin {

    @Unique
    private static final Logger IPL$LOG = LoggerFactory.getLogger("ipl-plunger-target");

    @Unique
    private static final boolean IPL$ENABLED =
        !"false".equals(System.getProperty("ipl.sable.plungerTargetFix"));

    @Unique
    private static volatile EntityDataAccessor<Vec3> ipl$targetPos;

    @Unique
    private static volatile EntityDataAccessor<Integer> ipl$otherPlungerId;

    @Unique
    private static volatile boolean ipl$reflectionFailed;

    @Unique
    private static long ipl$lastRestoreLogMs;

    @Unique
    private Vec3 ipl$savedTarget;

    @SuppressWarnings("unchecked")
    @Unique
    private boolean ipl$resolveAccessors() {
        if (ipl$reflectionFailed) return false;
        if (ipl$targetPos != null) return true;
        try {
            Class<?> cls = Class.forName(
                "dev.simulated_team.simulated.content.entities.launched_plunger.LaunchedPlungerEntity",
                false, this.getClass().getClassLoader());
            ipl$otherPlungerId = (EntityDataAccessor<Integer>)
                cls.getField("OTHER_PLUNGER_ID").get(null);
            ipl$targetPos = (EntityDataAccessor<Vec3>) cls.getField("TARGET_POS").get(null);
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            ipl$reflectionFailed = true;
            IPL$LOG.warn("[IPL-PLUNGER-TARGET] accessor reflection failed; fix disabled", e);
            return false;
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void ipl$captureTarget(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!IPL$ENABLED || !self.level().isClientSide || !ipl$resolveAccessors()) return;
        this.ipl$savedTarget = self.getEntityData().get(ipl$targetPos);
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 0)
    private void ipl$restoreTarget(CallbackInfo ci) {
        Vec3 saved = this.ipl$savedTarget;
        this.ipl$savedTarget = null;
        if (saved == null || saved.equals(Vec3.ZERO)) return;

        Entity self = (Entity) (Object) this;
        if (!self.level().isClientSide) return;
        int otherId = self.getEntityData().get(ipl$otherPlungerId);
        if (otherId == -1) return;
        if (!self.getEntityData().get(ipl$targetPos).equals(Vec3.ZERO)) return;

        self.getEntityData().set(ipl$targetPos, saved);

        // The renderer only falls back to TARGET_POS when the partner's client entity is
        // missing or removed — that's the window where the zeroed value drew (0,0,0).
        Entity other = self.level().getEntity(otherId);
        if (other != null && !other.isRemoved()) return;
        long now = System.currentTimeMillis();
        if (now - ipl$lastRestoreLogMs > 1000) {
            ipl$lastRestoreLogMs = now;
            IPL$LOG.warn("[IPL-PLUNGER-TARGET] paired plunger id={} has {} partner "
                + "(id={}) client-side; restored TARGET_POS=({}, {}, {})",
                self.getId(), other == null ? "no" : "a removed", otherId,
                String.format("%.1f", saved.x), String.format("%.1f", saved.y),
                String.format("%.1f", saved.z));
        }
    }
}
