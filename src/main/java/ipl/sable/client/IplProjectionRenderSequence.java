package ipl.sable.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * One sequence containing the locally hosted sub-levels followed by every straddle
 * projection, arming each projection's mapped-pose render state exactly while the consumer
 * is drawing that element.
 *
 * <p>Why this exists: {@code SubLevelRenderDispatcher.renderAfterSections} drains a SHARED
 * queue of pending single-block layers and clears it. Calling it once for the hosted list and
 * then once per projection therefore drew the projections from an already emptied queue, so
 * single-block sub-levels (rope ends, one-block contraptions) were silently missing from every
 * destination projection - a hole in eye-space, and one that also affects the Sodium path,
 * where the queue is filled from a different call site.
 *
 * <p>The dispatcher builds one buffer per queued layer, re-iterating this sequence once per
 * layer, and calls {@code renderSingleBlock} immediately after taking each element from the
 * iterator. Arming inside {@code next()} is therefore exactly scoped to the element being
 * baked into that buffer: geometry is transformed while the state is set, and the draw happens
 * afterwards from already transformed vertices.
 *
 * <p>A consumer may stop early (empty buffer, exception). Callers must therefore invoke
 * {@link #disarm()} in a {@code finally} block; it is idempotent and clears only state this
 * sequence itself pushed.
 */
public final class IplProjectionRenderSequence implements Iterable<ClientSubLevel> {

    private final List<ClientSubLevel> hosted;
    private final List<IplClientHostedLookup.StraddleProjection> projections;
    private Cursor active;

    public IplProjectionRenderSequence(
        List<ClientSubLevel> hosted,
        List<IplClientHostedLookup.StraddleProjection> projections
    ) {
        this.hosted = hosted;
        this.projections = projections;
    }

    public boolean isEmpty() {
        return hosted.isEmpty() && projections.isEmpty();
    }

    @Override
    public Iterator<ClientSubLevel> iterator() {
        disarm();
        active = new Cursor();
        return active;
    }

    /** Clears a projection state left armed by an abandoned iteration. Safe to call twice. */
    public void disarm() {
        if (active != null) {
            active.disarm();
            active = null;
        }
    }

    private final class Cursor implements Iterator<ClientSubLevel> {
        private int index;
        private boolean armed;

        @Override
        public boolean hasNext() {
            if (index < hosted.size() + projections.size()) return true;
            disarm();
            return false;
        }

        @Override
        public ClientSubLevel next() {
            // Every element's state ends where the next element begins, so a projection can
            // never leak its mapped pose onto the following sub-level.
            disarm();
            if (index < hosted.size()) return hosted.get(index++);
            int projectionIndex = index - hosted.size();
            if (projectionIndex >= projections.size()) throw new NoSuchElementException();
            index++;
            IplClientHostedLookup.StraddleProjection projection = projections.get(projectionIndex);
            IplStraddleRenderState.set(projection.sub(), projection.mappedPose(),
                projection.destPlane(), projection.portal());
            armed = true;
            return projection.sub();
        }

        void disarm() {
            if (armed) {
                IplStraddleRenderState.clear();
                armed = false;
            }
        }
    }
}
