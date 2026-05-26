package dev.touchpilot.app.tools

import dev.touchpilot.app.tools.targets.ClearTextTarget
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearTextTest {

    @Test
    fun catalogContainsClearText() {
        assertNotNull(AndroidToolCatalog.find("clear_text"))
    }

    @Test
    fun clearTextRiskIsMedium() {
        val spec = AndroidToolCatalog.find("clear_text")!!
        assertEquals(ToolRisk.MEDIUM, spec.risk)
    }

    @Test
    fun clearTextSpecAdvertisesAllSelectors() {
        val spec = AndroidToolCatalog.find("clear_text")!!
        assertTrue(spec.arguments.containsKey(ClearTextTarget.TargetTextArg))
        assertTrue(spec.arguments.containsKey(ClearTextTarget.TargetNodeIdArg))
        assertTrue(spec.arguments.containsKey(ClearTextTarget.TargetBoundsArg))
        assertTrue(spec.arguments.containsKey(ClearTextTarget.TargetViewIdArg))
        assertTrue(spec.arguments.containsKey(ClearTextTarget.TargetContentDescriptionArg))
    }

    @Test
    fun clearTextHasNoRequiredArgs() {
        val spec = AndroidToolCatalog.find("clear_text")!!
        assertTrue(spec.requiredArguments.isEmpty())
    }

    @Test
    fun clearTextDoesNotAcceptATextPayload() {
        // clear_text must never accept a `text` argument: there is no value to
        // type, and accepting one would imply we log/handle user content.
        val spec = AndroidToolCatalog.find("clear_text")!!
        assertTrue(!spec.arguments.containsKey("text"))
    }

    @Test
    fun validationAcceptsNoSelector() {
        // No selector => focused-field fallback path.
        assertNull(AndroidToolCatalog.validate("clear_text", emptyMap()))
    }

    @Test
    fun validationAcceptsTargetTextSelector() {
        assertNull(
            AndroidToolCatalog.validate(
                "clear_text",
                mapOf(ClearTextTarget.TargetTextArg to "Search")
            )
        )
    }

    @Test
    fun validationAcceptsTargetNodeIdSelector() {
        assertNull(
            AndroidToolCatalog.validate(
                "clear_text",
                mapOf(ClearTextTarget.TargetNodeIdArg to "0.1.2")
            )
        )
    }

    @Test
    fun validationAcceptsTargetBoundsSelector() {
        assertNull(
            AndroidToolCatalog.validate(
                "clear_text",
                mapOf(ClearTextTarget.TargetBoundsArg to "0,0,100,50")
            )
        )
    }

    @Test
    fun validationAcceptsTargetViewIdSelector() {
        assertNull(
            AndroidToolCatalog.validate(
                "clear_text",
                mapOf(ClearTextTarget.TargetViewIdArg to "com.example:id/search")
            )
        )
    }

    @Test
    fun validationAcceptsTargetContentDescriptionSelector() {
        assertNull(
            AndroidToolCatalog.validate(
                "clear_text",
                mapOf(ClearTextTarget.TargetContentDescriptionArg to "Search field")
            )
        )
    }

    @Test
    fun validationRejectsMalformedBounds() {
        val error = AndroidToolCatalog.validate(
            "clear_text",
            mapOf(ClearTextTarget.TargetBoundsArg to "not-a-rect")
        )
        assertNotNull(error)
        assertContains(error, "target_bounds")
    }

    @Test
    fun validationRejectsUnknownArgs() {
        val error = AndroidToolCatalog.validate(
            "clear_text",
            mapOf("text" to "hello")
        )
        assertNotNull(error)
        assertContains(error, "Unknown argument")
    }

    @Test
    fun clearTextTargetDetectsAllSelectorDimensions() {
        // Each individual selector dimension must register as "has target",
        // otherwise the executor will silently fall through to the focused
        // path and ignore the agent's selector.
        ClearTextTarget.selectorArgs.forEach { arg ->
            assertTrue(
                ClearTextTarget.hasTarget(mapOf(arg to "value")),
                "Expected hasTarget=true for selector arg $arg"
            )
        }
    }

    @Test
    fun clearTextTargetIgnoresBlankSelectorValues() {
        assertTrue(!ClearTextTarget.hasTarget(emptyMap()))
        assertTrue(
            !ClearTextTarget.hasTarget(
                mapOf(
                    ClearTextTarget.TargetTextArg to "   ",
                    ClearTextTarget.TargetNodeIdArg to ""
                )
            )
        )
    }

    @Test
    fun clearTextSelectorRoleIsInput() {
        val selector = ClearTextTarget.selectorFromArgs(
            mapOf(ClearTextTarget.TargetTextArg to "Search")
        )
        // Role must be INPUT so the resolver only ranks editable candidates.
        assertEquals(
            dev.touchpilot.app.tools.targets.TargetRole.INPUT,
            selector.role
        )
    }

    @Test
    fun existingToolsUnaffected() {
        assertNull(AndroidToolCatalog.validate("tap", mapOf("text" to "OK")))
        assertNull(AndroidToolCatalog.validate("type_text", mapOf("text" to "hello")))
        assertNull(AndroidToolCatalog.validate("scroll", mapOf("direction" to "forward")))
    }
}
