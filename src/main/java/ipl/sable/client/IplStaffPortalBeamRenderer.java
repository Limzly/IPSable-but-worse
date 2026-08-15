package ipl.sable.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffClientHandler;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import ipl.sable.mixin.client.IplPhysicsStaffBeamAccessorMixin;
import ipl.sable.mixin.client.IplPhysicsStaffBeamInvokerMixin;
import ipl.sable.mixin.client.IplPhysicsStaffClientHandlerAccessorMixin;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws the staff beam per IP world pass from one shared {@link IplStaffBeamRoutes.Route}.
 *
 * <p>Emission rules (the anti-flicker contract):
 * <ul>
 *   <li><b>Main pass draws every segment lying in the main world.</b> The near segment
 *       (staff → first aperture) therefore renders whether or not the portal is on screen,
 *       and a same-dimension far segment renders at the exit aperture even when the player
 *       is not looking through the portal — both endpoints exist in this world.</li>
 *   <li><b>Each portal pass draws every segment physically in its rendered world.</b>
 *       IP's stencil and destination clip decide whether it is visible through that
 *       aperture. This makes a beam a real world object from either portal face and
 *       through recursive portal renders, rather than only along its own traversal path.</li>
 *   <li><b>The staff focus position is sampled only on the main pass</b> and cached per
 *       owner. Portal passes reuse the cached tip: recomputing it there mixes the virtual
 *       portal camera with root-world player state and made the beam pivot with the
 *       camera (the old flicker/pivot bug).</li>
 * </ul>
 */
public final class IplStaffPortalBeamRenderer {

    /** Suppresses Simulated's main-world-only draw while this renderer owns the recursive pass. */
    private static final ThreadLocal<Boolean> PHYSICAL_BEAM_PASS = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<IplStaffBeamRoutes.Run> ACTIVE_RUN =
        new ThreadLocal<>();

    /** Per-owner staff tip sampled on the main pass; reused verbatim by portal passes. */
    private static final Map<UUID, Vec3> FOCUS = new HashMap<>();

    private IplStaffPortalBeamRenderer() {}

    public static void render(PoseStack poseStack, Camera camera, ClientLevel renderLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.level == null || renderLevel == null) return;

        PhysicsStaffClientHandler handler =
            dev.simulated_team.simulated.SimulatedClient.PHYSICS_STAFF_CLIENT_HANDLER;
        Object2ObjectMap<UUID, Object> beams = ((IplPhysicsStaffClientHandlerAccessorMixin) (Object) handler)
            .ipl$getBeams();
        if (beams.isEmpty()) {
            FOCUS.clear();
            return;
        }
        FOCUS.keySet().retainAll(beams.keySet());

        // IP reuses one LevelRenderer across recursive worlds. Its `level` field remains
        // the root level, so portal-pass geometry must select WorldRenderInfo.world instead.
        ClientLevel activeLevel = WorldRenderInfo.isRendering()
            ? WorldRenderInfo.getTopRenderInfo().world : renderLevel;
        boolean mainPass = !WorldRenderInfo.isRendering();
        float partialTick = AnimationTickHolder.getPartialTicks();
        SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
        boolean drew = false;

        for (Object2ObjectMap.Entry<UUID, Object> entry : beams.object2ObjectEntrySet()) {
            if (!(entry.getValue() instanceof PhysicsStaffClientHandler.PhysicsBeam beam)) continue;
            Player owner = findPlayer(entry.getKey());
            if (owner == null) continue;

            IplPhysicsStaffBeamAccessorMixin access =
                (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
            Vec3 localAnchor = access.ipl$getPreviousEnd().lerp(access.ipl$getEnd(), partialTick);
            ClientSubLevel sub = findHostedSubLevel(localAnchor);
            if (sub == null) continue;

            Vec3 staffStart = staffStart(owner, entry.getKey(), beam, mainPass, partialTick);

            IplStaffBeamRoutes.registerBeamOwner(beam, entry.getKey());

            IplStaffBeamRoutes.Route route = IplStaffBeamRoutes.resolve(
                entry.getKey(), owner.level(), staffStart, sub, localAnchor, partialTick
            );
            if (route == null) continue;

            // ONE continuous polyline, split into runs only where it changes world.
            for (IplStaffBeamRoutes.Run run : IplStaffBeamRoutes.runs(route)) {
                if (!shouldDrawInThisPass(run, activeLevel)) continue;
                renderPhysicalBeam(beam, run, poseStack, buffer, camera.getPosition(), partialTick);
                drew = true;
            }
        }

        if (drew) buffer.draw();
    }

    /**
     * The staff tip for this pass. Sampled fresh on the main pass — where the real camera is
     * active — and cached; portal passes replay the cached tip so route geometry is identical
     * across all passes of a frame. Falls back to the beam's interpolated network start when
     * a portal pass runs before any main pass produced a sample.
     */
    private static Vec3 staffStart(
        Player owner, UUID ownerId, PhysicsStaffClientHandler.PhysicsBeam beam,
        boolean mainPass, float partialTick
    ) {
        if (mainPass) {
            Vec3 fresh = staffFocus(owner, partialTick);
            FOCUS.put(ownerId, fresh);
            return fresh;
        }
        Vec3 cached = FOCUS.get(ownerId);
        if (cached != null) return cached;
        IplPhysicsStaffBeamAccessorMixin access = (IplPhysicsStaffBeamAccessorMixin) (Object) beam;
        return access.ipl$getPreviousStart().lerp(access.ipl$getStart(), partialTick);
    }

    /** Same physical held-item tip used by both beam geometry and held-staff aiming. */
    public static Vec3 staffFocus(Player owner, float partialTick) {
        boolean mainHand = owner.getMainHandItem().getItem() instanceof PhysicsStaffItem
            || !(owner.getOffhandItem().getItem() instanceof PhysicsStaffItem);
        return PhysicsStaffClientHandler.getStaffFocusPos(owner, mainHand, partialTick);
    }

    /** Draw any physical run in its world; IP clips it to the active portal aperture. */
    private static boolean shouldDrawInThisPass(
        IplStaffBeamRoutes.Run run, ClientLevel renderLevel
    ) {
        return run.dim().equals(renderLevel.dimension());
    }

    public static boolean isPhysicalBeamPass() {
        return PHYSICAL_BEAM_PASS.get();
    }

    public static IplStaffBeamRoutes.Run getActiveRun() {
        return ACTIVE_RUN.get();
    }

    private static void renderPhysicalBeam(
        PhysicsStaffClientHandler.PhysicsBeam beam, IplStaffBeamRoutes.Run run,
        PoseStack poseStack, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTick
    ) {
        boolean previous = PHYSICAL_BEAM_PASS.get();
        PHYSICAL_BEAM_PASS.set(true);
        ACTIVE_RUN.set(run);
        try {
            ((IplPhysicsStaffBeamInvokerMixin) (Object) beam).ipl$render(
                run.vertices().get(0).point(),
                run.vertices().get(run.vertices().size() - 1).point(),
                poseStack, buffer, camera, partialTick
            );
        } finally {
            ACTIVE_RUN.remove();
            PHYSICAL_BEAM_PASS.set(previous);
        }
    }

    private static Player findPlayer(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.level == null ? null : minecraft.level.getPlayerByUUID(id);
        if (player != null) return player;
        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            player = world.getPlayerByUUID(id);
            if (player != null) return player;
        }
        return null;
    }

    /** Beam anchors are plot coordinates, so resolve through hosting container, never main world. */
    public static ClientSubLevel findHostedSubLevel(Vec3 localAnchor) {
        SubLevelContainer container = IplClientHostedLookup.getHostingContainerOrNull();
        if (container == null) return null;
        ChunkPos chunk = new ChunkPos(BlockPos.containing(localAnchor));
        dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(chunk);
        SubLevel sub = plot == null ? null : plot.getSubLevel();
        return sub instanceof ClientSubLevel clientSub ? clientSub : null;
    }
}
