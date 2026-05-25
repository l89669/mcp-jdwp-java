package one.edee.mcp.jdwp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Suggests already-loaded class names that resemble a target name that did not match anything. Used
 * when a breakpoint is deferred because its class isn't loaded: a misspelled fully-qualified name
 * ({@code Config} for {@code com.example.Configuration}) is indistinguishable from a genuine
 * not-yet-loaded class and would defer forever, silently. Surfacing close matches turns that dead
 * end into a "did you mean…" hint.
 *
 * <p>Pure and JDI-free so the matching can be unit-tested; {@link JDWPTools} feeds it the loaded
 * class names from {@code vm.allClasses()}.
 */
public final class ClassNameMatcher {

    private ClassNameMatcher() {
    }

    /**
     * Returns up to {@code max} loaded class names that resemble {@code target}, best first. Matching
     * is on the <em>simple</em> name (the segment after the last {@code .} or {@code $}): an exact
     * case-insensitive match ranks first, then a prefix relationship (either direction — covers the
     * {@code Config}→{@code Configuration} truncation), then a small edit distance (typos). Array,
     * synthetic ({@code $$}) and lambda-style names are skipped. An exact match on the full name is
     * never suggested (the caller already knows that class isn't the problem).
     *
     * @param loadedClassNames fully-qualified names currently loaded in the target VM
     * @param target           the name the caller asked for
     * @param max              maximum suggestions to return
     * @return resembling class names, best match first; empty when nothing is close
     */
    public static List<String> suggest(Collection<String> loadedClassNames, String target, int max) {
        if (target.isBlank() || max <= 0) {
            return List.of();
        }
        final String targetSimpleLower = simpleName(target).toLowerCase(Locale.ROOT);
        if (targetSimpleLower.isEmpty()) {
            return List.of();
        }

        final List<Scored> scored = new ArrayList<>();
        for (final String name : loadedClassNames) {
            if (name.equals(target) || name.indexOf('[') >= 0 || name.contains("$$")) {
                continue;
            }
            final String simpleLower = simpleName(name).toLowerCase(Locale.ROOT);
            if (simpleLower.isEmpty()) {
                continue;
            }
            final int score = score(targetSimpleLower, simpleLower);
            if (score >= 0) {
                scored.add(new Scored(name, score));
            }
        }
        // Lower score = closer match; break ties by name so the output is deterministic.
        scored.sort(Comparator.comparingInt(Scored::score).thenComparing(Scored::name));
        final List<String> result = new ArrayList<>(max);
        for (final Scored s : scored) {
            result.add(s.name());
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /** A candidate name with its match score (lower is closer). */
    private record Scored(String name, int score) {
    }

    /** Simple name = the segment after the last package dot or nested-class {@code $}. */
    private static String simpleName(String fqcn) {
        final int cut = Math.max(fqcn.lastIndexOf('.'), fqcn.lastIndexOf('$'));
        return cut >= 0 ? fqcn.substring(cut + 1) : fqcn;
    }

    /**
     * Scores how closely {@code candidate} resembles {@code target} (both already lower-cased simple
     * names). Returns {@code -1} when they are not close enough to suggest. Lower non-negative scores
     * are better: 0 for an exact match, a small number for a prefix relationship (penalised by the
     * length gap), 10+ for a within-threshold edit distance.
     */
    private static int score(String target, String candidate) {
        if (candidate.equals(target)) {
            return 0;
        }
        if (candidate.startsWith(target) || target.startsWith(candidate)) {
            return 1 + Math.abs(candidate.length() - target.length());
        }
        final int threshold = Math.max(2, Math.max(target.length(), candidate.length()) / 4);
        final int dist = boundedLevenshtein(target, candidate, threshold);
        return dist >= 0 ? 10 + dist : -1;
    }

    /**
     * Levenshtein edit distance between {@code a} and {@code b}, returning {@code -1} as soon as the
     * best achievable distance exceeds {@code threshold} (so a far-off candidate costs little).
     */
    private static int boundedLevenshtein(String a, String b, int threshold) {
        if (Math.abs(a.length() - b.length()) > threshold) {
            return -1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowBest = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
                rowBest = Math.min(rowBest, curr[j]);
            }
            if (rowBest > threshold) {
                return -1; // every alignment through this row already exceeds the budget
            }
            final int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        final int dist = prev[b.length()];
        return dist <= threshold ? dist : -1;
    }
}
