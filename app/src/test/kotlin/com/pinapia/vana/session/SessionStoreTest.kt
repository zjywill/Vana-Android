package com.pinapia.vana.session

import java.io.File
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun savesAndLoadsSession() {
        val store = SessionStore(parent = folder.root)
        val session = ChatSession(
            messages = listOf(
                ChatMessage(role = ChatMessage.Role.USER, text = "昨晚睡得怎么样？"),
            ),
            updatedAt = Clock.System.now(),
        )
        store.save(session)
        val loaded = store.load(session.id)
        assertEquals(session.id, loaded?.id)
        assertEquals("昨晚睡得怎么样？", loaded?.messages?.first()?.text)
        assertEquals(1, store.listSummaries().size)
    }

    @Test
    fun privateSessionNeverPersists() {
        val store = SessionStore(parent = folder.root)
        val session = ChatSession(
            messages = listOf(ChatMessage(role = ChatMessage.Role.USER, text = "秘密")),
            isPrivate = true,
        )
        store.save(session)
        assertNull(store.load(session.id))
        assertTrue(store.listSummaries().isEmpty())
    }

    @Test
    fun emptySessionNotSaved() {
        val store = SessionStore(parent = folder.root)
        store.save(ChatSession())
        assertTrue(store.listSummaries().isEmpty())
    }
}
