package com.pinapia.vana.thread

import com.pinapia.vana.storage.AtomicFiles
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.vision.AttachmentStore
import java.io.File
import java.text.BreakIterator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一条侧聊:用户从主对话旁边单独拿出来聊的一件事。
 *
 * 名单上只记这几样;对话本身是一条和主对话**同一格式**的线程([ThreadStore]),换了个目录而已。
 * 五个字段都是必填的:缺一样就读不懂,读不懂的那一条原样留在名单里([SideChatStore] 的 `foreign`)。
 */
@Serializable
data class SideChat(
    val id: String,
    /** 他起的名字。留空时是 ""——拿第一句话自动起一个([autoTitled]),之后他能改。 */
    val title: String,
    /** 名字还没被他定过:第一句带字的话会拿来起名。他改过一次就是 false,再也不替他改。 */
    val autoTitled: Boolean,
    val createdAt: Instant,
    val lastActiveAt: Instant,
) {
    /** 界面上显示的名字。还没起名的叫「新侧聊」。 */
    val displayTitle: String
        get() = title.ifEmpty { L10n.text("新侧聊", "New side chat") }

    companion object {
        fun new(title: String, now: Instant = Clock.System.now()): SideChat {
            val cleaned = SideChatTitle.clean(title)
            return SideChat(
                id = UUID.randomUUID().toString(),
                title = cleaned,
                autoTitled = cleaned.isEmpty(),
                createdAt = now,
                lastActiveAt = now,
            )
        }
    }
}

/** 侧聊的名字怎么来。纯函数。长度按**字**数(字素簇),不按 UTF-16 码元:一个表情不该算两个字。 */
object SideChatTitle {
    /** 拿第一句话起名时最多取多少个字。再长就是一句话了,列表上一行放不下,标题栏也放不下。 */
    const val MAX_LENGTH = 20

    /**
     * 他自己起的名字最长多少个字符。比自动起名宽一倍:这是他特意打的,而同样一个名字英文要比中文
     * 长两三倍(「十月去京都」是五个字,「Kyoto in October」是十六个字符)——按中文的上限截,
     * 英文名字会被切掉半个词(iOS 上踩过)。
     */
    const val MAX_TYPED_LENGTH = 40

    /** 他自己起的名字:去掉首尾空白、换行压成空格、截到上限。 */
    fun clean(raw: String): String {
        val flattened = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return prefix(flattened, MAX_TYPED_LENGTH)
    }

    /** 拿第一句话起名:只取第一行,太长就截断加省略号。一个字都没有(只发了照片)返回 null,等下一句带字的。 */
    fun make(text: String): String? {
        val firstLine = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        if (length(firstLine) <= MAX_LENGTH) return firstLine
        return prefix(firstLine, MAX_LENGTH - 1) + "…"
    }

    fun length(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    private fun prefix(text: String, limit: Int): String {
        val iterator = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = 0
        repeat(limit) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text
            end = next
        }
        return text.substring(0, end)
    }
}

/**
 * 每个成员一个 `sides/` 目录:
 *
 * ```
 * <tenantRoot>/sides/
 *   index.json      名单:[{id, title, autoTitled, createdAt, lastActiveAt}]
 *   <uuid>/         和 thread/ 同一格式:seg-*.jsonl + meta.json(窗口游标、收割水位线)
 * ```
 *
 * **线程格式一个字不改**:[ThreadStore] 换个目录就是侧聊,窗口、收割、召回全部原样。照片照旧放在成员的
 * `attachments/` 里。
 *
 * 名单的读法和 `NoteStore`、`TaskStore` 一样稳妥:逐条解码、读不懂的原样留着、整份读不出来先备份、原子写。
 *
 * **每个 `sides/` 目录只有一个实例**([instance]),**每条侧聊只有一个 [ThreadWriter]**([writer]):
 * 同一个目录上两个实例,就是两份各记各的名单缓存、两个各自的线程写者,位置和已删的记账会对不上。
 */
class SideChatStore internal constructor(
    val directory: File,
    private val attachments: AttachmentStore? = null,
) {
    private val indexFile = File(directory, INDEX_NAME)
    private val backupFile = File(directory, "$INDEX_NAME.bak")
    private val mutex = Mutex()
    private var cached: List<SideChat>? = null

    /** 读不懂的那几条,原样留着、原样写回去。 */
    private var foreign: List<JsonElement> = emptyList()

    /**
     * 这个进程里**第一次从盘上读名单时**,它是读懂了的。只有这时候才许清孤儿目录:整份读不出来
     * (已经备份成 `.bak`)或者干脆没有名单文件时,每一个目录看起来都是孤儿。之后这个进程里新写的名单
     * 不算数——读不懂的那份里记着的目录,不会因为他在这之后新建了一条侧聊就变成垃圾。
     */
    private var firstReadWasTrustworthy = false
    private var didRead = false
    private var didSweep = false

    private val writers = HashMap<String, ThreadWriter>()

    /** 界面离开之后还得做完的那几件(改名、记活跃、离开时落盘),不挂在任何一个界面的生命周期上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _revision = MutableStateFlow(0L)

    /** 名单每变一次加一。列表页据此重读。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    // ------------------------------------------------------------------ 线程

    /** 这条侧聊的线程写者。同一条永远拿到同一个实例。 */
    fun writer(id: String): ThreadWriter = synchronized(writers) {
        writers.getOrPut(id) { ThreadWriter(ThreadStore(File(directory, id), attachments)) }
    }

    /** 名单上每一条的线程,最近说过话的在前。收割和「对话历史」用。 */
    suspend fun allWriters(): List<ThreadWriter> = all().map { writer(it.id) }

    /** 在这份名单自己的作用域里做一件事:界面走了它也会做完。 */
    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }

    // ------------------------------------------------------------------ 名单

    /** 最近说过话的在前。 */
    suspend fun all(): List<SideChat> {
        val chats = locked { loaded() }
        sweepOrphansIfNeeded()
        return chats.sortedByDescending { it.lastActiveAt }
    }

    suspend fun get(id: String): SideChat? = locked { loaded().firstOrNull { it.id == id } }

    suspend fun create(title: String, now: Instant = Clock.System.now()): SideChat = locked {
        val chat = SideChat.new(title, now)
        write(loaded() + chat)
        chat
    }

    /** 他改了名字。从此不再替他起名;改成空的就退回「新侧聊」,也不再自动起名——他清空它是一个明确的动作。 */
    suspend fun rename(id: String, title: String): SideChat? =
        update(id) { it.copy(title = SideChatTitle.clean(title), autoTitled = false) }

    /** 在里面说了一句话。名单按这个排;还没起名的顺手拿这句话起名。 */
    suspend fun noteActivity(id: String, text: String, now: Instant = Clock.System.now()): SideChat? =
        update(id) { chat ->
            val title = if (chat.autoTitled) SideChatTitle.make(text) else null
            chat.copy(
                lastActiveAt = now,
                title = title ?: chat.title,
                autoTitled = chat.autoTitled && title == null,
            )
        }

    // ------------------------------------------------------------------ 删

    /**
     * 删掉一条侧聊,连同它的照片。
     *
     * **顺序是先落名单,再清线程,最后删目录。** 反过来的话,删到一半崩了,名单上留着一条点进去是空的
     * 侧聊;按这个顺序最坏只剩一个不在名单上的目录,下次启动时清掉([sweepOrphansIfNeeded])。
     */
    suspend fun delete(id: String) {
        val removed = locked {
            val chats = loaded()
            if (chats.none { it.id == id }) return@locked false
            write(chats.filterNot { it.id == id })
            true
        }
        if (removed) removeThread(id)
    }

    /** 清空全部侧聊。设置 › 对话历史用。 */
    suspend fun deleteAll() {
        val ids = locked {
            val ids = loaded().map { it.id }
            write(emptyList())
            ids
        }
        ids.forEach { removeThread(it) }
    }

    /**
     * 清掉 [cutoff] 之前的消息,返回清了多少条。**整条都在那之前的侧聊连名单一起删**:留下一个名字、
     * 点进去什么都没有,只会让他以为是出了错。
     */
    suspend fun deleteOlderThan(cutoff: Instant): Int {
        var removed = 0
        for (chat in locked { loaded() }) {
            removed += writer(chat.id).write { it.deleteOlderThan(cutoff) }
            if (chat.lastActiveAt < cutoff) delete(chat.id)
        }
        return removed
    }

    /** 所有侧聊在盘上一共占多少字节。 */
    suspend fun sizeBytes(): Long = locked { loaded() }.sumOf { chat -> writer(chat.id).write { it.sizeBytes() } }

    private suspend fun removeThread(id: String) {
        writer(id).write { it.deleteAll() }
        withContext(Dispatchers.IO) { File(directory, id).deleteRecursively() }
        synchronized(writers) { writers.remove(id) }
    }

    /**
     * 名单上没有的目录:删到一半崩了留下的。**只在名单读懂了的时候清**,读不懂的那几条(`foreign`)的目录
     * 也放过——那是还没被理解的数据,不是垃圾。
     */
    private suspend fun sweepOrphansIfNeeded() {
        val orphans = locked {
            if (didSweep) return@locked emptyList<String>()
            didSweep = true
            if (!firstReadWasTrustworthy) return@locked emptyList<String>()
            // 以前有一份读不懂的名单被备份过:它被新的覆盖之后,下一次启动读到的是一份读得懂的新名单,
            // 旧名单上的那几条在它眼里全是孤儿——可它们只是还没被找回来。有备份在,就一个都不清。
            if (backupFile.exists()) return@locked emptyList<String>()
            val known = loaded().mapTo(HashSet()) { it.id }
            foreign.forEach { element ->
                ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(known::add)
            }

            directory.listFiles().orEmpty()
                .filter { it.isDirectory && isUuid(it.name) && it.name !in known }
                .map { it.name }
        }
        orphans.forEach { removeThread(it) }
    }

    // ------------------------------------------------------------------ 读写

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private suspend fun update(id: String, transform: (SideChat) -> SideChat): SideChat? = locked {
        val chats = loaded().toMutableList()
        val index = chats.indexOfFirst { it.id == id }
        if (index < 0) return@locked null
        val next = transform(chats[index]).copy(id = id)
        chats[index] = next
        write(chats)
        next
    }

    private fun loaded(): List<SideChat> {
        cached?.let { return it }
        val first = !didRead
        didRead = true
        if (!indexFile.exists()) {
            // 没有名单文件:还一条侧聊都没建过。目录里要是有东西,那不是这份代码写的,别碰。
            foreign = emptyList()
            return emptyList<SideChat>().also { cached = it }
        }
        val elements = runCatching { json.parseToJsonElement(indexFile.readText()) as JsonArray }.getOrNull()
        if (elements == null) {
            if (!backupFile.exists()) runCatching { indexFile.copyTo(backupFile) }
            foreign = emptyList()
            return emptyList<SideChat>().also { cached = it }
        }
        val chats = ArrayList<SideChat>()
        val unknown = ArrayList<JsonElement>()
        for (element in elements) {
            val chat = runCatching { json.decodeFromJsonElement(SideChat.serializer(), element) }.getOrNull()
            if (chat != null && isUuid(chat.id)) chats += chat else unknown += element
        }
        foreign = unknown
        if (first) firstReadWasTrustworthy = true
        return chats.also { cached = it }
    }

    private fun write(chats: List<SideChat>) {
        cached = chats
        val elements = chats.map { json.encodeToJsonElement(SideChat.serializer(), it) } + foreign
        directory.mkdirs()
        AtomicFiles.writeText(indexFile, json.encodeToString(JsonArray.serializer(), JsonArray(elements)))
        _revision.value = _revision.value + 1
    }

    companion object {
        const val DIRECTORY_NAME = "sides"
        const val INDEX_NAME = "index.json"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        private val instances = HashMap<String, SideChatStore>()

        /** 这个目录的那一个实例。成员那一套 store(`TenantStores`)从这里拿。 */
        fun instance(directory: File, attachments: AttachmentStore?): SideChatStore {
            val key = directory.absoluteFile.normalize().path
            return synchronized(instances) {
                instances.getOrPut(key) { SideChatStore(directory, attachments) }
            }
        }

        /**
         * 某条主对话线程旁边的那份名单(`<成员>/thread` 旁边的 `<成员>/sides`)。
         *
         * 聊天界面没被告知用哪份名单时从它手里的线程推出来:测试传进来的是临时目录里的线程,推出来的也就是
         * 临时目录里的名单——碰不到手机上那份真的(「清空全部对话」连侧聊一起清,默认值要是指着
         * `TenantScope.currentStores`,一条测试就能把真的侧聊全删了)。
         */
        fun beside(main: ThreadStore): SideChatStore =
            instance(File(main.directory.absoluteFile.parentFile, DIRECTORY_NAME), main.attachments)

        /** 只认我们自己写出去的那种(小写、标准格式):`UUID.fromString` 连「1-1-1-1-1」都收。 */
        private fun isUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}
