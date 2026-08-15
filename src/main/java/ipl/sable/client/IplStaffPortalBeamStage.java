package ipl.sable.client;

import ipl.sable.mixin.client.IplStaffPortalBeamPassMixin;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Vanilla NeoForge stage, independent from Veil's shader and buffer pipeline. */
public final class IplStaffPortalBeamStage {

    private static boolean initialized;

    private IplStaffPortalBeamStage() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, event -> {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
            var level = ((IplStaffPortalBeamPassMixin) (Object) event.getLevelRenderer()).ipl$getLevel();
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                IplEnteringVolumeRenderer.render(event.getPoseStack(),
                    net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource(), event.getCamera(), level);
            } else {
                IplStaffPortalBeamRenderer.render(event.getPoseStack(), event.getCamera(), level);
            }
        });
    }
}
