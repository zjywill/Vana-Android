package com.pinapia.vana.agent

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAssistantInstructionsTest {
    @Test
    fun answerLanguageAndEmergencyNumberFollowUiLanguage() {
        withLocale(Locale.ENGLISH) {
            val prompt = HealthAssistantInstructions.text()
            assertTrue(prompt.contains("用English回答"))
            assertFalse(prompt.contains("中国大陆是 120"))
        }
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            val prompt = HealthAssistantInstructions.text()
            assertTrue(prompt.contains("用简体中文回答"))
            assertTrue(prompt.contains("中国大陆是 120"))
        }
    }

    private inline fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
