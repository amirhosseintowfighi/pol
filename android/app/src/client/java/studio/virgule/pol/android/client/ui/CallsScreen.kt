package studio.virgule.pol.android.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SwapCalls
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.android.ui.SegmentCard
import studio.virgule.pol.android.ui.SimBadge
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.CallType
import studio.virgule.pol.core.domain.Contact
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.Validate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The green of the Call button in Google's Phone app, and the red of its End button. */
private val CallGreen = Color(0xFF1E8E3E)
private val EndRed = Color(0xFFD93025)

/** Section label above a group of cards ("Today", "Suggested"). */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp))
}

/** Opens the line chooser first when the gateway has several SIMs, as the system dialer does. */
@Composable
fun rememberDialer(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState): (String, Int?) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    fun place(n: String, sub: Int?) {
        resultText(ctx, repo.dial(n, sub ?: defaultVoiceSub(snap)))?.let { scope.launch { snackbar.showSnackbar(it) } }
    }
    pending?.let { n ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.choose_sim_call)) },
            text = {
                Column {
                    for (sim in snap.status?.sims.orEmpty()) {
                        ListItem(
                            modifier = Modifier.clickable { pending = null; place(n, sim.subId) },
                            leadingContent = { SimBadge(sim.slot) },
                            headlineContent = { Text(sim.label.ifBlank { "SIM ${sim.slot + 1}" }) },
                            supportingContent = { if (sim.carrier.isNotBlank()) Text(sim.carrier) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    return { number, sub ->
        val several = (snap.status?.sims?.size ?: 0) > 1 && snap.status?.caps?.simChoice == true
        if (sub == null && several) pending = number else place(number, sub)
    }
}

// MARK: Home

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState, openThread: (Long, String) -> Unit,
               openContacts: () -> Unit, openSearch: () -> Unit, openGateway: () -> Unit) {
    val dial = rememberDialer(repo, snap, snackbar)
    var missedOnly by remember { mutableStateOf(false) }
    var favouritesOpen by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<CallEntry?>(null) }
    val favourites = snap.contacts.filter { it.starred }
    val calls = if (missedOnly) snap.calls.filter { it.type == CallType.MISSED || it.type == CallType.REJECTED } else snap.calls

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            // Search pill: the menu button opens the gateway and settings.
            Surface(onClick = openSearch, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(56.dp),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = openGateway, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.Outlined.Menu, stringResource(R.string.gateway_open)) }
                    Text(stringResource(R.string.search_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = !missedOnly, onClick = { missedOnly = false }, label = { Text(stringResource(R.string.filter_all)) }) }
                item { FilterChip(selected = missedOnly, onClick = { missedOnly = true }, label = { Text(stringResource(R.string.filter_missed)) }) }
                item { FilterChip(selected = false, onClick = openContacts, label = { Text(stringResource(R.string.filter_contacts)) },
                    colors = FilterChipDefaults.filterChipColors()) }
            }
        }
        if (favourites.isNotEmpty() && !missedOnly) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { favouritesOpen = !favouritesOpen }) {
                        Text(stringResource(R.string.favourites), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Outlined.KeyboardArrowUp, null, Modifier.size(18.dp).rotate(if (favouritesOpen) 0f else 180f))
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = openContacts, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text(stringResource(R.string.view_contacts), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (favouritesOpen) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(favourites, key = { it.id }) { c ->
                        Column(Modifier.width(80.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = stringResource(R.string.call_x, c.name)) {
                            c.phones.firstOrNull()?.let { dial(it.number, null) }
                        }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Avatar(c.name, size = 56)
                            Text(c.name.substringBefore(' '), style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        if (calls.isEmpty()) {
            item {
                if (snap.status?.caps?.callLog == false) EmptyState(Icons.Outlined.History, stringResource(R.string.calls_off_title), stringResource(R.string.calls_off_body), Modifier.height(360.dp))
                else EmptyState(Icons.Outlined.History, stringResource(R.string.calls_empty_title), stringResource(R.string.calls_empty_body), Modifier.height(360.dp))
            }
        }
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val startOfToday = now - (now + java.util.TimeZone.getDefault().getOffset(now)) % day
        val groups = calls.groupBy { when { it.ts >= startOfToday -> 0; it.ts >= startOfToday - day -> 1; else -> 2 } }.toSortedMap()
        for ((g, list) in groups) {
            item(key = "g$g") { GroupLabel(stringResource(when (g) { 0 -> R.string.today; 1 -> R.string.yesterday; else -> R.string.older })) }
            itemsIndexed(list, key = { _, e -> e.id }) { i, e ->
                SegmentCard(i, list.size, onClick = { selected = e }) { CallRow(snap, e) { dial(e.number, null) } }
            }
        }
    }

    selected?.let { e ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            val name = e.name ?: snap.nameFor(e.number)
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name ?: "#", size = 72)
                Text(name ?: Format.ltr(e.number), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
                if (name != null) Text(Format.ltr(e.number), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.dateTime(e.ts) + if (e.durationS > 0) " · " + Format.duration(e.durationS) else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(enabled = Validate.dialNumber(e.number) != null, onClick = { selected = null; dial(e.number, e.subId) },
                        modifier = Modifier.weight(1f).height(56.dp)) { Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.call)) }
                    FilledTonalButton(enabled = Validate.smsRecipient(e.number) != null, onClick = {
                        val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(e.number) }?.thread ?: 0L
                        selected = null; openThread(t, e.number)
                    }, modifier = Modifier.weight(1f).height(56.dp)) { Icon(Icons.AutoMirrored.Outlined.Message, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.message)) }
                }
            }
        }
    }
}

/** One recent call: picture, name, direction and time, the line in its colour, and a call button. */
@Composable
private fun CallRow(snap: ClientSnapshot, e: CallEntry, onCall: () -> Unit) {
    val name = e.name ?: snap.nameFor(e.number)
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    val typeLabel = stringResource(when (e.type) {
        CallType.INCOMING -> R.string.call_incoming; CallType.OUTGOING -> R.string.call_outgoing
        CallType.MISSED -> R.string.call_missed; CallType.REJECTED -> R.string.call_declined
        CallType.BLOCKED -> R.string.call_blocked; CallType.OTHER -> R.string.call_other
    })
    val accent = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name ?: "#", size = 40)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name ?: Format.ltr(e.number.ifBlank { stringResource(R.string.unknown_number) }), style = MaterialTheme.typography.titleMedium,
                fontWeight = if (missed) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when (e.type) {
                    CallType.OUTGOING -> Icons.AutoMirrored.Outlined.CallMade
                    CallType.MISSED, CallType.REJECTED -> Icons.AutoMirrored.Outlined.CallMissed
                    CallType.BLOCKED -> Icons.Outlined.Block
                    else -> Icons.AutoMirrored.Outlined.CallReceived
                }, contentDescription = null, modifier = Modifier.size(15.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text("$typeLabel • ${Format.relative(e.ts)}", style = MaterialTheme.typography.bodyMedium, color = accent, maxLines = 1)
            }
            simLabel(snap, e.subId)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
        IconButton(onClick = onCall, enabled = Validate.dialNumber(e.number) != null) {
            Icon(Icons.Outlined.Call, stringResource(R.string.call_x, name ?: e.number))
        }
    }
}

/** A contact (or number) card with a call button, as in the Phone app's suggestions and search. */
@Composable
fun ContactCallRow(name: String, detail: String?, onCall: (() -> Unit)?, avatarName: String = name) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(avatarName, size = 40)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (onCall != null) IconButton(onClick = onCall) { Icon(Icons.Outlined.Call, stringResource(R.string.call_x, name)) }
    }
}

fun phoneDetail(p: studio.virgule.pol.core.domain.PhoneNumber) = listOfNotNull(p.label, Format.ltr(p.number)).joinToString(" ")

// MARK: Keypad

@Composable
fun KeypadScreen(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState, openThread: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val dial = rememberDialer(repo, snap, snackbar)
    var number by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    val isCode = number.startsWith("*") || number.startsWith("#")
    val digits = number.filter(Char::isDigit)

    Column(Modifier.fillMaxSize()) {
        val matches: List<Pair<Contact, studio.virgule.pol.core.domain.PhoneNumber>> = if (digits.length >= 2)
            snap.contacts.flatMap { c -> c.phones.filter { it.number.filter(Char::isDigit).contains(digits) }.map { c to it } }.take(8)
        else if (number.isEmpty()) (snap.contacts.filter { it.starred } + snap.contacts).distinctBy { it.id }.take(6).mapNotNull { c -> c.phones.firstOrNull()?.let { c to it } }
        else emptyList()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (matches.isNotEmpty()) {
                item { GroupLabel(stringResource(R.string.suggested)) }
                itemsIndexed(matches, key = { _, m -> "${m.first.id}:${m.second.number}" }) { i, (c, p) ->
                    SegmentCard(i, matches.size, onClick = { number = p.number }) {
                        ContactCallRow(c.name, phoneDetail(p), onCall = { dial(p.number, null) })
                    }
                }
            } else if (!isCode && Validate.dialNumber(number) != null) {
                item { GroupLabel(stringResource(R.string.not_in_contacts)) }
                item { SegmentCard(0, 1) { ContactCallRow(Format.ltr(number), null, onCall = { dial(number, null) }, avatarName = "#") } }
                if (Validate.smsRecipient(number) != null) item {
                    ListItem(
                        modifier = Modifier.padding(horizontal = 12.dp).clickable {
                            val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(number) }?.thread ?: 0L
                            openThread(t, number)
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Outlined.Message, null, tint = MaterialTheme.colorScheme.primary) },
                        headlineContent = { Text(stringResource(R.string.send_a_message), color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            if (isCode) item {
                Text(stringResource(if (snap.status?.caps?.ussd == true) R.string.code_hint else R.string.code_unavailable),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp), textAlign = TextAlign.Center)
            }
            snap.ussd?.let { u ->
                item {
                    Surface(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                        Text(when { u.pending -> stringResource(R.string.code_waiting); u.ok -> u.text.orEmpty(); else -> describe(ctx, u.err ?: "failed") },
                            Modifier.padding(16.dp))
                    }
                }
            }
        }
        // The dial pad panel, in its standard left-to-right layout in every language.
        Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(48.dp))
                        Text(number, fontSize = 30.sp, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { number = number.dropLast(1) }, enabled = number.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, stringResource(R.string.delete_digit))
                        }
                    }
                    val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO",
                        "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
                    for (row in keys.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((d, letters) in row) {
                            Surface(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (number.length < 32) number += d
                            }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceBright,
                                modifier = Modifier.weight(1f).height(60.dp).padding(vertical = 3.dp).semantics { contentDescription = d }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(d, fontSize = 26.sp, lineHeight = 28.sp)
                                    if (letters.isNotEmpty()) Text(letters, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val enabled = if (isCode) Validate.ussdCode(number) != null && snap.status?.caps?.ussd == true else Validate.dialNumber(number) != null
                    Surface(onClick = {
                        if (isCode) resultText(ctx, repo.model.ussd(number, defaultVoiceSub(snap)))?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
                        else dial(number, null)
                    }, enabled = enabled, shape = RoundedCornerShape(50), color = CallGreen.copy(alpha = if (enabled) 1f else 0.5f),
                        contentColor = Color.White, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp).height(56.dp).width(136.dp)) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Call, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (isCode) R.string.send_code else R.string.call), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

// MARK: In call

/** Shown while the gateway has a ringing or active call, laid out like Google's Phone app. */
@Composable
fun InCallScreen(repo: ClientRepository, snap: ClientSnapshot) {
    val ctx = LocalContext.current
    val call = snap.liveCalls.firstOrNull { it.state == LiveCallState.RINGING } ?: snap.liveCalls.first()
    val control = snap.status?.caps?.callControl == true
    val haptics = LocalHapticFeedback.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.callId, call.state) {
        if (call.state == LiveCallState.RINGING) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val name = call.name ?: snap.nameFor(call.number)
    val sim = simLabel(snap, call.subId)
    val bridged = repo.calls.isBridged(call.callId)
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var keypad by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            val since = call.activeSince
            val top = when (call.state) {
                LiveCallState.RINGING -> if (sim != null) stringResource(R.string.call_via_from, "\u0000") else stringResource(R.string.incoming_call)
                LiveCallState.DIALING -> if (sim != null) stringResource(R.string.calling_via, "\u0000") else stringResource(R.string.incall_dialing)
                LiveCallState.HOLDING -> stringResource(R.string.incall_holding)
                else -> if (since != null) Format.duration((now - since) / 1000) else stringResource(R.string.incall_in_progress)
            }
            Text(buildAnnotatedString {
                val parts = top.split("\u0000")
                append(parts[0])
                if (parts.size > 1) { withStyle(SpanStyle(color = accent)) { append(sim ?: "") }; append(parts[1]) }
            }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name ?: Format.ltr(call.number.ifBlank { stringResource(R.string.unknown_number) }), fontSize = 36.sp, lineHeight = 44.sp,
                textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            when {
                name != null -> Text(Format.ltr(call.number), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                call.state == LiveCallState.ACTIVE && sim != null -> Text(sim, style = MaterialTheme.typography.bodyLarge, color = accent)
            }
            if (snap.liveCalls.size > 1) Text(stringResource(R.string.incall_waiting), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary)
            if (!bridged) {
                Surface(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceBright) {
                    Text(stringResource(if (snap.status?.audio == "ready") R.string.incall_audio_off else R.string.incall_audio_note),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!control) Text(stringResource(R.string.incall_no_control), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (call.state == LiveCallState.RINGING) {
                SwipeToAnswer(enabled = control,
                    onAnswer = { resultText(ctx, repo.answer(call.callId)) },
                    onDecline = { resultText(ctx, repo.end(call.callId, ringing = true)) })
                if (snap.demo) Text(stringResource(R.string.demo_call_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.height(48.dp))
            } else {
                Surface(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (more) {
                            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.more), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { more = false }) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                            }
                            val held = call.state == LiveCallState.HOLDING
                            val other = snap.liveCalls.firstOrNull { it.callId != call.callId && it.state != LiveCallState.RINGING }
                            val rows = buildList {
                                if (snap.status?.caps?.hold == true) add(Triple(if (held) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, stringResource(if (held) R.string.resume else R.string.hold)) {
                                    resultText(ctx, repo.model.callAction(call.callId, if (held) CallActions.UNHOLD else CallActions.HOLD)); Unit
                                })
                                if (other != null && snap.status?.caps?.hold == true) add(Triple(Icons.Outlined.SwapCalls, stringResource(R.string.swap)) {
                                    resultText(ctx, repo.model.callAction(other.callId, CallActions.UNHOLD)); Unit
                                })
                            }
                            rows.forEachIndexed { i, (icon, label, act) ->
                                SegmentCard(i, rows.size, modifier = Modifier.padding(horizontal = 0.dp), onClick = { act(); more = false }) {
                                    ListItem(leadingContent = { Icon(icon, null) }, headlineContent = { Text(label) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                                }
                            }
                            if (rows.isEmpty()) Text(stringResource(R.string.unsupported_head), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                        }
                        if (keypad && bridged) {
                            DtmfPad { d -> repo.calls.connection(call.callId)?.onPlayDtmfTone(d) }
                            Spacer(Modifier.height(12.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            CallControl(Icons.Filled.Dialpad, stringResource(R.string.keypad), keypad, enabled = bridged) { keypad = !keypad; more = false }
                            CallControl(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, stringResource(R.string.mute), muted, enabled = bridged) {
                                muted = !muted; repo.calls.setMuted(muted)
                            }
                            CallControl(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.speaker), speaker, enabled = bridged) {
                                speaker = !speaker; repo.calls.setSpeaker(call.callId, speaker)
                            }
                            CallControl(Icons.Filled.MoreVert, stringResource(R.string.more), more, enabled = true) { more = !more; keypad = false }
                        }
                        Surface(onClick = { resultText(ctx, repo.end(call.callId, ringing = false)) }, enabled = control,
                            shape = RoundedCornerShape(50), color = EndRed.copy(alpha = if (control) 1f else 0.5f), contentColor = Color.White,
                            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp).height(64.dp).width(156.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.CallEnd, stringResource(R.string.end_call), Modifier.size(30.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** A round-cornered square control (Keypad, Mute, Speaker, More); dark when on. */
@Composable
private fun CallControl(icon: ImageVector, label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(24.dp),
            color = if (on) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceBright,
            contentColor = if (on) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(width = 76.dp, height = 60.dp).semantics { contentDescription = label }) {
            Box(contentAlignment = Alignment.Center) {
                val tint = androidx.compose.material3.LocalContentColor.current
                Icon(icon, null, Modifier.size(26.dp), tint = if (enabled) tint else tint.copy(alpha = 0.38f))
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Incoming call control: drag the phone toward Answer or Decline. Both are also accessibility actions and tappable. */
@Composable
private fun SwipeToAnswer(enabled: Boolean, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val maxPx = with(density) { 104.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val answerLabel = stringResource(R.string.answer)
    val declineLabel = stringResource(R.string.decline)
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.width(312.dp).height(84.dp).semantics {
            customActions = listOf(
                CustomAccessibilityAction(answerLabel) { if (enabled) onAnswer(); enabled },
                CustomAccessibilityAction(declineLabel) { if (enabled) onDecline(); enabled },
            )
        }) {
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecline, enabled = enabled) { Text(declineLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAnswer, enabled = enabled) { Text(answerLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
            Surface(shape = CircleShape, color = Color.White, shadowElevation = 2.dp,
                modifier = Modifier.absoluteOffset { IntOffset(offset.value.roundToInt(), 0) }.size(width = 84.dp, height = 64.dp)
                    .draggable(
                        state = rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(-maxPx, maxPx)) } },
                        orientation = Orientation.Horizontal, enabled = enabled,
                        onDragStopped = {
                            val toEnd = if (rtl) offset.value < -maxPx * 0.8f else offset.value > maxPx * 0.8f
                            val toStart = if (rtl) offset.value > maxPx * 0.8f else offset.value < -maxPx * 0.8f
                            when { toEnd -> onAnswer(); toStart -> onDecline() }
                            offset.animateTo(0f)
                        },
                    )) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, null, tint = CallGreen, modifier = Modifier.size(30.dp)) }
            }
        }
    }
}

/** Keypad for menus during a call; tones are played into the call by the gateway. */
@Composable
private fun DtmfPad(tap: (Char) -> Unit) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            for (row in keys.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in row) Surface(onClick = { tap(k) }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceBright,
                    modifier = Modifier.weight(1f).height(54.dp).padding(vertical = 3.dp).semantics { contentDescription = k.toString() }) {
                    Box(contentAlignment = Alignment.Center) { Text(k.toString(), fontSize = 24.sp) }
                }
            }
        }
    }
}
