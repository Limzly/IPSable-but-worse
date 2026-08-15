package ipl.sable.atlas;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipl.sable.dim.IplDimAgnostic;
import ipl.sable.dim.IplSceneOwnership;
import ipl.sable.natives.IplRapierNatives;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ships in different parent dimensions must never collide.
 *
 * <p>Every hosted ship's real body lives in the hosting chart at PARENT-FRAME
 * coordinates — an overworld ship at (100, 70, 100) and a nether ship at (100, 70, 100)
 * overlap numerically while being worlds apart semantically. Chart tagging separates them
 * from each other's TERRAIN (per-chart static colliders), but real-vs-real dynamic pairs
 * share the hosting chart.
 *
 * <p>Fix: tag each hosted body's native collider info with a parent-frame id
 * ({@link IplRapierNatives#setParentFrame}); the dispatcher drops native-vs-native
 * contacts between differing nonzero frames. Because the native check keys on frame
 * identity per SHAPE (image colliders carry the far chart and skip it), straddle
 * image-vs-image contacts in a shared chart stay collidable with no carve-out, and no
 * per-pair bookkeeping exists on either side. An unresolved parent (boot restore window)
 * gets a per-body sentinel frame that matches nothing — conservatively excluded, same as
 * the old pair derivation.
 *
 * <p>The JNI call happens only when a ship's (scene, body, parent) identity changes:
 * parent flips in transit, body recreation on rehome, scene recreation on reload.
 */
public final class IplParentFrames {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-parent-frames");

    /** Stable id per parent level, 1-based; 0 is the native "untagged" wildcard. */
    private static final Map<ServerLevel, Integer> FRAME_IDS = new HashMap<>();
    private static int nextFrameId = 1;

    /** What the native side currently holds per hosted sub — skip the JNI call when
     *  unchanged, drop the entry when a push misses so it retries next tick. */
    private static final Map<UUID, Tagged> TAGGED = new HashMap<>();

    private record Tagged(long scene, int bodyId, int frame) {}

    private IplParentFrames() {}

    /** Sync parent-frame tags. Called once per hosting-container tick. */
    public static void reconcile(Iterable<? extends ServerSubLevel> subLevels) {
        if (!IplRapierNatives.isAvailable()) return;

        Set<UUID> live = new HashSet<>();
        for (ServerSubLevel sub : subLevels) {
            if (sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) continue;
            int bodyId = Rapier3D.getID(sub);
            if (bodyId < 0) continue;
            long scene = IplSceneOwnership.liveSceneHandle((ServerLevel) sub.getLevel());
            if (scene == 0) continue;

            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            // Sentinel for an unresolved parent: unique per body (ids are never
            // reused), negative so it can't alias a real frame id.
            int frame = parent != null
                ? FRAME_IDS.computeIfAbsent(parent, l -> nextFrameId++)
                : -(bodyId + 1);

            UUID id = sub.getUniqueId();
            live.add(id);
            Tagged cached = TAGGED.get(id);
            if (cached != null && cached.scene == scene
                && cached.bodyId == bodyId && cached.frame == frame) {
                continue;
            }
            if (IplRapierNatives.setParentFrame(scene, bodyId, frame)) {
                TAGGED.put(id, new Tagged(scene, bodyId, frame));
                LOG.debug("[IPL-PARENT-FRAME] body {} -> frame {} ({})", bodyId, frame,
                    parent == null ? "unresolved" : parent.dimension().location());
            } else {
                // Body not registered in the scene yet (add still in flight) — leave
                // untracked so the push retries next hosting tick.
                TAGGED.remove(id);
            }
        }
        // Removed subs need no native clear: the info dies with the body and ids are
        // never reused. Entries skipped above (body/scene not ready) re-push later.
        TAGGED.keySet().retainAll(live);
    }

    public static void clearAll() {
        TAGGED.clear();
        FRAME_IDS.clear();
        nextFrameId = 1;
    }
}
