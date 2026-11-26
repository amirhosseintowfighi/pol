package studio.virgule.pol.android.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.Conversations
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.proto.Validate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(repo: ClientRepository, snap: ClientSnapshot, openThread: (Long, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var compose by remember { mutableStateOf(false) }
    val list = remember(snap.messages, snap.contacts, query) { Conversations.search(snap.messages.values, query) { snap.nameFor(it) } }

    Box(Modifier.fillMaxSize()) {
        Column {
            DockedSearchBar(
                inputField = {
                    SearchBarDefaults.InputField(query = query, onQueryChange = { query = it }, onSearch = {}, expanded = false, onExpandedChange = {},
                        placeholder = { Text(stringResource(R.string.search_messages)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                },
                expanded = false, onExpandedChange = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {}
            when {
                snap.status?.caps?.smsRead == false -> EmptyState(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.messages_off_title), stringResource(R.string.messages_off_body))
                list.isEmpty() && query.isNotBlank() -> EmptyState(Icons.Outlined.Search, stringResource(R.string.no_results), stringResource(R.string.no_results_body, query))
                list.isEmpty() -> EmptyState(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty_body))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.thread }) { c ->
                        val name = snap.nameFor(c.address)
                        val bold = c.unread > 0
                        ListItem(
                            modifier = Modifier.clickable { openThread(c.thread, c.address) },
                            leadingContent = { Avatar(name ?: "#") },
                            headlineContent = { Text(name ?: Format.ltr(c.address), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1) },
                            supportingContent = {
                                Text((if (c.last.dir == Direction.OUT) stringResource(R.string.you_prefix) else "") + c.last.body, maxLines = 2,
                                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            trailingContent = { Text(Format.relative(c.last.ts), style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
        if (snap.status?.caps?.smsSend != false) ExtendedFloatingActionButton(
            onClick = { compose = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            icon = { Icon(Icons.Outlined.Edit, null) }, text = { Text(stringResource(R.string.start_chat)) })
    }

    if (compose) {
        var to by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { compose = false },
            title = { Text(stringResource(R.string.new_message)) },
            text = {
                Column {
                    OutlinedTextField(to, { to = it }, label = { Text(stringResource(R.string.to_number)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        isError = to.isNotBlank() && Validate.smsRecipient(to) == null)
                    val matches = snap.contacts.filter { to.length >= 2 && it.name.contains(to, ignoreCase = true) }.take(4)
                    for (m in matches) for (p in m.phones.take(2)) TextButton(onClick = { to = p.number }) { Text("${m.name} · ${Format.ltr(p.number)}") }
                }
            },
            confirmButton = {
                TextButton(enabled = Validate.smsRecipient(to) != null, onClick = {
                    val n = Validate.smsRecipient(to)!!
                    val thread = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(n) }?.thread ?: 0L
                    compose = false; openThread(thread, n)
                }) { Text(stringResource(R.string.next)) }
            },
            dismissButton = { TextButton(onClick = { compose = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(repo: ClientRepository, snap: ClientSnapshot, thread: Long, address: String, onBack: () -> Unit, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // A new conversation has no thread id yet: follow it by number until the gateway assigns one.
    val messages = snap.messages.values.filter { if (thread > 0) it.thread == thread || (it.id == 0L && numberKey(it.address) == numberKey(address)) else numberKey(it.address) == numberKey(address) }
        .sortedBy { it.ts }
    val name = snap.nameFor(address)
    var text by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf(messages.lastOrNull()?.subId ?: defaultSmsSub(snap)) }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(thread, messages.count { !it.read }) { if (thread > 0) repo.model.markRead(thread) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Column { Text(name ?: Format.ltr(address), maxLines = 1); if (name != null) Text(Format.ltr(address), style = MaterialTheme.typography.labelMedium) } },
                actions = {
                    if (Validate.dialNumber(address) != null) IconButton(onClick = {
                        resultText(ctx, repo.dial(address, defaultVoiceSub(snap)))?.let { scope.launch { snackbar.showSnackbar(it) } }
                    }) { Icon(Icons.Outlined.Call, stringResource(R.string.call)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(messages, key = { if (it.id > 0) "m${it.id}" else "p${it.cmid}" }) { m ->
                    val out = m.dir == Direction.OUT
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
                        Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.widthIn(max = 300.dp).clickable(onClickLabel = stringResource(R.string.copy)) {
                                    clipboard.setText(AnnotatedString(m.body))
                                },
                            ) { Text(m.body, Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) }
                            val status = when (m.state) {
                                DeliveryState.PENDING -> stringResource(R.string.sending)
                                DeliveryState.SENT -> stringResource(R.string.sent)
                                DeliveryState.DELIVERED -> stringResource(R.string.delivered)
                                DeliveryState.FAILED -> stringResource(R.string.not_sent)
                                DeliveryState.RECEIVED -> ""
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (m.state == DeliveryState.FAILED) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                Text(listOfNotNull(Format.time(m.ts), status.ifEmpty { null }, simLabel(snap, m.subId)).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall, color = if (m.state == DeliveryState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                if (m.state == DeliveryState.FAILED && m.cmid != null) TextButton(onClick = {
                                    resultText(ctx, repo.model.retry(m.cmid!!))?.let { scope.launch { snackbar.showSnackbar(it) } }
                                }) { Text(stringResource(R.string.retry)) }
                            }
                        }
                    }
                }
            }
            SimPicker(snap, sub, { sub = it }, Modifier.padding(top = 4.dp))
            val canSend = snap.status?.caps?.smsSend != false && Validate.smsRecipient(address) != null
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { if (it.length <= Validate.MAX_SMS_CHARS) text = it }, Modifier.weight(1f), enabled = canSend,
                    placeholder = { Text(stringResource(if (canSend) R.string.text_message else R.string.cannot_reply)) },
                    shape = RoundedCornerShape(28.dp), maxLines = 5,
                    supportingText = { if (text.length > 140) Text(Format.digits("${text.length}/${Validate.MAX_SMS_CHARS}")) })
                FilledIconButton(enabled = canSend && Validate.smsBody(text) != null, onClick = {
                    val r = repo.model.sendSms(address, text, sub)
                    if (r == studio.virgule.pol.core.client.ClientModel.ActionResult.Ok || r == studio.virgule.pol.core.client.ClientModel.ActionResult.NotConnected) text = ""
                    resultText(ctx, r)?.let { scope.launch { snackbar.showSnackbar(it) } }
                }, modifier = Modifier.padding(start = 8.dp).size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send)) }
            }
        }
    }
}
