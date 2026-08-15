package ipl.sable.transit;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Per-thread scratch state that lets the entity-vs-ship collision loop cut individual
 * collision boxes at the portal plane instead of keeping or dropping whole blocks.
 *
 * <p><b>Why this exists.</b> {@code IplStraddleBlockClipMixin} filters the candidate
 * {@code BlockPos} iterable, which is the only granularity a position predicate can
 * offer: a block is either physically present on this side of the portal or it is not.
 * With an oblique portal (the 45-degree case) that quantises the physical boundary to
 * the sub-level's block lattice, so a ship that is only partly through the portal is
 * physically cut along block divisions rather than along the portal plane -- you can
 * stand on, or fall through, up to half a block of material that the render pass has
 * already clipped away. The visual seam and the collision seam disagree.
 *
 * <p><b>How this fixes it.</b> The block filter is deliberately generous: it keeps a
 * block whenever ANY part of its cube is still on the kept side. This class then trims
 * what survives. Sable's collision loop obtains each block's collision boxes through
 * {@code FastVoxelShapeIterable.sable$allBoxes()} and turns every box into an OBB; by
 * wrapping that iterator we hand back boxes already clipped against the portal
 * half-space, so the SAT solver sees the partial box that actually exists. The cut now
 * follows the plane down to sub-block precision rather than the lattice.
 *
 * <p><b>Frame and lifecycle.</b> Planes are plot-local, matching the coordinates the
 * collision loop works in before it applies the sub-level pose. {@link #setPlanes} is
 * called once per sub-level, at the point the candidate block iterable is created, and
 * is explicitly cleared for non-straddling sub-levels so state can never leak from one
 * sub-level to the next. {@link #noteBlock} is fed by the same filtering iterator that
 * drives every loop over those blocks, so the box clip always knows which block the
 * boxes it is being handed belong to. Everything is {@link ThreadLocal}: client and
 * server collision run concurrently.
 *
 * <p>Boxes that fall entirely behind the plane are dropped from the iterator, which is
 * what makes a block that survived the (generous) position filter contribute nothing
 * when the plane happens to miss all of its actual collision geometry.
 */
public final class IplStraddleCollisionClip {

    private IplStraddleCollisionClip() {
    }

    private static final class State {
        @Nullable
        List<IplStraddlePoseMap.LocalHalfSpace> planes;
        int blockX;
        int blockY;
        int blockZ;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    /** Installs (or with null, clears) the plot-local cuts for the sub-level being tested. */
    public static void setPlanes(@Nullable List<IplStraddlePoseMap.LocalHalfSpace> planes) {
        STATE.get().planes = (planes == null || planes.isEmpty()) ? null : planes;
    }

    /** Records which block the boxes handed to {@link #clip} belong to. */
    public static void noteBlock(int x, int y, int z) {
        State state = STATE.get();
        state.blockX = x;
        state.blockY = y;
        state.blockZ = z;
    }

    public static boolean isActive() {
        return STATE.get().planes != null;
    }

    /**
     * Wraps a block's collision-box iterator so every box is trimmed at the portal
     * plane. Returns the input untouched when no cut applies, so non-straddling ships
     * pay nothing beyond one thread-local read.
     */
    public static Iterator<BoundingBox3dc> clip(Iterator<BoundingBox3dc> boxes) {
        State state = STATE.get();
        List<IplStraddlePoseMap.LocalHalfSpace> planes = state.planes;
        if (planes == null) return boxes;
        return new ClippedIterator(boxes, planes, state.blockX, state.blockY, state.blockZ);
    }

    private static final class ClippedIterator implements Iterator<BoundingBox3dc> {
        private final Iterator<BoundingBox3dc> in;
        private final List<IplStraddlePoseMap.LocalHalfSpace> planes;
        private final double offsetX;
        private final double offsetY;
        private final double offsetZ;
        @Nullable
        private BoundingBox3dc next;

        ClippedIterator(Iterator<BoundingBox3dc> in,
                        List<IplStraddlePoseMap.LocalHalfSpace> planes,
                        int blockX, int blockY, int blockZ) {
            this.in = in;
            this.planes = planes;
            this.offsetX = blockX;
            this.offsetY = blockY;
            this.offsetZ = blockZ;
        }

        @Override
        public boolean hasNext() {
            while (next == null && in.hasNext()) {
                next = clipOne(in.next());
            }
            return next != null;
        }

        @Override
        public BoundingBox3dc next() {
            if (!hasNext()) throw new NoSuchElementException();
            BoundingBox3dc result = next;
            next = null;
            return result;
        }

        /**
         * Boxes arrive in block-local coordinates (0..1 within the block), so they are
         * lifted into plot-local space for the cut and pushed back afterwards -- the
         * caller adds the block position again itself.
         */
        @Nullable
        private BoundingBox3dc clipOne(BoundingBox3dc box) {
            double minX = box.minX() + offsetX;
            double minY = box.minY() + offsetY;
            double minZ = box.minZ() + offsetZ;
            double maxX = box.maxX() + offsetX;
            double maxY = box.maxY() + offsetY;
            double maxZ = box.maxZ() + offsetZ;

            boolean trimmed = false;
            for (IplStraddlePoseMap.LocalHalfSpace plane : planes) {
                BoundingBox3d cut = IplStraddlePoseMap.clipLocalBoxKeeping(
                    minX, minY, minZ, maxX, maxY, maxZ, plane);
                if (cut == null) return null;
                if (cut.minX != minX || cut.minY != minY || cut.minZ != minZ
                    || cut.maxX != maxX || cut.maxY != maxY || cut.maxZ != maxZ) {
                    trimmed = true;
                    minX = cut.minX;
                    minY = cut.minY;
                    minZ = cut.minZ;
                    maxX = cut.maxX;
                    maxY = cut.maxY;
                    maxZ = cut.maxZ;
                }
            }
            if (!trimmed) return box;

            // A degenerate sliver is worse than nothing: SAT on a zero-thickness box
            // yields a zero MTV that still counts as colliding, which would pin an
            // entity against a surface that has no material left.
            if (maxX - minX <= 1.0e-6 && maxY - minY <= 1.0e-6 && maxZ - minZ <= 1.0e-6) {
                return null;
            }

            return new BoundingBox3d(
                minX - offsetX, minY - offsetY, minZ - offsetZ,
                maxX - offsetX, maxY - offsetY, maxZ - offsetZ);
        }
    }
}
