package com.pinapia.vana.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineSettingsLogicTest {
    @Test
    fun defaultsMatchReviewConfiguration() {
        assertEquals("deepseek", EngineSettings.DEFAULT_PROVIDER)
        assertEquals("deepseek-v4-flash", EngineSettings.DEFAULT_MODEL)
    }

    @Test
    fun wireProtocolAdaptersAreStable() {
        assertEquals(
            CloudCatalog.WireProtocol.ANTHROPIC,
            CloudCatalog.WireProtocol.fromAdapter("anthropic"),
        )
        assertEquals(
            CloudCatalog.WireProtocol.OPENAI,
            CloudCatalog.WireProtocol.fromAdapter("openai"),
        )
        assertEquals(
            CloudCatalog.WireProtocol.GEMINI,
            CloudCatalog.WireProtocol.fromAdapter("gemini"),
        )
        assertEquals(null, CloudCatalog.WireProtocol.fromAdapter("unknown"))
    }

    @Test
    fun geminiModelListingUsesGoogleShape() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models",
            ModelListClient.modelsEndpoint(
                "https://generativelanguage.googleapis.com",
                CloudCatalog.WireProtocol.GEMINI,
            ),
        )
        val models = ModelListClient.decodeModels(
            """
            {
              "models": [
                {
                  "name": "models/gemini-3.5-flash",
                  "displayName": "Gemini 3.5 Flash",
                  "supportedGenerationMethods": ["generateContent"]
                },
                {
                  "name": "models/text-embedding-005",
                  "supportedGenerationMethods": ["embedContent"]
                }
              ]
            }
            """.trimIndent(),
            CloudCatalog.WireProtocol.GEMINI,
        )

        assertEquals(listOf("gemini-3.5-flash"), models.map { it.id })
        assertEquals("Gemini 3.5 Flash", models.single().displayName)
    }
}
