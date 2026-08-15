package qouteall.imm_ptl.core.platform_specific;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.IPModMainClient;
import qouteall.imm_ptl.core.compat.IPModInfoChecking;
import qouteall.imm_ptl.core.compat.iris_compatibility.ExperimentalIrisPortalRenderer;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.core.portal.EndPortalEntity;
import qouteall.imm_ptl.core.portal.LoadingIndicatorEntity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalTrackedPortal;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;
import qouteall.imm_ptl.core.portal.nether_portal.GeneralBreakablePortal;
import java.nio.file.Path;
import qouteall.imm_ptl.core.portal.nether_portal.NetherPortalEntity;
import qouteall.imm_ptl.core.render.LoadingIndicatorRenderer;
import qouteall.imm_ptl.core.render.PortalEntityRenderer;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.my_util.MyTaskList;

import java.util.Arrays;

public class IPModEntryClient {
    


    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void initPortalRenderers(EntityRenderersEvent.RegisterRenderers event) {
        
        Arrays.stream(new EntityType<?>[]{
            Portal.ENTITY_TYPE,
            NetherPortalEntity.ENTITY_TYPE,
            EndPortalEntity.ENTITY_TYPE,
            Mirror.ENTITY_TYPE,
            BreakableMirror.ENTITY_TYPE,
            GlobalTrackedPortal.ENTITY_TYPE,
            WorldWrappingPortal.ENTITY_TYPE,
            VerticalConnectingPortal.ENTITY_TYPE,
            GeneralBreakablePortal.ENTITY_TYPE
        }).forEach(
            entityType -> event.registerEntityRenderer(
                entityType,
                (EntityRendererProvider) PortalEntityRenderer::new
            )
        );
        
        event.registerEntityRenderer(
            LoadingIndicatorEntity.entityType,
            LoadingIndicatorRenderer::new
        );
        
    }

    public void onInitializeClient(IEventBus modEventBus) {
        IPModMainClient.init();

        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, IPModEntryClient::initPortalRenderers);
        
        boolean isSodiumPresent =
            ModList.get().isLoaded("embeddium") || ModList.get().isLoaded("sodium");
        if (isSodiumPresent) {
            Helper.log("Sodium is present");
            
            SodiumInterface.invoker = new SodiumInterface.OnSodiumPresent();
            
            // Sodium compat is pretty ok now. No warning needed.
//            IPGlobal.clientTaskList.addTask(MyTaskList.oneShotTask(() -> {
//                if (IPGlobal.enableWarning) {
//                    CHelper.printChat(
//                        Component.translatable("imm_ptl.sodium_warning")
//                            .append(IPMcHelper.getDisableWarningText())
//                    );
//                }
//            }));
        }
        else {
            Helper.log("Sodium is not present");
        }
        
        ipl.sable.client.IplStaffPortalBeamStage.init();

        // Sable-fork addition: when Veil is present, hook its preprocessor
        // pipeline so that IP's iportal_ClippingEquation injection survives
        // Veil's vanilla-shader recompile pass. Without this, Veil throws away
        // the linked programs IP injected into and links replacement ones from
        // un-injected source -- breaking clipping for every vanilla rendertype.
        // See ipl.sable.render.IplVeilShaderPreProcessor javadoc for the full
        // diagnosis. Guarded by ModList so the JVM doesn't try to verify the
        // Veil-referencing class when Veil is absent.
        if (ModList.get().isLoaded("veil")) {
            Helper.log("Veil is present -- registering IPL clip preprocessor");
            ipl.sable.render.IplVeilCompat.init(modEventBus);
        }

        if (ModList.get().isLoaded("iris")) {
            Helper.log("Iris is present");
            IrisInterface.invoker = new IrisInterface.OnIrisPresent();
            ExperimentalIrisPortalRenderer.init();
            
            // Auto-enable compatibility render mode when both Iris AND Distant Horizons
            // are present. The normal IrisPortalRenderer uses a deferred framebuffer blit
            // that breaks under Iris 1.8.14+ when DH is also re-entering the render loop
            // (GL Error 1281 → cascade to Flywheel fallback → invisible blocks).
            // The compatibility renderer avoids the broken blit entirely at the cost of
            // portal-in-portal rendering. This is the correct default for modpacks like
            // Create Convoluted that run Iris + DH together.
            //
            // We set IPGlobal.renderMode immediately (safe — just a static field).
            // We defer the config field update to CLIENT_TASK_LIST because IPConfig.getConfig()
            // returns null during onInitializeClient (configHolder is initialized later in
            // IPModMain.init(), which runs after onInitializeClient in IPModEntry's constructor).
            // The deferred task also re-applies renderMode after onConfigChanged() may have
            // reset it back to normal from the config's default compatibilityRenderMode=false.
            if (ModList.get().isLoaded("distanthorizons")) {
                Helper.log("Distant Horizons is also present -- auto-enabling compatibility render mode");
                IPGlobal.renderMode = IPGlobal.RenderMode.compatibility;
                
                // Patch DH config to ignore the ipl_sable:sublevels hosting dimension.
                // This prevents DH's LocalSaveStructure from accumulating data paths
                // across dimensions, which causes LOD data collision (wrong terrain
                // visible when portals link dimensions).
                try {
                    Path configDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config");
                    ipl.sable.dh.DhConfigPatch.patchConfig(configDir);
                } catch (Throwable t) {
                    Helper.err("Failed to patch DH config: " + t.getMessage());
                }
                
                IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                    try {
                        IPConfig config = IPConfig.getConfig();
                        if (config != null) {
                            // Only force-enable if the user hasn't explicitly set it to false
                            // (we can't distinguish "default false" from "user set false" here,
                            // so we always force it to true for the Iris+DH combo. Users who
                            // want normal mode can disable this auto-detection by removing
                            // either Iris or DH, or by patching this check.)
                            config.compatibilityRenderMode = true;
                            IPGlobal.renderMode = IPGlobal.RenderMode.compatibility;
                        }
                    } catch (Throwable t) {
                        Helper.err("Failed to apply auto compatibility render mode: " + t.getMessage());
                    }
                }));
            }
            
            IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.oneShotTask(() -> {
                if (IPConfig.getConfig().shouldDisplayWarning("iris")) {
                    CHelper.printChat(
                        Component.translatable("imm_ptl.iris_warning")
                            .append(IPMcHelper.getDisableWarningText("iris"))
                    );
                }
            }));
        }
        else {
            Helper.log("Iris is not present");
        }
        
        IPModInfoChecking.initClient();
    }
    
}
