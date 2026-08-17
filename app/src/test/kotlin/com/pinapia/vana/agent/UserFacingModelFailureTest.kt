package com.pinapia.vana.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingModelFailureTest {
    @Test
    fun translatesActionableFailures() {
        assertTrue(UserFacingModelFailure.message("Error code: 401").contains("API 密钥"))
        assertTrue(UserFacingModelFailure.message("insufficient quota").contains("额度"))
        assertTrue(UserFacingModelFailure.message("prompt is too long").contains("新对话"))
        assertTrue(UserFacingModelFailure.message("server overloaded").contains("暂时"))
    }

    @Test
    fun keepsUnknownFailureForDiagnostics() {
        val raw = "a failure nobody has classified"
        assertEquals(raw, UserFacingModelFailure.message(raw))
        assertFalse(UserFacingModelFailure.message("invalid_api_key").contains("invalid_api_key"))
    }
}
