package com.pinapia.vana.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPresentationLogicTest {
    @Test
    fun trailingReplyIndicatorFollowsTheLiveTurn() {
        assertFalse(
            showsTrailingReplyIndicator(
                isLiveReply = false,
                hasRunningToolCall = false,
            ),
        )
        assertTrue(
            showsTrailingReplyIndicator(
                isLiveReply = true,
                hasRunningToolCall = false,
            ),
        )
        assertFalse(
            showsTrailingReplyIndicator(
                isLiveReply = true,
                hasRunningToolCall = true,
            ),
        )
    }
}
