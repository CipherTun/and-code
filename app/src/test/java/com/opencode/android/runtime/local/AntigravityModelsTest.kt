package com.opencode.android.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [output] is captured verbatim from `agy models` run on a signed-in real device with the exact
 * official 1.1.7 release this app pins - not the differently-formatted output an unrelated locally
 * installed 1.1.1 build printed, which an earlier version of this parser was built against.
 */
class AntigravityModelsTest {
    private val output =
        """
        gemini-3.6-flash-high
        gemini-3.6-flash-medium
        gemini-3.6-flash-low
        gemini-3.5-flash-high
        gemini-3.5-flash-medium
        gemini-3.5-flash-low
        gemini-3.1-pro-high
        gemini-3.1-pro-low
        claude-sonnet-4-6
        claude-opus-4-6-thinking
        gpt-oss-120b-medium
        """.trimIndent()

    @Test
    fun `parses the real agy models output`() {
        val entries = AntigravityModels.parse(output)
        assertEquals(11, entries.size)
        assertEquals(AntigravityModels.Entry("gemini-3.6-flash", "high"), entries.first())
        assertEquals(AntigravityModels.Entry("gpt-oss-120b", "medium"), entries.last())
    }

    @Test
    fun `a version number ending in a digit is not mistaken for a variant`() {
        val entry = AntigravityModels.parse("claude-sonnet-4-6").single()
        assertEquals(AntigravityModels.Entry("claude-sonnet-4-6", null), entry)
    }

    @Test
    fun `groups entries by base model with variants in order`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        val provider = catalog.all.single()
        assertEquals(6, provider.models.size)
        val flash = provider.models.getValue("gemini-3.6-flash")
        assertEquals(listOf("high", "medium", "low"), flash.variants.keys.toList())
        val sonnet = provider.models.getValue("claude-sonnet-4-6")
        assertEquals(emptyList<String>(), sonnet.variants.keys.toList())
        val opus = provider.models.getValue("claude-opus-4-6")
        assertEquals(listOf("thinking"), opus.variants.keys.toList())
        assertEquals("gemini-3.6-flash", catalog.default[AntigravityModels.PROVIDER_ID])
    }

    @Test
    fun `falls back to a single placeholder model when nothing was parsed`() {
        val catalog = AntigravityModels.catalog(emptyList())
        val provider = catalog.all.single()
        assertEquals(1, provider.models.size)
        assertTrue(provider.models.containsKey("default"))
    }

    @Test
    fun `a slug without a variant suffix has no variant`() {
        val entries = AntigravityModels.parse("some-custom-model\n")
        assertEquals(AntigravityModels.Entry("some-custom-model", null), entries.single())
    }

    @Test
    fun `cli args rejoin the base and variant into the original slug`() {
        assertEquals(listOf("--model", "gemini-3.1-pro-high"), AntigravityModels.cliArgs("gemini-3.1-pro", "high"))
        assertEquals(listOf("--model", "claude-opus-4-6-thinking"), AntigravityModels.cliArgs("claude-opus-4-6", "thinking"))
    }

    @Test
    fun `a model with no variant is sent as-is`() {
        assertEquals(listOf("--model", "claude-sonnet-4-6"), AntigravityModels.cliArgs("claude-sonnet-4-6", null))
    }

    @Test
    fun `no model selected omits every flag`() {
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs(null, null))
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs("default", null))
    }
}
