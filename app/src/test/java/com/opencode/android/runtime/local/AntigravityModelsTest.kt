package com.opencode.android.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OUTPUT] is captured verbatim from a real signed-in `agy models` run. */
class AntigravityModelsTest {
    private val output =
        """
        Gemini 3.6 Flash (High)
        Gemini 3.6 Flash (Medium)
        Gemini 3.6 Flash (Low)
        Gemini 3.5 Flash (High)
        Gemini 3.5 Flash (Medium)
        Gemini 3.5 Flash (Low)
        Gemini 3.1 Pro (High)
        Gemini 3.1 Pro (Low)
        Claude Sonnet 4.6 (Thinking)
        Claude Opus 4.6 (Thinking)
        GPT-OSS 120B (Medium)
        """.trimIndent()

    @Test
    fun `parses the real agy models output`() {
        val entries = AntigravityModels.parse(output)
        assertEquals(11, entries.size)
        assertEquals(AntigravityModels.Entry("Gemini 3.6 Flash", "High"), entries.first())
        assertEquals(AntigravityModels.Entry("GPT-OSS 120B", "Medium"), entries.last())
    }

    @Test
    fun `groups entries by base model with variants in order`() {
        val catalog = AntigravityModels.catalog(AntigravityModels.parse(output))
        val provider = catalog.all.single()
        assertEquals(6, provider.models.size)
        val flash = provider.models.getValue("Gemini 3.6 Flash")
        assertEquals(listOf("High", "Medium", "Low"), flash.variants.keys.toList())
        val sonnet = provider.models.getValue("Claude Sonnet 4.6")
        assertEquals(listOf("Thinking"), sonnet.variants.keys.toList())
        assertEquals("Gemini 3.6 Flash", catalog.default[AntigravityModels.PROVIDER_ID])
    }

    @Test
    fun `falls back to a single placeholder model when nothing was parsed`() {
        val catalog = AntigravityModels.catalog(emptyList())
        val provider = catalog.all.single()
        assertEquals(1, provider.models.size)
        assertTrue(provider.models.containsKey("default"))
    }

    @Test
    fun `a line without a variant suffix has no variant`() {
        val entries = AntigravityModels.parse("Some Custom Model\n")
        assertEquals(AntigravityModels.Entry("Some Custom Model", null), entries.single())
    }

    @Test
    fun `effort words map to the dedicated flag`() {
        assertEquals(listOf("--model", "Gemini 3.1 Pro", "--effort", "high"), AntigravityModels.cliArgs("Gemini 3.1 Pro", "High"))
        assertEquals(listOf("--model", "Gemini 3.1 Pro", "--effort", "low"), AntigravityModels.cliArgs("Gemini 3.1 Pro", "low"))
    }

    @Test
    fun `a non-effort variant is sent as part of the model label`() {
        assertEquals(
            listOf("--model", "Claude Sonnet 4.6 (Thinking)"),
            AntigravityModels.cliArgs("Claude Sonnet 4.6", "Thinking"),
        )
    }

    @Test
    fun `no model selected omits every flag`() {
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs(null, null))
        assertEquals(emptyList<String>(), AntigravityModels.cliArgs("default", null))
    }

    @Test
    fun `a model with no variant is still sent`() {
        assertEquals(listOf("--model", "Gemini 3.1 Pro"), AntigravityModels.cliArgs("Gemini 3.1 Pro", null))
    }
}
