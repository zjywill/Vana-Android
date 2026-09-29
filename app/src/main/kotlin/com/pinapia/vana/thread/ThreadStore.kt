package com.pinapia.vana.thread

import com.pinapia.vana.session.ChatMessage
import com.pinapia.vana.storage.AtomicFiles
import com.pinapia.vana.vision.AttachmentStore
import java.io.File
import java.io.FileOutputStream
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 那条永远的对话在磁盘上的样子。
 *
 * ```
 * <tenantRoot>/thread/
 *   meta.json            水位线、窗口游标等(小,整个原子重写)
 *   seg-000001.jsonl     追加写,一行一条记录;满了滚到下一段
 * ```
 *
 * **追加写**代替以前「每轮把整个会话文件重写一遍」:写入量只和这一轮改了什么有关,和历史多长无关;
 * 进程被杀在半路,坏的至多是最后一行(读的时候跳过),不会像整文件写一半那样把整份历史带走。
 *
 * 一条记录是 [Rec]:要么是 put(一条消息的最新样子,带位置 `p`),要么是 del(删掉某条)。
 * 同一条消息可以被 put 很多次,**最后一次为准**——所以重试、编辑、给问题卡补上答案都还是追加。
 * 顺序由位置 `p`(浮点数)定,不由写入先后定:用户在助手还在回答时补了一句,那条回复要排在补的话
 * **前面**,可它是后写的——给它一个夹在两者之间的位置就行。
 *
 * 读是**从新往旧**:先加载最新的几段给界面,滑到顶再往前翻。一条消息在旧段里的 put 若被新段里的
 * put/del 盖过,读旧段时按 [resolved] 跳过——所以它得一直记着已经见过哪些 id。
 */
class ThreadStore(
    private val directory: File,
    private val attachments: AttachmentStore? = null,
    private val json: Json = defaultJson,
) {
    /** 界面拿到的一页:按位置排好的消息,以及还有没有更早的。 */
    data class Page(val messages: List<ChatMessage>, val oldestSegment: Int, val hasOlder: Boolean)

    @Serializable
    private data class Rec(val p: Double? = null, val m: ChatMessage? = null, val d: String? = null)

    /** 后台派生(待跟进回访)跑过的记录:什么时候跑的、得出的一句结论。 */
    @Serializable
    data class DerivedRecord(val at: Instant, val conclusion: String? = null)

    /** 线程里有记录写入或删除时通知的听众。档案索引靠它增量更新,不用每次重扫。 */
    interface ChangeListener {
        fun onChanged(puts: List<Pair<Double, ChatMessage>>, deletes: List<String>)
        fun onCleared()
    }

    @Serializable
    data class Meta(
        val schema: Int = 1,
        /** 后台派生的记录,按键(比如待跟进条目的 id)存。 */
        val derived: Map<String, DerivedRecord> = emptyMap(),
        /** 记忆收割到哪个位置为止(含)。之后的消息还没被抽过。 */
        val harvestedUpToPos: Double? = null,
        /** 滑动窗口的起点位置。窗口只在淘汰时才前移。 */
        val windowStartPos: Double? = null,
        val lastRequestAt: Instant? = null,
    )

    private val lock = Any()
    private val metaFile = File(directory, META_NAME)

    @Volatile
    var changeListener: ChangeListener? = null

    /** id → 位置。只记这个进程里见过(加载过或写过)的。 */
    private val positions = HashMap<String, Double>()

    /** 读的时候已经确定了最终状态的 id(活的或已删的)。往旧翻时据此跳过被盖过的 put。 */
    private val resolved = HashSet<String>()
    private var maxPos: Double = 0.0
    private var currentIndex: Int = 1
    private var currentRecords: Int = 0

    init {
        directory.mkdirs()
        val newest = segments().lastOrNull()
        if (newest != null) {
            currentIndex = newest.first
            healUnterminatedTail(newest.second)
            currentRecords = newest.second.readLines().count { it.isNotBlank() }
            maxPos = readRecords(newest.second).mapNotNull { it.p }.maxOrNull() ?: 0.0
        }
    }

    // ------------------------------------------------------------------ 读

    /** 最新的几段:凑够 [minMessages] 条活的消息就停,最多 [maxSegments] 段。空线程返回空页。 */
    fun loadTail(minMessages: Int = DEFAULT_TAIL_MESSAGES, maxSegments: Int = DEFAULT_TAIL_SEGMENTS): Page =
        synchronized(lock) {
            resolved.clear()
            positions.clear()
            readBackwards(before = Int.MAX_VALUE, minMessages = minMessages, maxSegments = maxSegments)
        }

    /** 再往前翻:[beforeSegment] 是上一页的 `oldestSegment`。 */
    fun loadOlder(beforeSegment: Int, minMessages: Int = DEFAULT_TAIL_MESSAGES): Page =
        synchronized(lock) {
            readBackwards(before = beforeSegment, minMessages = minMessages, maxSegments = DEFAULT_TAIL_SEGMENTS)
        }

    private fun readBackwards(before: Int, minMessages: Int, maxSegments: Int): Page {
        val files = segments().filter { it.first < before }.reversed()
        val live = HashMap<String, Pair<Double, ChatMessage>>()
        var oldest = before
        var read = 0
        for ((index, file) in files) {
            for (rec in readRecords(file).asReversed()) {
                rec.d?.let { resolved += it }
                val message = rec.m ?: continue
                if (!resolved.add(message.id)) continue
                live[message.id] = (rec.p ?: 0.0) to message
            }
            oldest = index
            read++
            if (live.size >= minMessages || read >= maxSegments) break
        }
        live.forEach { (id, entry) ->
            positions[id] = entry.first
            if (entry.first > maxPos) maxPos = entry.first
        }
        val ordered = live.values.sortedBy { it.first }.map { it.second }
        val hasOlder = files.any { it.first < oldest }
        return Page(messages = ordered, oldestSegment = oldest, hasOlder = hasOlder)
    }

    /** 这条消息在线程里的位置。只认这个进程加载过或写过的。 */
    fun positionOf(id: String): Double? = synchronized(lock) { positions[id] }

    /**
     * 位置在 [pos] 之后的活消息,按位置排。记忆收割用:只喂水位线之后没抽过的。
     * 从最新一段往前读,读到整段都在 [pos] 之前就停。
     */
    fun messagesAfter(pos: Double?): List<Pair<Double, ChatMessage>> = synchronized(lock) {
        val floor = pos ?: Double.NEGATIVE_INFINITY
        val seen = HashSet<String>()
        val live = ArrayList<Pair<Double, ChatMessage>>()
        for ((_, file) in segments().reversed()) {
            var segmentMax = Double.NEGATIVE_INFINITY
            for (rec in readRecords(file).asReversed()) {
                rec.p?.let { if (it > segmentMax) segmentMax = it }
                rec.d?.let { seen += it }
                val message = rec.m ?: continue
                if (!seen.add(message.id)) continue
                val p = rec.p ?: continue
                if (p > floor) live += p to message
            }
            if (segmentMax <= floor) break
        }
        live.sortedBy { it.first }
    }

    /**
     * 整条线程里所有活着的消息,不保证顺序,逐条交给 [consumer]。档案索引启动时扫一遍用;
     * 不把消息攒在内存里(每条都带整轮 transcript,攒起来是历史长度的几倍)。
     */
    fun scan(consumer: (pos: Double, message: ChatMessage) -> Unit) = synchronized(lock) {
        val seen = HashSet<String>()
        for ((_, file) in segments().reversed()) {
            for (rec in readRecords(file).asReversed()) {
                rec.d?.let { seen += it }
                val message = rec.m ?: continue
                if (!seen.add(message.id)) continue
                consumer(rec.p ?: 0.0, message)
            }
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 把界面持有的那一段消息同步到盘上,只写**变了的**:
     * - 盘上还没有的:按它在列表里的位置排个号(夹在前后两条之间),写一条 put;
     * - 在 [dirty] 里的:原位置再 put 一次;
     * - 上次同步过([known])、这次不在列表里的:写 del。
     *
     * 返回这次同步之后界面持有的 id 集合,下次原样传回来。只删「界面自己同步过的」——后台追加的主动消息
     * 界面还没加载到,不在 [known] 里,不会被误删。
     */
    fun sync(messages: List<ChatMessage>, dirty: Set<String>, known: Set<String>): Set<String> = synchronized(lock) {
        val out = ArrayList<Rec>()
        val nextKnown = arrayOfNulls<Double>(messages.size)
        var following: Double? = null
        for (i in messages.indices.reversed()) {
            nextKnown[i] = following
            positions[messages[i].id]?.let { following = it }
        }
        var previous: Double? = null
        for ((i, message) in messages.withIndex()) {
            val existing = positions[message.id]
            if (existing == null) {
                val next = nextKnown[i]
                val p = when {
                    previous != null && next != null && next > previous -> (previous + next) / 2
                    previous != null -> previous + 1.0
                    next != null -> next - 1.0
                    else -> maxPos + 1.0
                }
                positions[message.id] = p
                resolved += message.id
                if (p > maxPos) maxPos = p
                out += Rec(p = p, m = message)
                previous = p
            } else {
                if (message.id in dirty) out += Rec(p = existing, m = message)
                previous = existing
            }
        }
        val current = messages.mapTo(HashSet()) { it.id }
        for (id in known - current) {
            out += Rec(d = id)
            positions.remove(id)
            resolved += id
        }
        append(out)
        current
    }

    /** 加到线程最末尾。后台来的主动消息(check-in、任务结果)走这条:界面没在跑就直接落盘。 */
    fun appendAtEnd(message: ChatMessage): Double = synchronized(lock) {
        val p = maxPos + 1.0
        maxPos = p
        positions[message.id] = p
        resolved += message.id
        append(listOf(Rec(p = p, m = message)))
        p
    }

    /** 在线程里改一条已经落盘的消息(比如给结果卡补上「已处理」)。只改这个进程认得的、或读得到的。 */
    fun put(message: ChatMessage): Boolean = synchronized(lock) {
        val p = positions[message.id] ?: findPosition(message.id) ?: return false
        append(listOf(Rec(p = p, m = message)))
        true
    }

    private fun findPosition(id: String): Double? {
        val seen = HashSet<String>()
        for ((_, file) in segments().reversed()) {
            for (rec in readRecords(file).asReversed()) {
                rec.d?.let { seen += it }
                val message = rec.m ?: continue
                if (message.id != id) continue
                if (id in seen) return null
                return rec.p
            }
        }
        return null
    }

    /** 删掉这些消息,顺手清掉只有它们引用的照片。 */
    fun delete(ids: Set<String>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        val doomedAttachments = ArrayList<String>()
        scan { _, message ->
            if (message.id in ids) message.attachments.mapNotNullTo(doomedAttachments) { it.imageFileName }
        }
        append(ids.map { Rec(d = it) })
        ids.forEach {
            positions.remove(it)
            resolved += it
        }
        pruneAttachments(doomedAttachments)
    }

    /** 删掉创建时间早于 [cutoff] 的全部消息,返回删了多少条。 */
    fun deleteOlderThan(cutoff: Instant): Int = synchronized(lock) {
        val doomed = HashSet<String>()
        scan { _, message -> if (message.createdAt < cutoff) doomed += message.id }
        delete(doomed)
        doomed.size
    }

    /** 清空整条线程(和它引用过的全部照片)。 */
    fun deleteAll() = synchronized(lock) {
        directory.listFiles()?.forEach { if (it.name != LEGACY_MARKER) it.delete() }
        positions.clear()
        resolved.clear()
        maxPos = 0.0
        currentIndex = 1
        currentRecords = 0
        attachments?.removeAll()
        changeListener?.onCleared()
    }

    // ------------------------------------------------------------------ meta

    fun meta(): Meta = synchronized(lock) {
        if (!metaFile.exists()) return@synchronized Meta()
        runCatching { json.decodeFromString(Meta.serializer(), metaFile.readText()) }.getOrDefault(Meta())
    }

    fun updateMeta(transform: (Meta) -> Meta) = synchronized(lock) {
        AtomicFiles.writeText(metaFile, json.encodeToString(Meta.serializer(), transform(meta())))
    }

    /** 磁盘上这条线程一共占多少字节。设置里「对话历史」显示用。 */
    fun sizeBytes(): Long = synchronized(lock) {
        directory.listFiles()?.filter { it.name.startsWith("seg-") }?.sumOf { it.length() } ?: 0L
    }

    // ------------------------------------------------------------------ 内部

    private fun append(records: List<Rec>) {
        if (records.isEmpty()) return
        if (currentRecords >= SEGMENT_MAX_RECORDS) {
            currentIndex++
            currentRecords = 0
        }
        val text = records.joinToString(separator = "") { json.encodeToString(Rec.serializer(), it) + "\n" }
        FileOutputStream(segmentFile(currentIndex), true).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        currentRecords += records.size
        changeListener?.onChanged(
            puts = records.mapNotNull { rec -> rec.m?.let { (rec.p ?: 0.0) to it } },
            deletes = records.mapNotNull { it.d },
        )
    }

    private fun segmentFile(index: Int) = File(directory, "seg-%06d.jsonl".format(index))

    private fun segments(): List<Pair<Int, File>> =
        directory.listFiles()
            ?.mapNotNull { file ->
                val match = SEGMENT_NAME.matchEntire(file.name) ?: return@mapNotNull null
                match.groupValues[1].toInt() to file
            }
            ?.sortedBy { it.first }
            .orEmpty()

    private fun readRecords(file: File): List<Rec> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            runCatching { json.decodeFromString(Rec.serializer(), line) }.getOrNull()
        }
    }

    /** 进程死在写到一半的那一行:补一个换行,免得下一条记录被接在半行后面一起读坏。 */
    private fun healUnterminatedTail(file: File) {
        if (file.length() == 0L) return
        val last = java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read()
        }
        if (last != '\n'.code) FileOutputStream(file, true).use { it.write('\n'.code) }
    }

    /**
     * 删掉 [candidates] 里不再被任何消息引用的照片文件。整条线程里只要还有一条在引用就留着;
     * 读到坏行不影响判断(坏行读不出引用,多留一张孤儿比误删强)。
     */
    private fun pruneAttachments(candidates: List<String>) {
        val store = attachments ?: return
        if (candidates.isEmpty()) return
        val stillReferenced = HashSet<String>()
        scan { _, message -> message.attachments.forEach { it.imageFileName?.let(stillReferenced::add) } }
        store.remove(candidates.distinct().filterNot { it in stillReferenced })
    }

    companion object {
        const val META_NAME = "meta.json"
        const val LEGACY_MARKER = ".legacy-sessions-cleared"

        /** 一段最多这么多条记录。再多就滚到下一段——读旧段和往前翻都是按段来的。 */
        const val SEGMENT_MAX_RECORDS = 400
        const val DEFAULT_TAIL_MESSAGES = 60
        const val DEFAULT_TAIL_SEGMENTS = 3

        private val SEGMENT_NAME = Regex("""seg-(\d{6})\.jsonl""")

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
