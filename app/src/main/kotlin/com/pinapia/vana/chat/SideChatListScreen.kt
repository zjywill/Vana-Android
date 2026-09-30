package com.pinapia.vana.chat

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinapia.vana.tenant.Tenant
import com.pinapia.vana.thread.SideChat
import com.pinapia.vana.thread.SideChatStore
import com.pinapia.vana.ui.L10n
import com.pinapia.vana.ui.icons.VanaIcons
import com.pinapia.vana.ui.uiText
import kotlinx.coroutines.launch

/** 侧聊那几句要在两处说得一模一样的话。 */
internal object SideChatCopy {
    val note: String
        get() = L10n.text(
            "这里说的不挤主对话，Vana 记得的事两边都用得上。在这里建的提醒，到点出现在主对话里。",
            "What you say here doesn't crowd the main conversation, and what Vana remembers works in both. " +
                "Reminders you set here arrive in the main conversation.",
        )

    val deleteMessage: String
        get() = L10n.text(
            "这条侧聊里的消息和它们带的照片都会从本机删除，无法撤销。Vana 已经记住的事不受影响。",
            "The messages in this side chat and their photos will be deleted from this device. This cannot be undone. " +
                "What Vana already remembers is not affected.",
        )
}

/**
 * 「⋯ › 侧聊」:主对话旁边,他自己单独拿出来聊的那几件事。
 *
 * **只有他能开**:模型不开侧聊,也不建议「挪过去」。按最近说过话排。点开是另一页、另一个聊天
 * view model,返回回到这里。
 *
 * 这一页不在首屏常驻,入口只在「⋯」里:主对话永远是家。一上来就摆一张列表、再给置顶和文件夹,
 * 等于把会话列表请回来了。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SideChatListScreen(
    store: SideChatStore,
    tenant: Tenant,
    onOpen: (SideChat) -> Unit,
    onBack: () -> Unit,
) {
    val revision by store.revision.collectAsStateWithLifecycle()
    var chats by remember { mutableStateOf<List<SideChat>?>(null) }
    LaunchedEffect(revision) { chats = store.all() }
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SideChat?>(null) }
    var deleting by remember { mutableStateOf<SideChat?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(uiText("侧聊", "Side chats"))
                        // 当前是谁要一直挂在视线里,但只标家人(同记忆页、用药表)。
                        if (!tenant.isOwner) {
                            Text(
                                tenant.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(VanaIcons.ArrowLeft, contentDescription = uiText("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = { naming = true }) {
                        Icon(VanaIcons.PencilSquare, contentDescription = uiText("新侧聊", "New side chat"))
                    }
                },
            )
        },
    ) { insets ->
        val list = chats
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> EmptySideChats(modifier = Modifier.padding(insets), onNew = { naming = true })
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(list, key = { it.id }) { chat ->
                    SideChatRow(
                        chat = chat,
                        onOpen = { onOpen(chat) },
                        onRename = { renaming = chat },
                        onDelete = { deleting = chat },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                item {
                    Text(
                        SideChatCopy.note,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (naming) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(uiText("新侧聊", "New side chat")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        uiText(
                            "给它起个名字。也可以留空，第一句话会拿来当名字。",
                            "Give it a name, or leave it blank and the first thing you say becomes the name.",
                        ),
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        placeholder = { Text(uiText("比如：十月去京都", "e.g. Kyoto in October")) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    scope.launch { onOpen(store.create(title)) }
                }) { Text(uiText("开始", "Start")) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }

    renaming?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(uiText("改名", "Rename")) },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    placeholder = { Text(chat.displayTitle) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch { store.rename(chat.id, title) }
                }) { Text(uiText("好", "OK")) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }

    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(uiText("删除「${chat.displayTitle}」？", "Delete \"${chat.displayTitle}\"?")) },
            text = { Text(SideChatCopy.deleteMessage) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch { store.delete(chat.id) }
                }) { Text(uiText("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(uiText("取消", "Cancel")) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SideChatRow(
    chat: SideChat,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                chat.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                lastActive(chat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 长按之外再给一个看得见的「⋯」:长按在 Android 上不是人人都会去试。
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(VanaIcons.EllipsisVertical, contentDescription = uiText("更多", "More"))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(uiText("改名", "Rename")) },
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(uiText("删除", "Delete"), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 「刚刚」「5 分钟前」「昨天」。不到一分钟时系统会说「0 分钟前」,那句话读起来像出了错。 */
@Composable
private fun lastActive(chat: SideChat): String {
    val at = chat.lastActiveAt.toEpochMilliseconds()
    val now = System.currentTimeMillis()
    if (now - at < DateUtils.MINUTE_IN_MILLIS) return uiText("刚刚", "Just now")
    return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun EmptySideChats(modifier: Modifier = Modifier, onNew: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(uiText("还没有侧聊", "No side chats yet"), style = MaterialTheme.typography.titleMedium)
        Text(
            uiText(
                "想把一件事单独拿出来聊，比如一趟行程、一次比价、一份要细看的材料，就开一条侧聊。" +
                    "那里说的不挤主对话，Vana 记得的事两边都用得上。",
                "To talk through one thing on its own, like a trip, a comparison or a document to go over, open a side chat. " +
                    "It doesn't crowd the main conversation, and what Vana remembers works in both.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNew) { Text(uiText("新侧聊", "New side chat")) }
    }
}
