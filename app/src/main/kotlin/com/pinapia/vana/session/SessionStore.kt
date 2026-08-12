package com.pinapia.vana.session

import com.pinapia.vana.memory.InterestProfile
import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class SessionStore(
    private val parent: File,
    private val json: Json = defaultJson,
) {
    private val directory: File
        get() = File(parent, "sessions").also { it.mkdirs() }

    fun listEntries(): List<SessionIndexEntry> {
        return directory.listFiles()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val session = json.decodeFromString(ChatSession.serializer(), file.readText())
                    val seen = linkedSetOf<String>()
                    val tools = session.messages
                        .flatMap { it.toolCalls.map { call -> call.name } }
                        .filter { seen.add(it) }
                    val firstUser = session.messages.firstOrNull { it.role == ChatMessage.Role.USER }
                    SessionIndexEntry(
                        id = session.id,
                        title = SessionTitle.make(
                            threadId = session.threadId,
                            threadTitle = session.threadTitle,
                            topicId = session.topicId,
                            firstUserText = firstUser?.text,
                            firstUserHasAttachments = firstUser?.attachments?.isNotEmpty() == true,
                            createdAt = session.createdAt,
                        ),
                        updatedAt = session.updatedAt,
                        messageCount = session.messages.size,
                        threadId = session.threadId,
                        threadTitle = session.threadTitle,
                        isDerived = session.isDerived,
                        topicId = session.topicId,
                        toolNames = tools,
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
    }

    /** 从会话里数他实际问过什么。最近的在前。 */
    fun interests(): InterestProfile {
        val entries = listEntries().map {
            InterestProfile.Companion.Entry(
                isDerived = it.isDerived,
                toolNames = it.toolNames,
            )
        }
        return InterestProfile.build(entries)
    }

    fun listSummaries(): List<SessionSummary> =
        listEntries()
            .filter { SessionThread.parse(it.threadId)?.isGoal != true }
            .map {
                SessionSummary(
                    id = it.id,
                    title = displayTitle(it),
                    updatedAt = it.updatedAt,
                    messageCount = it.messageCount,
                    threadId = it.threadId,
                    isDerived = it.isDerived,
                )
            }

    fun goals(): List<GoalSummary> {
        val byThread = linkedMapOf<String, MutableList<SessionIndexEntry>>()
        for (entry in listEntries()) {
            val thread = entry.thread ?: continue
            if (!thread.isGoal) continue
            byThread.getOrPut(entry.threadId!!) { mutableListOf() } += entry
        }
        return byThread.map { (threadId, segments) ->
            val latest = segments.maxBy { it.updatedAt }
            GoalSummary(
                threadId = threadId,
                title = latest.threadTitle?.takeIf { it.isNotBlank() }
                    ?: SessionThread.parse(threadId)?.title
                    ?: "长期目标",
                updatedAt = latest.updatedAt,
                latestSessionId = latest.id,
                segmentCount = segments.size,
                messageCount = segments.sumOf { it.messageCount },
            )
        }.sortedByDescending { it.updatedAt }
    }

    fun entries(inThread: SessionThread): List<SessionIndexEntry> =
        listEntries().filter { it.threadId == inThread.id }.sortedByDescending { it.updatedAt }

    fun latestInThread(thread: SessionThread): SessionIndexEntry? =
        entries(inThread = thread).firstOrNull()

    fun openThread(thread: SessionThread, now: Instant = Clock.System.now()): ChatSession? {
        val latest = latestInThread(thread) ?: return null
        if (!SessionThreadPolicy.canContinue(latest, now)) return null
        return load(latest.id)
    }

    fun renameThread(thread: SessionThread, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        for (entry in entries(thread)) {
            val session = load(entry.id) ?: continue
            save(session.copy(threadTitle = trimmed, updatedAt = Clock.System.now()))
        }
    }

    fun deleteThread(thread: SessionThread) {
        for (entry in entries(thread)) {
            delete(entry.id)
        }
    }

    fun deleteAll() {
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
    }

    fun load(id: String): ChatSession? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(ChatSession.serializer(), file.readText())
        }.getOrNull()
    }

    fun save(session: ChatSession) {
        if (session.isPrivate || session.isEmpty) return
        val file = fileFor(session.id)
        file.writeText(json.encodeToString(ChatSession.serializer(), session))
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun displayTitle(entry: SessionIndexEntry): String {
        val thread = entry.thread
        return if (thread != null) {
            val base = entry.threadTitle?.takeIf { it.isNotBlank() } ?: thread.title
            "$base · ${SessionTitle.dateLabel(entry.updatedAt)}起"
        } else {
            entry.title
        }
    }

    private fun fileFor(id: String): File = File(directory, "$id.json")

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
