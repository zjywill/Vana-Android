package com.pinapia.vana.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 侧聊的宿主:离开时还在写的留着写完、亮未读点、回来接上同一个对象;删它、换成员时停下。
 * 口径同 iOS 的 `SideChatTests.hostKeepsReplyingModel` / `hostDiscardStops`。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SideChatHostTest {
    private class FakeChat : SideChatHost.Hosted {
        override val isReplying = MutableStateFlow(false)
        val leaves = ArrayList<Boolean>()
        var disposed = false

        override fun leave(harvesting: Boolean): Job? {
            leaves += harvesting
            isReplying.value = false // 等于按了停止
            return null
        }

        override fun dispose() {
            disposed = true
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aSideChatClosedMidReplyKeepsWritingAndComesBackUnread() {
        val host = SideChatHost()
        val chat = host.open("a") { FakeChat() }
        chat.isReplying.value = true
        assertEquals(setOf("a"), host.replying.value)

        host.close("a")
        assertSame("关掉时还在写:留着", chat, host.hosted("a"))
        assertTrue(chat.leaves.isEmpty())
        assertFalse(chat.disposed)

        chat.isReplying.value = false
        assertEquals("写完了:亮未读点", setOf("a"), host.unread.value)
        assertNull("再放掉", host.hosted("a"))
        assertEquals(listOf(true), chat.leaves) // 放掉时收割一次
        assertTrue(chat.disposed)
        assertTrue(host.replying.value.isEmpty())

        host.open("a") { FakeChat() }
        assertTrue("打开就算看过了", host.unread.value.isEmpty())
    }

    @Test
    fun comingBackWhileItIsStillWritingGetsTheSameObjectAndCancelsTheRelease() {
        val host = SideChatHost()
        val chat = host.open("a") { FakeChat() }
        chat.isReplying.value = true
        host.close("a")

        assertSame(chat, host.open("a") { FakeChat() })
        chat.isReplying.value = false
        assertSame("他又回来了:写完也不放掉", chat, host.hosted("a"))
        assertTrue(host.unread.value.isEmpty())
        assertTrue(chat.leaves.isEmpty())
    }

    @Test
    fun closingAnIdleSideChatLetsGoOfItAtOnce() {
        val host = SideChatHost()
        val chat = host.open("a") { FakeChat() }
        host.close("a")
        assertNull(host.hosted("a"))
        assertEquals(listOf(true), chat.leaves)
        assertTrue(chat.disposed)
        assertTrue(host.unread.value.isEmpty())
    }

    @Test
    fun discardingASideChatStopsItsReplyWithoutHarvestingAndLetsGoOfIt() {
        val host = SideChatHost()
        val chat = host.open("a") { FakeChat() }
        chat.isReplying.value = true
        host.close("a")

        host.discard("a")
        assertEquals(listOf(false), chat.leaves)
        assertTrue(chat.disposed)
        assertNull(host.hosted("a"))
        assertTrue(host.unread.value.isEmpty())
        assertTrue(host.replying.value.isEmpty())
    }

    /** 换成员、清空全部对话:主对话那一项整个换掉,还在写的那几个一起停下。 */
    @Test
    fun releasingEverythingStopsTheOnesStillWriting() {
        val host = SideChatHost()
        val writing = host.open("a") { FakeChat() }
        val idle = host.open("b") { FakeChat() }
        writing.isReplying.value = true
        host.close("a")

        host.releaseAll()
        assertEquals(listOf(true), writing.leaves)
        assertFalse(writing.isReplying.value)
        assertTrue(writing.disposed)
        assertTrue(idle.disposed)
        assertNull(host.hosted("a"))
        assertNull(host.hosted("b"))
    }
}
