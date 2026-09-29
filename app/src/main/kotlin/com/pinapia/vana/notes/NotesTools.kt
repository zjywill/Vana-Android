package com.pinapia.vana.notes

import com.pinapia.vana.agentruntime.AgentToolOutput
import com.pinapia.vana.agentruntime.CapabilityDefinition
import com.pinapia.vana.agentruntime.CapabilityExecutionResult
import com.pinapia.vana.agentruntime.CapabilityRegistry
import com.pinapia.vana.agentruntime.RuntimeJSONValue

/**
 * 笔记和清单的四个工具。**没有删除**:让模型删用户留着的东西,错一次就没了;删除只在界面上做。
 * 读是按需的([LIST]、[READ]),不往 system 段里塞——他有一百条笔记也不该每次都背着。
 */
object NotesTools {
    const val SAVE = "save_note"
    const val LIST = "list_notes"
    const val READ = "read_note"
    const val UPDATE = "update_note"

    val READ_TOOLS = setOf(LIST, READ)

    private fun obj(vararg entries: Pair<String, RuntimeJSONValue>) = RuntimeJSONValue.ObjectValue(mapOf(*entries))
    private fun str(value: String) = RuntimeJSONValue.StringValue(value)
    private fun stringProp(description: String) = obj("type" to str("string"), "description" to str(description))
    private fun stringList(description: String) = obj("type" to str("array"), "description" to str(description), "items" to obj("type" to str("string")))
    private fun schema(properties: Map<String, RuntimeJSONValue>, required: List<String> = emptyList()) = obj(
        "type" to str("object"),
        "properties" to RuntimeJSONValue.ObjectValue(properties),
        "required" to RuntimeJSONValue.ArrayValue(required.map { str(it) }),
        "additionalProperties" to RuntimeJSONValue.BoolValue(false),
    )

    fun registry(store: NoteStore): CapabilityRegistry = CapabilityRegistry(
        definitions = listOf(
            CapabilityDefinition(
                name = SAVE,
                description = "把用户要留着的一段文字或一张清单存成笔记（购物单、行李单、想法、草稿）。" +
                    "给 items 就是清单（逐条可勾），否则是一段文字（body）。只在用户要你记下这类内容时用；" +
                    "关于他这个人的长期事实（偏好、家人、习惯）不是笔记，用记忆。",
                inputSchema = schema(
                    mapOf(
                        "title" to stringProp("标题，短一点"),
                        "body" to stringProp("一段文字的内容，可选"),
                        "items" to stringList("清单的条目，可选；给了就存成清单"),
                    ),
                    required = listOf("title"),
                ),
            ),
            CapabilityDefinition(
                name = LIST,
                description = "列出笔记和清单（最近动过的在前），带短编号。给 query 就只列标题或内容里含它的。要读或改某一条之前先用它拿编号。",
                inputSchema = schema(mapOf("query" to stringProp("只看含这个词的，可选"))),
            ),
            CapabilityDefinition(
                name = READ,
                description = "读一条笔记或清单的全部内容。按 list_notes 给的短编号指到那一条。",
                inputSchema = schema(mapOf("id" to stringProp("短编号")), required = listOf("id")),
            ),
            CapabilityDefinition(
                name = UPDATE,
                description = "改一条笔记或清单：改标题、整段改写（body）、在末尾追加一段（append）；清单可以加条目、勾掉、取消勾、去掉条目（写条目原文的一部分即可）。" +
                    "按 list_notes 给的短编号指到那一条。",
                inputSchema = schema(
                    mapOf(
                        "id" to stringProp("短编号"),
                        "title" to stringProp("新标题，可选"),
                        "body" to stringProp("整段改写成这段文字，可选（会覆盖原文）"),
                        "append" to stringProp("在原文末尾追加的一段，可选"),
                        "add_items" to stringList("清单要加的条目"),
                        "check_items" to stringList("清单里要勾掉的条目"),
                        "uncheck_items" to stringList("清单里要取消勾选的条目"),
                        "remove_items" to stringList("清单里要去掉的条目"),
                    ),
                    required = listOf("id"),
                ),
            ),
        ),
    ) { invocation ->
        val input = runCatching { RuntimeJSONValue.decode(from = invocation.input) }.getOrNull()
        when (invocation.name) {
            SAVE -> save(store, input)
            LIST -> list(store, input)
            READ -> read(store, input)
            UPDATE -> update(store, input)
            else -> failure("不支持名为 ${invocation.name} 的工具。")
        }
    }

    private fun failure(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
        isError = true,
    )

    private fun success(message: String) = CapabilityExecutionResult(
        output = AgentToolOutput(kind = AgentToolOutput.Kind.TEXT, text = message),
    )

    private fun strings(input: RuntimeJSONValue?, key: String): List<String> =
        input?.get(key)?.arrayValue?.mapNotNull { it.stringValue?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

    private fun save(store: NoteStore, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val title = input?.get("title")?.stringValue?.trim().orEmpty()
        if (title.isEmpty()) return failure("save_note 需要标题 title。")
        if (title.length > Note.MAX_TITLE) return failure("标题太长了，短一点。")
        val items = strings(input, "items")
        val body = input?.get("body")?.stringValue?.trim().orEmpty()
        if (body.length > Note.MAX_BODY) return failure("内容太长了（最多 ${Note.MAX_BODY} 字）。")
        val note = if (items.isNotEmpty()) {
            if (items.size > Note.MAX_ITEMS) return failure("清单最多 ${Note.MAX_ITEMS} 条。")
            Note(kind = NoteKind.LIST, title = title, body = body, items = items.map { NoteItem(text = it.take(Note.MAX_ITEM_CHARS)) })
        } else {
            Note(kind = NoteKind.NOTE, title = title, body = body)
        }
        val saved = store.add(note) ?: return failure("笔记已经有 ${Note.MAX_NOTES} 条了，先让用户清理一些。")
        return success("已存成${if (saved.kind == NoteKind.LIST) "清单" else "笔记"}「$title」（编号 ${saved.handle}）。")
    }

    private fun list(store: NoteStore, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val query = input?.get("query")?.stringValue?.trim().orEmpty()
        val notes = store.all().filter { note ->
            query.isEmpty() || note.title.contains(query, ignoreCase = true) || note.body.contains(query, ignoreCase = true) ||
                note.items.any { it.text.contains(query, ignoreCase = true) }
        }
        if (notes.isEmpty()) return success(if (query.isEmpty()) "还没有笔记或清单。" else "没有含「$query」的笔记或清单。")
        val lines = notes.take(30).joinToString("\n") { note ->
            "- ${note.handle} · ${if (note.kind == NoteKind.LIST) "清单" else "笔记"} · ${note.title}" +
                note.preview.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        }
        val more = if (notes.size > 30) "\n（还有 ${notes.size - 30} 条没列出，用 query 缩小范围。）" else ""
        return success(lines + more)
    }

    private fun read(store: NoteStore, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val handle = input?.get("id")?.stringValue?.trim().orEmpty()
        val note = store.find(handle) ?: return failure("没有找到编号为 $handle 的笔记。先用 list_notes 拿编号。")
        return success(render(note))
    }

    fun render(note: Note): String = buildString {
        appendLine("${if (note.kind == NoteKind.LIST) "清单" else "笔记"}：${note.title}")
        if (note.body.isNotBlank()) appendLine(note.body)
        note.items.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.text}") }
    }.trimEnd()

    private fun update(store: NoteStore, input: RuntimeJSONValue?): CapabilityExecutionResult {
        val handle = input?.get("id")?.stringValue?.trim().orEmpty()
        val note = store.find(handle) ?: return failure("没有找到编号为 $handle 的笔记。先用 list_notes 拿编号。")
        val title = input?.get("title")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
        if (title != null && title.length > Note.MAX_TITLE) return failure("标题太长了，短一点。")
        val body = input?.get("body")?.stringValue
        val append = input?.get("append")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
        val add = strings(input, "add_items")
        val check = strings(input, "check_items")
        val uncheck = strings(input, "uncheck_items")
        val remove = strings(input, "remove_items")
        if ((add + check + uncheck + remove).isNotEmpty() && note.kind != NoteKind.LIST) {
            return failure("「${note.title}」是一段文字，不是清单；改条目只能用在清单上。")
        }

        val missed = mutableListOf<String>()
        var tooLong = false
        val updated = store.update(note.id) { current ->
            var items = current.items + add.map { NoteItem(text = it.take(Note.MAX_ITEM_CHARS)) }
            fun change(keys: List<String>, block: (List<NoteItem>, Int) -> List<NoteItem>) {
                for (key in keys) {
                    val index = items.indexOfFirst { it.text.contains(key) || it.id.startsWith(key) }
                    if (index < 0) missed += key else items = block(items, index)
                }
            }
            change(check) { list, i -> list.mapIndexed { n, item -> if (n == i) item.copy(done = true) else item } }
            change(uncheck) { list, i -> list.mapIndexed { n, item -> if (n == i) item.copy(done = false) else item } }
            change(remove) { list, i -> list.filterIndexed { n, _ -> n != i } }
            var nextBody = body?.trim() ?: current.body
            if (append != null) nextBody = if (nextBody.isBlank()) append else "$nextBody\n$append"
            if (nextBody.length > Note.MAX_BODY || items.size > Note.MAX_ITEMS) tooLong = true
            if (tooLong) current else current.copy(title = title ?: current.title, body = nextBody, items = items)
        }!!
        if (tooLong) return failure("这样改会超过长度上限（内容 ${Note.MAX_BODY} 字、清单 ${Note.MAX_ITEMS} 条），没有改。")
        val tail = if (missed.isEmpty()) "" else "（没找到这几条：${missed.joinToString("、")}）"
        return success("已更新「${updated.title}」$tail")
    }
}
