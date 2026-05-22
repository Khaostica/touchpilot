package dev.touchpilot.app.androidcontrol

/**
 * Platform-independent view of a single node considered for `tapByText`.
 *
 * Pulled out of `AccessibilityNodeInfo` so the selection logic — which decides
 * *which* visible node the agent should click for a given query — can be
 * exercised in plain JVM unit tests. Tap *execution* (`performAction`,
 * `dispatchGesture`) stays in [TouchPilotAccessibilityService] where the live
 * platform type is required.
 *
 * `label` is the user-meaningful string for the node: the node's text when it
 * has one, falling back to its `contentDescription` (the standard source for
 * icon-only buttons like a Material FAB).
 */
data class TapCandidate(
    val label: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isVisibleToUser: Boolean,
    val hasBounds: Boolean,
)

/**
 * Pure target-selection logic for `tapByText`.
 *
 * Selection is conservative on purpose:
 *
 * 1. **Reachability gate.** A node has to be visible to the user and have a
 *    non-empty on-screen bounding box. Off-screen rows, nodes behind a dialog,
 *    and zero-bounds wrappers are never tap targets — even if their label
 *    matches.
 * 2. **Exact match wins.** "Settings" prefers a row labelled exactly
 *    "Settings" over a header labelled "Privacy Settings", regardless of DFS
 *    order. Prefix and suffix matches outrank loose substring matches.
 * 3. **Affordance tie-break.** Among candidates that match equally well, a
 *    clickable row beats a passive `TextView`, and an editable input beats a
 *    non-interactive node.
 *
 * The previous DFS-first-match-wins behaviour silently tapped header labels,
 * off-screen rows, and ancestors with overly broad bounds; the rules here are
 * the minimal surface that closes those gaps without changing the
 * `tapByText(query)` contract.
 */
object TapTargetSelector {
    /**
     * How a candidate's label relates to the query. Ordered loosely strongest
     * to weakest — the [score] function maps the kind to a coarse band so that
     * any exact match beats any prefix match, and so on.
     */
    enum class MatchKind { NONE, EXACT, PREFIX, SUFFIX, SUBSTRING }

    private const val SCORE_EXACT = 4_000
    private const val SCORE_PREFIX = 3_000
    private const val SCORE_SUFFIX = 2_000
    private const val SCORE_SUBSTRING = 1_000
    private const val SCORE_CLICKABLE_BONUS = 100
    private const val SCORE_EDITABLE_BONUS = 50

    /** Off-screen and zero-bounds nodes are never tap targets. */
    fun isReachable(candidate: TapCandidate): Boolean =
        candidate.isVisibleToUser && candidate.hasBounds

    /**
     * How [candidate]'s label relates to [query]. Comparisons are
     * case-insensitive and ignore leading/trailing whitespace on both sides
     * so a label rendered as `"  Settings\n"` still matches `"settings"`.
     * Blank queries and blank labels never match.
     */
    fun matchKind(candidate: TapCandidate, query: String): MatchKind {
        val q = query.trim()
        if (q.isEmpty()) return MatchKind.NONE
        val label = candidate.label.trim()
        if (label.isEmpty()) return MatchKind.NONE
        if (label.equals(q, ignoreCase = true)) return MatchKind.EXACT
        if (label.startsWith(q, ignoreCase = true)) return MatchKind.PREFIX
        if (label.endsWith(q, ignoreCase = true)) return MatchKind.SUFFIX
        if (label.contains(q, ignoreCase = true)) return MatchKind.SUBSTRING
        return MatchKind.NONE
    }

    /**
     * Score for ranking candidates. Higher is better. Unreachable or
     * non-matching candidates return [Int.MIN_VALUE] so they cannot be picked
     * even by accident if the caller compares scores naively.
     */
    fun score(candidate: TapCandidate, query: String): Int {
        if (!isReachable(candidate)) return Int.MIN_VALUE
        val kindScore = when (matchKind(candidate, query)) {
            MatchKind.EXACT -> SCORE_EXACT
            MatchKind.PREFIX -> SCORE_PREFIX
            MatchKind.SUFFIX -> SCORE_SUFFIX
            MatchKind.SUBSTRING -> SCORE_SUBSTRING
            MatchKind.NONE -> return Int.MIN_VALUE
        }
        val affordanceBonus = when {
            candidate.isClickable -> SCORE_CLICKABLE_BONUS
            candidate.isEditable -> SCORE_EDITABLE_BONUS
            else -> 0
        }
        return kindScore + affordanceBonus
    }

    /** Convenience for `score(...) > Int.MIN_VALUE`. */
    fun isCandidate(candidate: TapCandidate, query: String): Boolean =
        score(candidate, query) > Int.MIN_VALUE

    /**
     * Among [candidates], return the entry attached to the highest-scoring
     * [TapCandidate] for [query]. Returns `null` if no candidate is reachable
     * and matching. The first encountered candidate wins on a strict tie,
     * which keeps results stable for the same DFS order in the caller.
     */
    fun <T> chooseBest(
        candidates: List<Pair<T, TapCandidate>>,
        query: String,
    ): T? {
        var best: T? = null
        var bestScore = Int.MIN_VALUE
        for ((ref, candidate) in candidates) {
            val s = score(candidate, query)
            if (s > bestScore) {
                bestScore = s
                best = ref
            }
        }
        return best
    }
}
