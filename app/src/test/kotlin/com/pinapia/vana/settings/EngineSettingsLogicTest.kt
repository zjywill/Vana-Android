package com.pinapia.vana.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSettingsLogicTest {
    @Test
    fun defaultsMatchCatalog() {
        assertEquals("anthropic", EngineSettings.DEFAULT_PROVIDER)
        assertTrue(CloudCatalog.provider(EngineSettings.DEFAULT_PROVIDER) != null)
        assertTrue(CloudCatalog.models(EngineSettings.DEFAULT_PROVIDER).isNotEmpty())
    }

    @Test
    fun defaultModelSupportsTools() {
        val model = CloudCatalog.defaultModel("deepseek")
        assertEquals("deepseek-chat", model)
        assertTrue(CloudCatalog.model(model!!, "deepseek")!!.supportsTools)
    }

    @Test
    fun wireProtocolsPresent() {
        assertEquals(CloudCatalog.WireProtocol.ANTHROPIC, CloudCatalog.provider("anthropic")!!.wireProtocol)
        assertEquals(CloudCatalog.WireProtocol.OPENAI, CloudCatalog.provider("openai")!!.wireProtocol)
        assertEquals(CloudCatalog.WireProtocol.OPENAI, CloudCatalog.provider("dashscope")!!.wireProtocol)
    }
}
