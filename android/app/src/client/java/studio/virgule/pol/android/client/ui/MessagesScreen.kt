package studio.virgule.pol.android.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.android.ui.SegmentCard
import studio.virgule.pol.android.ui.SimBadge
import studio.virgule.pol.core.client.ClientModel
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.Conversations
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.proto.Validate
import kotlinx.coroutines.launch

@Composable
private fun noLine() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
)

/** Conversation list in the style of Google Messages: plain rows, unread counts, and a Start chat button. */
@Composable
fun MessagesScreen(snap: ClientSnapshot, openThread: (Long, String) -> Unit, newChat: () -> Unit, openGateway: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val list = remember(snap.messages, snap.contacts, query) { Conversations.search(snap.messages.values, query) { snap.nameFor(it) } }

    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    TextField(query, { query = it }, Modifier.weight(1f), singleLine = true, colors = noLine(),
                        placeholder = { Text(stringResource(R.string.search_messages)) })
                } else {
                    Text(stringResource(R.string.tab_messages), style = MaterialTheme.typography.titleLarge, fontSize = 24.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
                    Surface(onClick = openGateway, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhoneAndroid, stringResource(R.string.gateway_open), Modifier.size(22.dp)) }
                    }
                }
            }
            when {
                snap.status?.caps?.smsRead == false -> EmptyState(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.messages_off_title), stringResource(R.string.messages_off_body))
                list.isEmpty() && query.isNotBlank() -> EmptyState(Icons.Outlined.Search, stringResource(R.string.no_results), stringResource(R.string.no_results_body, query))
                list.isEmpty() -> EmptyState(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.messages_empty_title), stringResource(R.string.messages_empty_body))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(list, key = { it.thread }) { c ->
                        val name = snap.nameFor(c.address)
                        val unread = c.unread > 0
                        Row(Modifier.fillMaxWidth().clickable { openThread(c.thread, c.address) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name ?: "#", size = 48)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(name ?: Format.ltr(c.address), style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                Text((if (c.last.dir == Direction.OUT) stringResource(R.string.you_prefix) else "") + Format.auto(c.last.body), maxLines = 2,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Format.relative(c.last.ts), style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
                                if (unread) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp)) {
                                    Text(Format.digits(c.unread.coerceAtMost(99).toString()), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (snap.status?.caps?.smsSend != false) ExtendedFloatingActionButton(
            onClick = newChat, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, text = { Text(stringResource(R.string.start_chat)) })
    }
}

/** New chat: "To" and "With" (the SIM) in a card, then contacts grouped by letter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatScreen(snap: ClientSnapshot, onBack: () -> Unit, openThread: (Long, String) -> Unit) {
    var to by remember { mutableStateOf("") }
    fun open(n: String) {
        val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(n) }?.thread ?: 0L
        openThread(t, n)
    }
    val q = to.trim()
    val digits = q.filter(Char::isDigit)
    val matches = snap.contacts.filter { c -> q.isEmpty() || c.name.contains(q, true) || (digits.isNotEmpty() && c.phones.any { it.number.filter(Char::isDigit).contains(digits) }) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.new_chat)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().imePadding()) {
            item {
                Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.to_label), style = MaterialTheme.typography.bodyMedium)
                            TextField(to, { to = it }, Modifier.weight(1f), singleLine = true, colors = noLine(),
                                placeholder = { Text(stringResource(R.string.to_hint)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                        }
                        val sims = snap.status?.sims.orEmpty()
                        if (sims.size > 1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                            Text(stringResource(R.string.with_label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                            val sim = sims.firstOrNull { it.subId == defaultSmsSub(snap) } ?: sims.first()
                            FilterChip(selected = false, onClick = {}, leadingIcon = { SimBadge(sim.slot) }, label = { Text(sim.label.ifBlank { "SIM ${sim.slot + 1}" }) })
                        }
                    }
                }
            }
            val n = Validate.smsRecipient(q)
            if (n != null && matches.isEmpty()) item {
                SegmentCard(0, 1, onClick = { open(n) }) { ContactCallRow(Format.ltr(n), stringResource(R.string.send_a_message), onCall = null, avatarName = "#") }
            }
            val groups = matches.groupBy { it.name.take(1).uppercase() }.toSortedMap()
            for ((letter, group) in groups) {
                item(key = "h:$letter") { GroupLabel(letter) }
                val rows = group.flatMap { c -> c.phones.map { c to it } }
                itemsIndexed(rows, key = { _, r -> "${r.first.id}:${r.second.number}" }) { i, (c, p) ->
                    SegmentCard(i, rows.size, onClick = { open(p.number) }) { ContactCallRow(c.name, phoneDetail(p), onCall = null) }
                }
            }
        }
    }
}

/** A conversation: grouped bubbles on a white sheet and the compose bar, as in Google Messages. */
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
    val lastOut = messages.lastOrNull { it.dir == Direction.OUT }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name ?: "#", size = 40)
                        Text(name ?: Format.ltr(address), maxLines = 1, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                },
                actions = {
                    if (Validate.dialNumber(address) != null) IconButton(onClick = {
                        resultText(ctx, repo.dial(address, defaultVoiceSub(snap)))?.let { scope.launch { snackbar.showSnackbar(it) } }
                    }) { Icon(Icons.Outlined.Call, stringResource(R.string.call)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Surface(Modifier.padding(pad).fillMaxSize(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                    itemsIndexed(messages, key = { _, m -> if (m.id > 0) "m${m.id}" else "p${m.cmid}" }) { i, m ->
                        val prev = messages.getOrNull(i - 1)
                        val next = messages.getOrNull(i + 1)
                        val newGroup = prev == null || m.ts - prev.ts > 3_600_000
                        val first = newGroup || prev?.dir != m.dir
                        val last = next == null || next.dir != m.dir || next.ts - m.ts > 3_600_000
                        if (newGroup) Text(Format.dateTime(m.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        val out = m.dir == Direction.OUT
                        val big = 20.dp; val small = 4.dp
                        val shape = if (out) RoundedCornerShape(topStart = big, bottomStart = big, topEnd = if (first) big else small, bottomEnd = if (last) big else small)
                        else RoundedCornerShape(topEnd = big, bottomEnd = big, topStart = if (first) big else small, bottomStart = if (last) big else small)
                        Row(Modifier.fillMaxWidth().padding(top = if (first) 6.dp else 2.dp), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
                            Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
                                Surface(
                                    shape = shape,
                                    color = if (out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.widthIn(max = 296.dp).clickable(onClickLabel = stringResource(R.string.copy)) { clipboard.setText(AnnotatedString(m.body)) },
                                ) {
                                    Text(m.body, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                                }
                                val failed = m.state == DeliveryState.FAILED
                                if (failed || m === lastOut) {
                                    val status = when (m.state) {
                                        DeliveryState.PENDING -> stringResource(R.string.sending)
                                        DeliveryState.SENT -> stringResource(R.string.sent)
                                        DeliveryState.DELIVERED -> stringResource(R.string.delivered)
                                        DeliveryState.FAILED -> stringResource(R.string.not_sent)
                                        DeliveryState.RECEIVED -> ""
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        if (failed) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                        Text(listOfNotNull(Format.time(m.ts), status.ifEmpty { null }, simLabel(snap, m.subId)).joinToString(" • "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (failed && m.cmid != null) TextButton(onClick = {
                                            resultText(ctx, repo.model.retry(m.cmid!!))?.let { scope.launch { snackbar.showSnackbar(it) } }
                                        }) { Text(stringResource(R.string.retry)) }
                                    }
                                }
                            }
                        }
                    }
                }
                val canSend = snap.status?.caps?.smsSend != false && Validate.smsRecipient(address) != null
                val sims = snap.status?.sims.orEmpty()
                val several = sims.size > 1 && snap.status?.caps?.simChoice == true
                var simMenu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { simMenu = true }, enabled = several) { Icon(Icons.Outlined.AddCircleOutline, stringResource(R.string.choose_sim)) }
                                DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }) {
                                    for (s in sims) DropdownMenuItem(text = { Text(s.label.ifBlank { "SIM ${s.slot + 1}" }) }, leadingIcon = { SimBadge(s.slot) },
                                        onClick = { sub = s.subId; simMenu = false })
                                }
                            }
                            val line = simLabel(snap, sub)
                            TextField(text, { if (it.length <= Validate.MAX_SMS_CHARS) text = it }, Modifier.weight(1f), enabled = canSend, colors = noLine(), maxLines = 5,
                                placeholder = {
                                    Text(if (!canSend) stringResource(R.string.cannot_reply)
                                        else if (line != null) stringResource(R.string.sms_placeholder, line) else stringResource(R.string.text_message), maxLines = 1)
                                })
                        }
                    }
                    FilledIconButton(enabled = canSend && Validate.smsBody(text) != null, onClick = {
                        val r = repo.model.sendSms(address, text, sub)
                        if (r == ClientModel.ActionResult.Ok || r == ClientModel.ActionResult.NotConnected) text = ""
                        resultText(ctx, r)?.let { scope.launch { snackbar.showSnackbar(it) } }
                    }, modifier = Modifier.padding(start = 8.dp).size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                    }
                }
                if (text.length > 140) Text(Format.digits("${text.length}/${Validate.MAX_SMS_CHARS}"), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 28.dp, bottom = 8.dp))
            }
        }
    }
}
