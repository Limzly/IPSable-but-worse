package ipl.sable.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.Plane;

/**
 * Render-thread state for drawing a straddling hosted sub-level's DEST-SIDE projection.
 *
 * <p>While set, the sub-level renders with the portal-mapped pose
 * ({@code IplClientSubLevelPoseOverrideMixin}) and the legacy source-clip bracket
 * ({@code SableSourceClipMixin} → {@code SourceClipPortalFinder}) installs the provided
 * complementary plane instead of searching for one — so the projection shows exactly the
 * through-the-portal portion, cut at the mapped portal plane.
 */
public final class IplStraddleRenderState {

    @Nullable private static ClientSubLevel activeSub;
    @Nullable private static Pose3dc activePose;
    @Nullable private static Plane activePlane;
    @Nullable private static Portal activePortal;
    private static final java.util.ArrayDeque<State> suspended = new java.util.ArrayDeque<>();

    private record State(
        @Nullable ClientSubLevel sub, @Nullable Pose3dc pose,
        @Nullable Plane plane, @Nullable Portal portal
    ) {}

    private IplStraddleRenderState() {}

    public static void set(ClientSubLevel sub, Pose3dc mappedPose, Plane destPlane, Portal portal) {
        // Preserve outer state for recursive portal rendering.
        if (activeSub != null) {
            suspended.addLast(new State(activeSub, activePose, activePlane, activePortal));
        }
        activeSub = sub;
        activePose = mappedPose;
        activePlane = destPlane;
        activePortal = portal;
    }

    public static void clear() {
        State previous = suspended.pollLast();
        if (previous != null) {
            activeSub = previous.sub();
            activePose = previous.pose();
            activePlane = previous.plane();
            activePortal = previous.portal();
            return;
        }
        activeSub = null;
        activePose = null;
        activePlane = null;
        activePortal = null;
    }

    public static boolean isActiveFor(@Nullable Object sub) {
        return activeSub != null && activeSub == sub;
    }

    @Nullable
    public static Pose3dc getPoseFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePose : null;
    }

    @Nullable
    public static Plane getPlaneFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePlane : null;
    }

    @Nullable
    public static Portal getPortalFor(@Nullable Object sub) {
        return isActiveFor(sub) ? activePortal : null;
    }

}
