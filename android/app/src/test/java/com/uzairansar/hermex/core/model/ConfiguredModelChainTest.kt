package com.uzairansar.hermex.core.model

import com.uzairansar.hermex.core.network.HermesJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfiguredModelChainTest {
    private val catalogJson = """
        {
          "active_provider": "anthropic",
          "default_model": "claude-opus-5-5",
          "groups": [
            {"provider": "Anthropic", "provider_id": "anthropic", "models": [
              {"id": "claude-opus-4-5-20251101", "label": "Claude Opus 4 5 20251101"},
              {"id": "claude-opus-4-8", "label": "Claude Opus 4 8"},
              {"id": "claude-opus-5-5", "label": "Claude Opus 5 5"}
            ]},
            {"provider": "OpenAI Codex", "provider_id": "openai-codex", "models": [
              {"id": "@openai-codex:gpt-6-astra", "label": "GPT 6 ASTRA"},
              {"id": "@openai-codex:gpt-6-sol", "label": "GPT 6 SOL"}
            ]},
            {"provider": "Z.AI Coding", "provider_id": "zai-coding", "models": [
              {"id": "@zai-coding:glm-5.3", "label": "glm-5.3"}
            ]},
            {"provider": "OpenRouter", "provider_id": "openrouter", "models": [
              {"id": "google/gemini-3.7-flash", "label": "Gemini 3.7 Flash"}
            ]}
          ],
          "configured_model_badges": {
            "google/gemini-3.7-flash": {"role": "fallback", "label": "Fallback 10", "provider": "openrouter"},
            "glm-5.3": {"role": "fallback", "label": "Fallback 2", "provider": "zai-coding"},
            "@zai-coding:glm-5.3": {"role": "fallback", "label": "Fallback 2", "provider": "zai-coding"},
            "k3-256k": {"role": "fallback", "label": "Fallback 3", "provider": "kimi-coding"},
            "claude-opus-5-5": {"role": "primary", "label": "Primary", "provider": "anthropic"},
            "@anthropic:claude-opus-5-5": {"role": "primary", "label": "Primary", "provider": "anthropic"},
            "gpt-6-sol": {"role": "fallback", "label": "Fallback 1", "provider": "openai-codex"}
          }
        }
    """.trimIndent()

    @Test
    fun pickerShowsOnlyPrimaryAndFallbackChainInChainOrder() {
        val decoded = HermesJson.decodeFromString<ModelCatalogResponse>(catalogJson)

        val chain = decoded.configuredChainModels

        assertEquals(
            listOf("claude-opus-5-5", "@openai-codex:gpt-6-sol", "@zai-coding:glm-5.3", "k3-256k", "google/gemini-3.7-flash"),
            chain.map { it.id },
        )
        assertEquals(
            listOf("anthropic", "openai-codex", "zai-coding", "kimi-coding", "openrouter"),
            chain.map { it.provider },
        )
        // Catalog labels are kept for matched entries; a chain model absent from the catalog stays selectable.
        assertEquals("Claude Opus 5 5", chain.first().label)
        assertEquals("k3-256k", chain[3].label)
    }

    @Test
    fun primaryModelIsFirstAndResolvesToTheConfiguredDefault() {
        val decoded = HermesJson.decodeFromString<ModelCatalogResponse>(catalogJson)

        assertEquals("claude-opus-5-5", decoded.configuredChainModels.first().id)
        assertEquals("claude-opus-5-5", decoded.configuredPrimaryModel?.id)
        assertEquals("anthropic", decoded.configuredPrimaryModel?.provider)
    }

    @Test
    fun catalogWithoutConfiguredChainKeepsTheFullList() {
        val decoded = HermesJson.decodeFromString<ModelCatalogResponse>(
            """{"groups":[{"provider_id":"anthropic","models":[{"id":"claude-opus-4-5-20251101"},{"id":"claude-opus-5-5"}]}]}""",
        )

        assertEquals(decoded.flattenedModels, decoded.configuredChainModels)
        assertNull(decoded.configuredPrimaryModel)
    }
}
