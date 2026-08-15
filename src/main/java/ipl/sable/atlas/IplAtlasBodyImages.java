package ipl.sable.atlas;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Atlas presence for a hosted body outside its real dimension.
 *
 * <p>The body, plot storage, block entities, joints, ropes, and third-party level state stay
 * honestly in {@code ipl_sable:sublevels}. This registry gives the body one persistent image
 * in its current parent chart. Rapier maps contacts from that image back to the real body, so
 * external terrain is a physics projection rather than a Java-level identity substitution.
 */
public final class IplAtlasBodyImages {

    private static final Map<UUID, Image> IMAGES = new HashMap<>();

    private IplAtlasBodyImages() {}

    private static final class Image {
        final ServerSubLevel sub;
        final ServerLevel chart;
        final long scene;
        final int bodyId;
        long handle;

        Image(
            ServerSubLevel sub, ServerLevel chart, long scene, int bodyId, long handle
        ) {
            this.sub = sub;
            this.chart = chart;
            this.scene = scene;
            this.bodyId = bodyId;
            this.handle = handle;
        }
    }

    /** Reconcile one hosted body with its current parent chart. Called from the hosting tick. */
    public static void reconcile(ServerSubLevel sub) {
        if (!IplRapierNatives.isAvailable() || sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) {
            remove(sub.getUniqueId());
            return;
        }

        ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
        if (parent == null) {
            remove(sub.getUniqueId());
            return;
        }

        long parentScene = IplSceneOwnership.liveSceneHandle(parent);
        if (parentScene == 0) return;
        int bodyId = Rapier3D.getID(sub);
        UUID id = sub.getUniqueId();
        Image current = IMAGES.get(id);

        if (current != null && (current.chart != parent || current.scene != parentScene
            || current.bodyId != bodyId)) {
            ipl$removeNative(current);
            IMAGES.remove(id);
            current = null;
        }

        if (current == null) {
            // The image is created through the parent chart view but resolves the real body
            // from Atlas's shared world registry. The identity prefix preserves the body pose
            // carried by Sable while the collider's chart selects parent terrain.
            long handle = IplRapierNatives.createImageCollider(
                parentScene, bodyId, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);
            if (handle < 0) return;
            IMAGES.put(id, new Image(sub, parent, parentScene, bodyId, handle));
        }
    }

    /** Reconcile all real hosted bodies once per hosting-container tick. */
    public static void reconcileAll(Iterable<? extends ServerSubLevel> subLevels) {
        for (ServerSubLevel sub : subLevels) {
            reconcile(sub);
        }

        Iterator<Map.Entry<UUID, Image>> it = IMAGES.entrySet().iterator();
        while (it.hasNext()) {
            Image image = it.next().getValue();
            if (!image.sub.isRemoved()) continue;
            ipl$removeNative(image);
            it.remove();
        }
    }

    public static void remove(UUID id) {
        Image image = IMAGES.remove(id);
        if (image != null && IplRapierNatives.isAvailable()) {
            ipl$removeNative(image);
        }
    }

    /**
     * Remove the native collider ONLY while the image's chart still resolves to the
     * SAME live scene it was created in. Server-stop closes levels overworld-first,
     * and container.close removes hosted ships AFTER their parent scenes died — the
     * stored raw handle then points at freed native memory, and dereferencing it hung
     * the server thread inside {@code removeImageCollider} forever (the 28s watchdog
     * stall at shutdown). A dead scene took its colliders with it; skipping is correct.
     */
    private static void ipl$removeNative(Image image) {
        if (IplSceneOwnership.liveSceneHandle(image.chart) != image.scene) return;
        IplRapierNatives.removeImageCollider(image.scene, image.bodyId, image.handle);
    }

    public static void clearAll() {
        // Server-stop teardown can dispose a chart before Java reaches its final cleanup
        // hook. The shared native world then owns the remaining collider destruction; do not
        // dereference a stale raw scene handle during shutdown.
        IMAGES.clear();
    }
}
