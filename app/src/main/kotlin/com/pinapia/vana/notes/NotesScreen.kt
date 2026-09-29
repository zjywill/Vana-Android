package com.pinapia.vana.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText

/** 笔记与清单。列表和编辑器在同一个页面里切换,编辑器的返回键回到列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    store: NoteStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val revision by store.revision.collectAsState()
    val notes = remember(revision) { store.all() }
    var editing by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }

    val open = editing?.let { id -> notes.firstOrNull { it.id == id } }
    if (open != null) {
        NoteEditor(note = open, store = store, onClose = { editing = null }, modifier = modifier)
        return
    }

    fun create(kind: NoteKind) {
        val created = store.add(Note(kind = kind, title = if (kind == NoteKind.LIST) "新清单" else "新笔记"))
        if (created != null) editing = created.id
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiText("笔记与清单", "Notes and lists")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back")) }
                },
                actions = {
                    Column {
                        IconButton(onClick = { addMenu = true }) { Icon(VanaIcons.Plus, contentDescription = uiText("添加", "Add")) }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text(uiText("新笔记", "New note")) }, onClick = { addMenu = false; create(NoteKind.NOTE) })
                            DropdownMenuItem(text = { Text(uiText("新清单", "New list")) }, onClick = { addMenu = false; create(NoteKind.LIST) })
                        }
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (notes.isEmpty()) {
                item {
                    Text(uiText("还没有笔记", "No notes yet"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        uiText(
                            "在对话里说「帮我记一个购物清单：牛奶、鸡蛋」，Vana 会存在这里；也可以点右上角的加号自己写。这些内容只在需要时才会被读进对话。",
                            "Say “make me a shopping list: milk, eggs” in the chat and Vana saves it here, or tap + to write one yourself. A note is only read into a conversation when it's needed.",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(notes, key = { it.id }) { note ->
                Column(modifier = Modifier.fillMaxWidth().clickable { editing = note.id }.padding(vertical = 10.dp)) {
                    Text(note.title, style = MaterialTheme.typography.bodyLarge)
                    val kind = if (note.kind == NoteKind.LIST) uiText("清单", "List") else uiText("笔记", "Note")
                    Text(
                        listOf(kind, note.preview).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditor(note: Note, store: NoteStore, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    val items = remember(note.id) { note.items.toMutableStateList() }
    var newItem by remember(note.id) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        store.update(note.id) {
            it.copy(
                title = title.trim().ifEmpty { it.title }.take(Note.MAX_TITLE),
                body = body.take(Note.MAX_BODY),
                items = items.filter { item -> item.text.isNotBlank() }.take(Note.MAX_ITEMS),
            )
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (note.kind == NoteKind.LIST) uiText("清单", "List") else uiText("笔记", "Note")) },
                navigationIcon = {
                    IconButton(onClick = { save(); onClose() }) { Icon(VanaIcons.ArrowLeft, contentDescription = uiText("保存并返回", "Save and go back")) }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(VanaIcons.Trash, contentDescription = uiText("删除", "Delete")) }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(Note.MAX_TITLE) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text(uiText("标题", "Title")) },
            )
            if (note.kind == NoteKind.NOTE) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(Note.MAX_BODY) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    label = { Text(uiText("内容", "Content")) },
                )
            } else {
                items.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = item.done, onCheckedChange = { items[index] = item.copy(done = it) })
                        OutlinedTextField(
                            value = item.text,
                            onValueChange = { items[index] = item.copy(text = it.take(Note.MAX_ITEM_CHARS)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        IconButton(onClick = { items.removeAt(index) }) { Icon(VanaIcons.XMark, contentDescription = uiText("去掉", "Remove")) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newItem,
                        onValueChange = { newItem = it.take(Note.MAX_ITEM_CHARS) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(uiText("加一条", "Add an item")) },
                    )
                    TextButton(
                        enabled = newItem.isNotBlank() && items.size < Note.MAX_ITEMS,
                        onClick = { items.add(NoteItem(text = newItem.trim())); newItem = "" },
                    ) { Text(uiText("添加", "Add")) }
                }
            }
            Text(
                uiText(
                    "返回时自动保存。Vana 只在你让它看这一条时才会读到它，读到的内容会随那次提问发给你选的模型服务。",
                    "Saved when you go back. Vana only reads this note when you ask it to, and what it reads is sent with that question to your chosen model service.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(uiText("删除这一条？", "Delete this item?")) },
            text = { Text(uiText("删除后无法撤销。", "This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; store.delete(note.id); onClose() }) { Text(uiText("删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(uiText("取消", "Cancel")) } },
        )
    }
}
