package ipl.sable.duck;

import org.joml.Quaterniondc;
import qouteall.imm_ptl.core.portal.Portal;

/** Server-only control over Simulated's live staff constraint during a frame change. */
public interface IplStaffDragSessionControl {

    /** Body transited: move the live native motor target and basis into portal destination frame. */
    void ipl$reframeAfterTransit(Portal portal);

    /**
     * The dragging PLAYER teleported through a portal: rotate the stored player-relative
     * cursor vector so the physics substeps between the teleport and the client's next
     * (already new-frame) drag packet stay continuous.
     */
    void ipl$rotateRelativeGoal(Quaterniondc portalRotation);

    /** Remove the native staff joint before its session leaves Simulated's map. */
    void ipl$removeConstraint();
}
