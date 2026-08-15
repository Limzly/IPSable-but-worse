package ipl.sable.dim;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No entity teleports into the hosting void.
 *
 * <p>Mods that teleport to "the block's level" record a hosted block entity's honest
 * dimension — {@code ipl_sable:sublevels} — and later resolve it by key and teleport
 * there (Waystones+Sable being the canonical case: its compat maps the waystone's plot
 * position through {@code logicalPose()} to the ship's VISIBLE coordinates, then
 * teleports into the recorded dimension — under hosting, the void). None of the frame
 * bridges can see this: the destination is a dimension KEY resolved through
 * {@code server.getLevel}, not a plot access.
 *
 * <p>The generic invariant this guard enforces: a teleport targeting the hosting
 * dimension at NON-PLOT coordinates is always wrong — nothing exists there, and the
 * mapped position always lands inside (or just above) some hosted ship's parent-frame
 * bounding box, which names the level the caller actually meant. Plot-coordinate
 * teleports (debug/rescue tooling) pass through untouched.
 *
 * <p>Kill switch: {@code -Dipl.sable.teleportGuard=false}.
 */
public final class IplHostingTeleportGuard {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-teleport-guard");

    private static final boolean ENABLED =
        !"false".equalsIgnoreCase(System.getProperty("ipl.sable.teleportGuard", "true"));

    /** Lateral slack + headroom over the ship bbox: teleport landings sit on decks. */
    private static final double XZ_MARGIN = 2.0;
    private static final double Y_BELOW = 2.0;
    private static final double Y_ABOVE = 6.0;

    private IplHostingTeleportGuard() {}

    /** The level this teleport actually means: {@code target} unless it is the hosting
     *  dim at world-frame coordinates matching a hosted ship — then the ship's parent. */
    public static ServerLevel redirect(ServerLevel target, double x, double y, double z) {
        if (!ENABLED || target == null || !IplDimAgnostic.isHostingLevel(target)) {
            return target;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(target);
        if (container == null) return target;
        if (container.inBounds(SectionPos.blockToSectionCoord((int) Math.floor(x)),
            SectionPos.blockToSectionCoord((int) Math.floor(z)))) {
            return target; // genuine plot-space teleport (debug/rescue) — allowed
        }

        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved() || !IplDimAgnostic.isHosted(sub)) continue;
            BoundingBox3dc bb = sub.boundingBox();
            if (x < bb.minX() - XZ_MARGIN || x > bb.maxX() + XZ_MARGIN
                || y < bb.minY() - Y_BELOW || y > bb.maxY() + Y_ABOVE
                || z < bb.minZ() - XZ_MARGIN || z > bb.maxZ() + XZ_MARGIN) {
                continue;
            }
            ServerLevel parent = IplDimAgnostic.getServerParentLevel(sub);
            if (parent != null) {
                LOG.info("[IPL-TELEPORT-GUARD] redirected hosting-dim teleport at "
                    + "({}, {}, {}) to ship {}'s parent {}", (int) x, (int) y, (int) z,
                    sub.getUniqueId(), parent.dimension().location());
                return parent;
            }
        }

        LOG.warn("[IPL-TELEPORT-GUARD] teleport into the hosting void at ({}, {}, {}) "
            + "matches no hosted ship — letting it through", (int) x, (int) y, (int) z);
        return target;
    }
}
