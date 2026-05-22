package dev.touchpilot.app.androidcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TapTargetSelectorTest {
    private fun candidate(
        label: String,
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        isVisibleToUser: Boolean = true,
        hasBounds: Boolean = true,
    ): TapCandidate = TapCandidate(
        label = label,
        isClickable = isClickable,
        isEditable = isEditable,
        isVisibleToUser = isVisibleToUser,
        hasBounds = hasBounds,
    )

    // --- Reachability gate ---

    @Test
    fun reachable_visibleAndBounded() {
        assertTrue(TapTargetSelector.isReachable(candidate("Settings")))
    }

    @Test
    fun unreachable_invisibleNeverMatches() {
        val c = candidate("Settings", isVisibleToUser = false)
        assertFalse(TapTargetSelector.isReachable(c))
        assertEquals(Int.MIN_VALUE, TapTargetSelector.score(c, "Settings"))
        assertFalse(TapTargetSelector.isCandidate(c, "Settings"))
    }

    @Test
    fun unreachable_zeroBoundsNeverMatches() {
        val c = candidate("Settings", hasBounds = false)
        assertFalse(TapTargetSelector.isReachable(c))
        assertEquals(Int.MIN_VALUE, TapTargetSelector.score(c, "Settings"))
    }

    // --- Match kinds ---

    @Test
    fun matchKind_exactCaseInsensitive() {
        assertEquals(
            TapTargetSelector.MatchKind.EXACT,
            TapTargetSelector.matchKind(candidate("Settings"), "settings"),
        )
    }

    @Test
    fun matchKind_prefix() {
        assertEquals(
            TapTargetSelector.MatchKind.PREFIX,
            TapTargetSelector.matchKind(candidate("Settings menu"), "settings"),
        )
    }

    @Test
    fun matchKind_suffix() {
        assertEquals(
            TapTargetSelector.MatchKind.SUFFIX,
            TapTargetSelector.matchKind(candidate("Privacy Settings"), "settings"),
        )
    }

    @Test
    fun matchKind_substring() {
        assertEquals(
            TapTargetSelector.MatchKind.SUBSTRING,
            TapTargetSelector.matchKind(candidate("Open settings panel"), "settings"),
        )
    }

    @Test
    fun matchKind_noneWhenLabelDoesNotContainQuery() {
        assertEquals(
            TapTargetSelector.MatchKind.NONE,
            TapTargetSelector.matchKind(candidate("Battery"), "settings"),
        )
    }

    @Test
    fun matchKind_blankQueryNeverMatches() {
        assertEquals(
            TapTargetSelector.MatchKind.NONE,
            TapTargetSelector.matchKind(candidate("Settings"), "   "),
        )
    }

    @Test
    fun matchKind_blankLabelNeverMatches() {
        assertEquals(
            TapTargetSelector.MatchKind.NONE,
            TapTargetSelector.matchKind(candidate("   "), "Settings"),
        )
    }

    @Test
    fun matchKind_ignoresSurroundingWhitespace() {
        assertEquals(
            TapTargetSelector.MatchKind.EXACT,
            TapTargetSelector.matchKind(candidate("  Settings\n"), "settings"),
        )
    }

    // --- Score ordering ---

    @Test
    fun score_exactBeatsPrefixBeatsSuffixBeatsSubstring() {
        val exact = TapTargetSelector.score(candidate("Settings"), "settings")
        val prefix = TapTargetSelector.score(candidate("Settings menu"), "settings")
        val suffix = TapTargetSelector.score(candidate("Privacy Settings"), "settings")
        val substring = TapTargetSelector.score(candidate("Open settings panel"), "settings")
        assertTrue(exact > prefix)
        assertTrue(prefix > suffix)
        assertTrue(suffix > substring)
    }

    @Test
    fun score_clickableTieBreaksAgainstPassiveAtSameKind() {
        val clickableRow = TapTargetSelector.score(
            candidate("Settings", isClickable = true),
            "settings",
        )
        val passiveLabel = TapTargetSelector.score(candidate("Settings"), "settings")
        assertTrue(clickableRow > passiveLabel)
    }

    @Test
    fun score_editableTieBreaksAgainstPassiveAtSameKind() {
        val editable = TapTargetSelector.score(
            candidate("Search", isEditable = true),
            "search",
        )
        val passive = TapTargetSelector.score(candidate("Search"), "search")
        assertTrue(editable > passive)
    }

    @Test
    fun score_clickableExactStillBeatsClickableSubstring() {
        val exactClickable = TapTargetSelector.score(
            candidate("Settings", isClickable = true),
            "settings",
        )
        val substringClickable = TapTargetSelector.score(
            candidate("Open settings panel", isClickable = true),
            "settings",
        )
        assertTrue(exactClickable > substringClickable)
    }

    // --- chooseBest ---

    @Test
    fun chooseBest_exactRowOverHeaderInSettings() {
        // Common Settings layout: header "Privacy Settings" (suffix match,
        // non-clickable TextView) plus a row "Settings sync" (substring match,
        // clickable). Query "Settings" used to silently land on the header
        // because of DFS-first-match-wins; the new selector should reject
        // both, because there's no exact / prefix match — unless we add the
        // expected row.
        val header = "header" to candidate("Privacy Settings")
        val syncRow = "sync" to candidate("Settings sync", isClickable = true)
        val settingsRow = "settings" to candidate("Settings", isClickable = true)

        val winner = TapTargetSelector.chooseBest(
            listOf(header, syncRow, settingsRow),
            query = "Settings",
        )

        assertEquals("settings", winner)
    }

    @Test
    fun chooseBest_iconButtonWithContentDescription() {
        // Material FAB / icon-only nav rail: text="" so the label propagated
        // from the live node is the contentDescription. Old selector ignored
        // contentDescription whenever text was a non-null empty string; the
        // pure selector here is given the right label by the caller, so it
        // just has to match on it normally.
        val fab = "fab" to candidate(
            "Compose",
            isClickable = true,
        )
        val hamburger = "menu" to candidate(
            "Open navigation drawer",
            isClickable = true,
        )

        val winner = TapTargetSelector.chooseBest(
            listOf(fab, hamburger),
            query = "compose",
        )

        assertEquals("fab", winner)
    }

    @Test
    fun chooseBest_skipsOffScreenWinnerByLabel() {
        // The off-screen entry has the exact label match, but it's not
        // reachable — the selector must skip it and pick the second-best
        // reachable candidate.
        val offScreen = "offscreen" to candidate(
            "Wi-Fi",
            isClickable = true,
            isVisibleToUser = false,
        )
        val visibleSubstring = "visible" to candidate(
            "Wi-Fi and Bluetooth",
            isClickable = true,
        )

        val winner = TapTargetSelector.chooseBest(
            listOf(offScreen, visibleSubstring),
            query = "Wi-Fi",
        )

        assertEquals("visible", winner)
    }

    @Test
    fun chooseBest_returnsNullWhenNothingMatches() {
        val winner = TapTargetSelector.chooseBest(
            listOf(
                "a" to candidate("Battery"),
                "b" to candidate("Storage"),
            ),
            query = "Settings",
        )
        assertNull(winner)
    }

    @Test
    fun chooseBest_returnsNullWhenAllMatchesAreUnreachable() {
        val winner = TapTargetSelector.chooseBest(
            listOf(
                "a" to candidate("Settings", isClickable = true, isVisibleToUser = false),
                "b" to candidate("Settings sync", isClickable = true, hasBounds = false),
            ),
            query = "Settings",
        )
        assertNull(winner)
    }

    @Test
    fun chooseBest_stableOnExactTie() {
        // Two equal candidates (same label, same affordance, same reachability)
        // — first-encountered wins so callers can rely on DFS order when the
        // tree has no better signal.
        val first = "first" to candidate("Settings", isClickable = true)
        val second = "second" to candidate("Settings", isClickable = true)
        val winner = TapTargetSelector.chooseBest(
            listOf(first, second),
            query = "Settings",
        )
        assertEquals("first", winner)
    }
}
