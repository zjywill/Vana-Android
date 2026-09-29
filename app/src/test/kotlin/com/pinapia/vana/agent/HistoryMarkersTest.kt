package com.pinapia.vana.agent

import com.pinapia.vana.session.ChatMessage
import java.time.ZoneId
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryMarkersTest {
    private val zone = ZoneId.of("UTC")

    private fun user(text: String, at: String) =
        ChatMessage(role = ChatMessage.Role.USER, text = text, createdAt = Instant.parse(at))

    private fun assistant(text: String, at: String) =
        ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, createdAt = Instant.parse(at))

    private fun ms(text: String) = java.time.Instant.parse(text)

    @Test
    fun aShortGapGetsNoMarker() {
        assertNull(HistoryMarkers.marker(ms("2026-06-03T10:00:00Z"), ms("2026-06-03T12:00:00Z"), zone))
    }

    @Test
    fun aGapOfSixHoursOrMoreGetsAMarkerWithTheTimeAndHowLongItWas() {
        val marker = HistoryMarkers.marker(ms("2026-06-02T19:00:00Z"), ms("2026-06-03T09:10:00Z"), zone)!!
        assertTrue(marker, marker.contains("6月3日 09:10"))
        assertTrue(marker, marker.contains("14 小时"))
    }

    @Test
    fun longGapsAreExpressedInDays() {
        val marker = HistoryMarkers.marker(ms("2026-06-01T10:00:00Z"), ms("2026-06-05T10:00:00Z"), zone)!!
        assertTrue(marker, marker.contains("4 天"))
    }

    @Test
    fun theMarkerIsAddedToTheNextUserMessageOnly() {
        val messages = listOf(
            user("晚上好", "2026-06-02T21:00:00Z"),
            assistant("晚上好，有什么事？", "2026-06-02T21:00:05Z"),
            user("早，昨晚睡得不错", "2026-06-03T07:30:00Z"),
            assistant("那就好", "2026-06-03T07:30:10Z"),
        )
        val dtos = HistoryMarkers.apply(messages, zone)

        assertEquals("晚上好", dtos[0].text)
        assertEquals("晚上好，有什么事？", dtos[1].text)
        assertTrue(dtos[2].text.startsWith("——（6月3日 07:30，距上一条约 10 小时）——\n"))
        assertTrue(dtos[2].text.endsWith("早，昨晚睡得不错"))
        assertEquals("助手消息不加", "那就好", dtos[3].text)
    }

    @Test
    fun theMarkerIsDeterministicSoRepeatedRequestsGetTheSamePrefix() {
        val messages = listOf(
            user("一", "2026-06-02T09:00:00Z"),
            user("二", "2026-06-03T09:00:00Z"),
        )
        assertEquals(HistoryMarkers.apply(messages, zone), HistoryMarkers.apply(messages, zone))
    }

    @Test
    fun theFirstMessageInTheWindowHasNothingToCompareWith() {
        val dtos = HistoryMarkers.apply(listOf(user("你好", "2026-06-03T09:00:00Z")), zone)
        assertFalse(dtos.single().text.contains("——"))
    }

    // ---- 主动消息折进下一条用户消息 ----

    private fun proactive(text: String, at: String) =
        ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text, createdAt = Instant.parse(at), origin = ChatMessage.Origin.CHECK_IN)

    @Test
    fun aProactiveMessageIsFoldedIntoTheNextUserMessageInsteadOfBeingItsOwnAssistantTurn() {
        val messages = listOf(
            proactive("今天有什么想关注的？", "2026-06-03T08:00:00Z"),
            user("想把作息调一调", "2026-06-03T08:01:00Z"),
            assistant("可以先从起床时间开始", "2026-06-03T08:01:10Z"),
        )
        val dtos = HistoryMarkers.apply(messages, zone)

        assertEquals("请求里只剩一问一答，助手消息不会以主动消息开头", 2, dtos.size)
        assertEquals(com.pinapia.vana.agentruntime.AgentChatMessageDTO.Role.USER, dtos[0].role)
        assertTrue(dtos[0].text, dtos[0].text.startsWith("（Vana 之前主动说过：今天有什么想关注的？）\n"))
        assertTrue(dtos[0].text.endsWith("想把作息调一调"))
    }

    @Test
    fun severalProactiveMessagesBeforeOneUserMessageAreJoined() {
        val messages = listOf(
            assistant("回答", "2026-06-03T08:00:00Z"),
            proactive("提醒一", "2026-06-03T09:00:00Z"),
            proactive("提醒二", "2026-06-03T09:05:00Z"),
            user("好的", "2026-06-03T09:10:00Z"),
        )
        val dtos = HistoryMarkers.apply(messages, zone)
        assertEquals(2, dtos.size)
        assertTrue(dtos[1].text.contains("提醒一；提醒二"))
    }

    @Test
    fun aTrailingProactiveMessageWithNoUserReplyIsSimplyNotSent() {
        val messages = listOf(user("你好", "2026-06-03T08:00:00Z"), proactive("有事吗", "2026-06-03T09:00:00Z"))
        assertEquals(1, HistoryMarkers.apply(messages, zone).size)
    }
}
