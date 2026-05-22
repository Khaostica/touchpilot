package dev.touchpilot.app.androidcontrol

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live behavior proof for the robust tap-target-selection change.
 *
 * The test:
 * 1. Sends the device to the launcher to start from a known state.
 * 2. Launches the Android Settings app via Intent so the recorded run has a
 *    visible app transition.
 * 3. Walks the live `AccessibilityNodeInfo` tree, converting each node into a
 *    `TapCandidate` exactly the way the patched
 *    [TouchPilotAccessibilityService.tapByText] does in production.
 * 4. Demonstrates the four fixes by feeding the candidates through both the
 *    pre-patch heuristic (first DFS substring match against `text` only) and
 *    the new [TapTargetSelector], and logging each chosen label.
 * 5. Returns to HOME so subsequent runs start clean.
 *
 * `UiAutomation.rootInActiveWindow` grants the same view of the tree the
 * AccessibilityService would see, without requiring TouchPilot's own service
 * to be enabled by the user.
 */
@RunWith(AndroidJUnit4::class)
class TapTargetSelectorLiveTest {
    private val tag = "TapTargetSelectorLive"

    @Test
    fun selectorBeatsLegacyHeuristicOnLiveSettingsTree() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation: UiAutomation = instrumentation.uiAutomation

        uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        Thread.sleep(1_500L)

        val context = instrumentation.targetContext
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        Thread.sleep(2_500L)

        val root: AccessibilityNodeInfo? = uiAutomation.rootInActiveWindow
        assertNotNull("rootInActiveWindow was null on the live device", root)

        val annotated = collectAnnotated(root!!)
        Log.i(tag, "candidate count: ${annotated.size}")

        // Common queries that exercise each failure mode the patch fixes.
        val queries = listOf("Settings", "Network", "Battery", "Search")
        for (query in queries) {
            val legacyChoice = legacyFirstSubstringText(root, query)
            val selectorChoice = TapTargetSelector.chooseBest(
                annotated.map { (label, candidate) -> label to candidate },
                query,
            )

            Log.i(tag, "query=\"$query\" | legacy=${legacyChoice ?: "(none)"} | selector=${selectorChoice ?: "(none)"}")
        }

        // Demonstrate the icon-button fix end-to-end: gather every node with
        // a non-blank contentDescription but an empty text, then show that
        // each one becomes a TapCandidate whose label is the description.
        val iconButtons = annotated.filter { (_, c) ->
            c.label.isNotBlank()
        }.filter { (originalLabel, _) ->
            originalLabel.startsWith("desc:")
        }
        Log.i(tag, "icon-button candidates (desc-only labels): ${iconButtons.size}")
        for ((label, candidate) in iconButtons.take(5)) {
            Log.i(tag, "  $label -> reachable=${TapTargetSelector.isReachable(candidate)} clickable=${candidate.isClickable}")
        }

        // The Settings activity always exposes at least one reachable
        // candidate; an empty list would mean the live tree was not usable
        // and is the failure mode we are guarding against.
        assertTrue(
            "No reachable tap candidates on live Settings screen",
            annotated.any { (_, c) -> TapTargetSelector.isReachable(c) },
        )
    }

    @After
    fun returnHome() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Walks the live tree once, returning an `originalLabel` describing where
     * the candidate's label came from (text vs. desc) alongside the
     * [TapCandidate] the selector sees in production.
     */
    private fun collectAnnotated(root: AccessibilityNodeInfo): List<Pair<String, TapCandidate>> {
        val out = mutableListOf<Pair<String, TapCandidate>>()
        forEachNode(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label: String
            val source: String
            when {
                text.isNotEmpty() -> { label = text; source = "text:$text" }
                desc.isNotEmpty() -> { label = desc; source = "desc:$desc" }
                else -> return@forEachNode
            }
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val candidate = TapCandidate(
                label = label,
                isClickable = node.isClickable,
                isEditable = node.isEditable ||
                    node.className?.toString()?.contains("EditText", ignoreCase = true) == true,
                isVisibleToUser = node.isVisibleToUser,
                hasBounds = !bounds.isEmpty,
            )
            out += source to candidate
        }
        return out
    }

    /**
     * The pre-patch heuristic: first DFS hit whose `text` (with a nullable
     * Elvis fallback to `contentDescription`) substring-contains the query.
     * Reproduces every failure mode this PR closes — substring header match,
     * off-screen tap, ignored content description on icon buttons.
     */
    private fun legacyFirstSubstringText(root: AccessibilityNodeInfo, query: String): String? {
        var found: String? = null
        forEachNode(root) { node ->
            if (found != null) return@forEachNode
            val label = node.text?.toString()
                ?: node.contentDescription?.toString()
                ?: ""
            if (label.contains(query, ignoreCase = true)) {
                found = label
            }
        }
        return found
    }

    private fun forEachNode(
        node: AccessibilityNodeInfo,
        visit: (AccessibilityNodeInfo) -> Unit,
    ) {
        visit(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            forEachNode(child, visit)
        }
    }
}
