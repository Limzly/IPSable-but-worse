package ipl.sable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread stall watchdog.
 *
 * <p>The integrated server can hang with no exception and no log -- e.g. an
 * infinite loop from a corrupted data structure, or a deadlock. When that
 * happens we have no idea <i>where</i> it's stuck, because the server thread
 * simply stops emitting anything (the 29May mirror-churn hang looked exactly
 * like this: last server log line was a mirror spawn, then silence while the
 * render thread kept going).
 *
 * <p>This watchdog runs a daemon thread that watches a "last tick progressed"
 * timestamp, updated each server tick via {@link #onTick(Thread)}. If the
 * server thread goes {@link #STALL_THRESHOLD_NANOS} without progressing, it
 * captures and logs that thread's current stack trace -- pinpointing the stuck
 * frame. It re-dumps every {@link #REDUMP_INTERVAL_NANOS} while the stall
 * persists, so a <i>moving</i> stack (infinite loop) can be told apart from a
 * <i>static</i> one (deadlock / blocked I/O).
 *
 * <p>Cheap and silent in normal operation: it only ever logs when a tick takes
 * multiple seconds, which should never happen in healthy play. Purely
 * diagnostic -- it does not attempt to recover the server.
 */
public final class IplServerWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger("ipl-watchdog");

    /** How long the server thread may go without a completed tick before we dump. */
    private static final long STALL_THRESHOLD_NANOS = 8_000_000_000L; // 8s

    /** While still stalled, re-dump no more often than this. */
    private static final long REDUMP_INTERVAL_NANOS = 10_000_000_000L; // 10s

    /**
     * A stall older than this escalates to an ALL-THREADS dump + JVM deadlock check:
     * the server-thread stack shows WHERE it spins, but when it's waiting on an
     * incomplete future (chunk save, entity storage), the WHY lives on a worker
     * thread that died or deadlocked — only a full dump exposes that.
     */
    private static final long ALL_THREADS_THRESHOLD_NANOS = 25_000_000_000L; // 25s
    private static final long ALL_THREADS_REDUMP_NANOS = 30_000_000_000L; // 30s

    private static volatile Thread serverThread;
    private static volatile net.minecraft.server.MinecraftServer server;
    private static volatile long lastProgressNanos = System.nanoTime();
    private static volatile long tickCount = 0L;
    private static volatile long lastDumpNanos = 0L;
    private static volatile long lastAllDumpNanos = 0L;
    private static volatile boolean watchdogStarted = false;

    private IplServerWatchdog() {}

    /**
     * Call once per completed server tick (from the heartbeat mixin's TAIL). Records
     * the server thread reference + a fresh progress timestamp, and lazily starts the
     * watchdog daemon on first call.
     */
    public static void onTick(Thread thread, net.minecraft.server.MinecraftServer srv) {
        serverThread = thread;
        server = srv;
        lastProgressNanos = System.nanoTime();
        tickCount++;
        if (!watchdogStarted) {
            startWatchdog();
        }
    }

    private static synchronized void startWatchdog() {
        if (watchdogStarted) return;
        watchdogStarted = true;
        Thread t = new Thread(IplServerWatchdog::watchdogLoop, "ipl-server-watchdog");
        t.setDaemon(true);
        t.start();
        LOG.info("[IPL-WATCHDOG] started (stall threshold {}s)", STALL_THRESHOLD_NANOS / 1_000_000_000L);
    }

    private static void watchdogLoop() {
        while (true) {
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException e) {
                return;
            }

            Thread st = serverThread;
            if (st == null) continue;
            if (st.getState() == Thread.State.TERMINATED) {
                // Server exited; the stale progress timestamp is not a stall.
                serverThread = null;
                server = null;
                continue;
            }

            long now = System.nanoTime();
            long stalledNanos = now - lastProgressNanos;
            if (stalledNanos < STALL_THRESHOLD_NANOS) continue;
            if (now - lastDumpNanos < REDUMP_INTERVAL_NANOS) continue;

            StackTraceElement[] stack = st.getStackTrace();
            if (isWaitingForNextTick(st, stack)) continue;

            lastDumpNanos = now;
            StringBuilder sb = new StringBuilder(2048);
            sb.append("[IPL-WATCHDOG] SERVER THREAD STALLED ~")
                .append(stalledNanos / 1_000_000_000L)
                .append("s (lastCompletedTick=").append(tickCount)
                .append(", thread=").append(st.getName())
                .append(", state=").append(st.getState())
                .append("). Stack:\n");
            if (stack.length == 0) {
                sb.append("    <no stack frames available>\n");
            } else {
                for (StackTraceElement e : stack) {
                    sb.append("    at ").append(e).append('\n');
                }
            }
            appendLevelChunkCounts(sb);
            LOG.error(sb.toString());

            if (stalledNanos >= ALL_THREADS_THRESHOLD_NANOS
                && now - lastAllDumpNanos >= ALL_THREADS_REDUMP_NANOS) {
                lastAllDumpNanos = now;
                dumpAllThreads();
            }
        }
    }

    /**
     * Per-level chunk counts: across repeated dumps, the level whose count does not
     * shrink is the one a shutdown unload loop can't drain. On every stall dump —
     * slow-but-finite shutdowns end before the all-threads escalation would fire.
     */
    private static void appendLevelChunkCounts(StringBuilder sb) {
        net.minecraft.server.MinecraftServer srv = server;
        if (srv == null) return;
        try {
            for (net.minecraft.server.level.ServerLevel level : srv.getAllLevels()) {
                sb.append("level ").append(level.dimension().location())
                    .append(": loadedChunks=")
                    .append(level.getChunkSource().getLoadedChunksCount())
                    .append('\n');
            }
        } catch (Throwable t) {
            sb.append("(level chunk stats unavailable: ").append(t).append(")\n");
        }
    }

    /** Full JVM picture: deadlock check + every thread's state and stack. */
    private static void dumpAllThreads() {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("[IPL-WATCHDOG] ALL-THREADS DUMP (stall escalation)\n");

        appendLevelChunkCounts(sb);

        try {
            java.lang.management.ThreadMXBean mx =
                java.lang.management.ManagementFactory.getThreadMXBean();
            long[] deadlocked = mx.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                sb.append("!! JVM-LEVEL DEADLOCK detected between thread ids: ");
                for (long id : deadlocked) sb.append(id).append(' ');
                sb.append('\n');
            } else {
                sb.append("no JVM-level lock deadlock (futures/spin loops not covered by this check)\n");
            }
        } catch (Throwable ignored) {
        }

        java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == Thread.currentThread()) continue;
            sb.append("-- \"").append(thread.getName()).append("\" ")
                .append(thread.getState())
                .append(thread.isDaemon() ? " daemon" : "")
                .append(" id=").append(thread.threadId()).append('\n');
            StackTraceElement[] stack = entry.getValue();
            int limit = Math.min(stack.length, 24);
            for (int i = 0; i < limit; i++) {
                sb.append("    at ").append(stack[i]).append('\n');
            }
            if (stack.length > limit) {
                sb.append("    ... ").append(stack.length - limit).append(" more\n");
            }
        }
        LOG.error(sb.toString());
    }

    /** The server can deliberately sleep for arbitrary time while paused. */
    private static boolean isWaitingForNextTick(Thread thread, StackTraceElement[] stack) {
        if (thread.getState() != Thread.State.TIMED_WAITING) return false;
        for (StackTraceElement frame : stack) {
            if (frame.getClassName().equals("net.minecraft.server.MinecraftServer")
                && frame.getMethodName().equals("waitUntilNextTick")) {
                return true;
            }
        }
        return false;
    }
}
