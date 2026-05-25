package one.edee.mcp.jdwp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure deadlock-cycle detection over a JDWP monitor wait-for graph. Kept free of any JDI types so
 * the cycle-finding logic can be unit-tested without a live VM; {@link JDWPTools#jdwp_dump_locks}
 * builds the {@link WaitForEdge} list from {@code currentContendedMonitor()} /
 * {@code owningThread()} and feeds it here.
 *
 * <p>Each thread is blocked on at most one monitor at a time, so it has at most one outgoing edge
 * ("the thread that owns the monitor I'm waiting for"). The wait-for graph is therefore
 * <em>functional</em>, and a deadlock is exactly a cycle in it: a set of threads each waiting on
 * the next, the last waiting back on the first.
 */
public final class DeadlockAnalyzer {

    private DeadlockAnalyzer() {
    }

    /**
     * One thread's contention edge: {@code threadId} is blocked trying to acquire a monitor that
     * {@code ownerThreadId} currently holds. Threads that are not blocked on a monitor contribute
     * no edge.
     *
     * @param threadId      the blocked thread
     * @param ownerThreadId the thread holding the contended monitor
     */
    public record WaitForEdge(long threadId, long ownerThreadId) {
    }

    /**
     * Finds every deadlock cycle in the wait-for graph. Returns one list per distinct cycle, each
     * holding the thread IDs in cycle order (the starting offset within a cycle is unspecified but
     * the membership and traversal order are stable); the list is empty when no deadlock exists.
     *
     * <p>A node that merely <em>feeds into</em> a cycle (waits on a deadlocked thread without
     * being part of the loop) is not reported as a cycle member.
     *
     * @param edges the contention edges; self-edges are ignored as physically impossible
     * @return deadlock cycles, each as an ordered list of thread IDs
     */
    public static List<List<Long>> findDeadlockCycles(Collection<WaitForEdge> edges) {
        final Map<Long, Long> waitsFor = new LinkedHashMap<>();
        for (WaitForEdge e : edges) {
            // A thread cannot deadlock on a monitor it already owns, so a self-edge is noise that
            // would otherwise register as a degenerate one-node "cycle". Drop it.
            if (e.threadId() != e.ownerThreadId()) {
                waitsFor.put(e.threadId(), e.ownerThreadId());
            }
        }

        final List<List<Long>> cycles = new ArrayList<>();
        final Set<Long> settled = new HashSet<>();
        for (final Long start : waitsFor.keySet()) {
            if (settled.contains(start)) {
                continue;
            }
            // Walk the chain of "waits-for" edges from this start, recording the path. The first
            // time a node reappears on the current path, everything from its first occurrence
            // onward is a cycle. A node already settled by a previous walk ends this walk: it
            // either led nowhere or fed into a cycle we've already recorded.
            final Map<Long, Integer> indexOnPath = new LinkedHashMap<>();
            final List<Long> path = new ArrayList<>();
            Long node = start;
            while (node != null && !settled.contains(node)) {
                final Integer seenAt = indexOnPath.get(node);
                if (seenAt != null) {
                    cycles.add(List.copyOf(path.subList(seenAt, path.size())));
                    break;
                }
                indexOnPath.put(node, path.size());
                path.add(node);
                node = waitsFor.get(node);
            }
            settled.addAll(path);
        }
        return cycles;
    }
}
