package com.pinapia.vana.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineSettingsLogicTest {
    @Test
    fun defaultsMatchReviewConfiguration() {
        assertEquals("deepseek", EngineSettings.DEFAULT_PROVIDER)
        assertEquals("deepseek-chat", EngineSettings.DEFAULT_MODEL)
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
        assertEquals(null, CloudCatalog.WireProtocol.fromAdapter("unknown"))
    }
}
