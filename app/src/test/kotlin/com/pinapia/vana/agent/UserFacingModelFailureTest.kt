package com.pinapia.vana.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UserFacingModelFailureTest {
    @Test
    fun translatesActionableFailures() {
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            assertTrue(UserFacingModelFailure.message("Error code: 401").contains("API 密钥"))
            assertTrue(UserFacingModelFailure.message("insufficient quota").contains("额度"))
            assertTrue(UserFacingModelFailure.message("prompt is too long").contains("新对话"))
            assertTrue(UserFacingModelFailure.message("server overloaded").contains("暂时"))
        }
        withLocale(Locale.ENGLISH) {
            assertTrue(UserFacingModelFailure.message("Error code: 401").contains("API key"))
            assertTrue(UserFacingModelFailure.message("insufficient quota").contains("quota"))
            assertTrue(UserFacingModelFailure.message("prompt is too long").contains("new conversation"))
            assertTrue(UserFacingModelFailure.message("server overloaded").contains("temporarily"))
        }
    }

    @Test
    fun keepsUnknownFailureForDiagnostics() {
        val raw = "a failure nobody has classified"
        assertEquals(raw, UserFacingModelFailure.message(raw))
        assertFalse(UserFacingModelFailure.message("invalid_api_key").contains("invalid_api_key"))
    }

    @Test
    fun authenticationMessageIsStableForSetupRecovery() {
        lateinit var chinese: String
        withLocale(Locale.SIMPLIFIED_CHINESE) {
            chinese = UserFacingModelFailure.message("Error code: 401")
            assertTrue(UserFacingModelFailure.isAuthenticationMessage(chinese))
            assertFalse(UserFacingModelFailure.isAuthenticationMessage("网络暂时不可用"))
        }
        withLocale(Locale.ENGLISH) {
            val english = UserFacingModelFailure.message("Error code: 401")
            assertTrue(UserFacingModelFailure.isAuthenticationMessage(english))
            assertTrue(UserFacingModelFailure.isAuthenticationMessage(chinese))
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
