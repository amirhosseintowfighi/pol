package studio.virgule.pol.android.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Avatar
import studio.virgule.pol.android.ui.EmptyState
import studio.virgule.pol.android.ui.Pill
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.CallType
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.Validate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CallsScreen(repo: ClientRepository, snap: ClientSnapshot, snackbar: SnackbarHostState, openThread: (Long, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keypad by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CallEntry?>(null) }
    fun call(number: String, sub: Int?) {
        resultText(ctx, repo.dial(number, sub ?: defaultVoiceSub(snap)))?.let { scope.launch { snackbar.showSnackbar(it) } }
    }

    Box(Modifier.fillMaxSize()) {
        val favourites = snap.contacts.filter { it.starred }
        if (snap.status?.caps?.callLog == false && favourites.isEmpty()) {
            EmptyState(Icons.Outlined.History, stringResource(R.string.calls_off_title), stringResource(R.string.calls_off_body))
        } else if (snap.calls.isEmpty() && favourites.isEmpty()) {
            EmptyState(Icons.Outlined.History, stringResource(R.string.calls_empty_title), stringResource(R.string.calls_empty_body))
        } else LazyColumn(Modifier.fillMaxSize()) {
            if (favourites.isNotEmpty()) {
                item { Text(stringResource(R.string.favourites), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)) }
                item {
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        items(favourites, key = { it.id }) { c ->
                            Column(Modifier.width(76.dp).clickable(onClickLabel = stringResource(R.string.call_x, c.name)) { c.phones.firstOrNull()?.let { call(it.number, null) } }
                                .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(c.name, size = 56)
                                Text(c.name.substringBefore(' '), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (snap.calls.isNotEmpty()) item { Text(stringResource(R.string.recents), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            items(snap.calls, key = { it.id }) { e -> RecentRow(snap, e) { selected = e } }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(onClick = { keypad = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Outlined.Dialpad, contentDescription = stringResource(R.string.keypad))
        }
    }

    if (keypad) KeypadSheet(repo, snap, onDismiss = { keypad = false }, onCall = { n, s -> call(n, s); keypad = false })

    selected?.let { e ->
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            val name = e.name ?: snap.nameFor(e.number)
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(name ?: Format.ltr(e.number), style = MaterialTheme.typography.headlineSmall)
                if (name != null) Text(Format.ltr(e.number), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.dateTime(e.ts) + if (e.durationS > 0) " · " + Format.duration(e.durationS) else "", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                var sub by remember { mutableStateOf(e.subId ?: defaultVoiceSub(snap)) }
                SimPicker(snap, sub, { sub = it })
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = Validate.dialNumber(e.number) != null, onClick = { call(e.number, sub); selected = null }) {
                        Icon(Icons.Outlined.Call, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.call))
                    }
                    TextButton(enabled = Validate.smsRecipient(e.number) != null, onClick = {
                        val t = snap.messages.values.firstOrNull { numberKey(it.address) == numberKey(e.number) }?.thread ?: 0L
                        selected = null; openThread(t, e.number)
                    }) { Icon(Icons.AutoMirrored.Outlined.Message, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.message)) }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(snap: ClientSnapshot, e: CallEntry, onClick: () -> Unit) {
    val name = e.name ?: snap.nameFor(e.number)
    val missed = e.type == CallType.MISSED || e.type == CallType.REJECTED
    val typeLabel = stringResource(when (e.type) {
        CallType.INCOMING -> R.string.call_incoming; CallType.OUTGOING -> R.string.call_outgoing
        CallType.MISSED -> R.string.call_missed; CallType.REJECTED -> R.string.call_declined
        CallType.BLOCKED -> R.string.call_blocked; CallType.OTHER -> R.string.call_other
    })
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Avatar(name ?: "#") },
        headlineContent = {
            Text(name ?: Format.ltr(e.number.ifBlank { stringResource(R.string.unknown_number) }),
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(when (e.type) {
                    CallType.OUTGOING -> Icons.AutoMirrored.Outlined.CallMade
                    CallType.MISSED, CallType.REJECTED -> Icons.AutoMirrored.Outlined.CallMissed
                    CallType.BLOCKED -> Icons.Outlined.Block
                    else -> Icons.AutoMirrored.Outlined.CallReceived
                }, contentDescription = typeLabel, modifier = Modifier.size(16.dp),
                    tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(Format.relative(e.ts))
                simLabel(snap, e.subId)?.let { Spacer(Modifier.width(6.dp)); Pill(it) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadSheet(repo: ClientRepository, snap: ClientSnapshot, onDismiss: () -> Unit, onCall: (String, Int?) -> Unit) {
    var number by remember { mutableStateOf("") }
    var sub by remember { mutableStateOf(defaultVoiceSub(snap)) }
    val haptics = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val isCode = number.startsWith("*") || number.startsWith("#")
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Format.ltr(number.ifEmpty { " " }), fontSize = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                maxLines = 1)
            val match = if (number.length >= 4) snap.nameFor(number) else null
            Text(match ?: " ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SimPicker(snap, sub, { sub = it })
            Spacer(Modifier.height(8.dp))
            val keys = listOf("1" to "", "2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "*" to "", "0" to "+", "#" to "")
            // The dial pad keeps its standard left-to-right layout in every language.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
                Column(Modifier.width(300.dp)) {
                    for (row in keys.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for ((d, letters) in row) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(6.dp).size(76.dp).semantics { contentDescription = d }) {
                                Column(Modifier.fillMaxSize().clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (number.length < 32) number += d
                                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Text(d, fontSize = 28.sp)
                                    if (letters.isNotEmpty()) Text(letters, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(56.dp))
                        FilledIconButton(onClick = {
                            if (isCode) {
                                resultText(ctx, repo.model.ussd(number, sub))?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
                            } else onCall(number, sub)
                        }, enabled = if (isCode) Validate.ussdCode(number) != null && snap.status?.caps?.ussd == true else Validate.dialNumber(number) != null,
                            modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                            Icon(Icons.Outlined.Call, contentDescription = stringResource(if (isCode) R.string.send_code else R.string.call))
                        }
                        IconButton(onClick = { number = number.dropLast(1) }, enabled = number.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.delete_digit))
                        }
                    }
                }
            }
            if (isCode) Text(stringResource(if (snap.status?.caps?.ussd == true) R.string.code_hint else R.string.code_unavailable),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp), textAlign = TextAlign.Center)
            snap.ussd?.let { u ->
                Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(when { u.pending -> stringResource(R.string.code_waiting); u.ok -> u.text.orEmpty(); else -> describe(ctx, u.err ?: "failed") },
                        Modifier.padding(16.dp))
                }
            }
        }
    }
}

/** Shown while the gateway has a ringing or active call. Voice stays on the gateway phone (see COMPATIBILITY.md). */
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(64.dp))
            Avatar(name ?: "#", size = 96)
            Spacer(Modifier.height(16.dp))
            Text(name ?: Format.ltr(call.number.ifBlank { stringResource(R.string.unknown_number) }), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            if (name != null) Text(Format.ltr(call.number), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val since = call.activeSince
            Text(when (call.state) {
                LiveCallState.RINGING -> stringResource(R.string.incall_ringing)
                LiveCallState.DIALING -> stringResource(R.string.incall_dialing)
                LiveCallState.HOLDING -> stringResource(R.string.incall_holding)
                else -> if (since != null) stringResource(R.string.incall_active, Format.duration((now - since) / 1000)) else stringResource(R.string.incall_in_progress)
            }, style = MaterialTheme.typography.titleMedium)
            simLabel(snap, call.subId)?.let { Spacer(Modifier.height(6.dp)); Pill(it) }
            if (snap.liveCalls.size > 1) Text(stringResource(R.string.incall_waiting), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(24.dp))
            val bridged = repo.calls.isBridged(call.callId)
            if (!bridged) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(stringResource(if (snap.status?.audio == "ready") R.string.incall_audio_off else R.string.incall_audio_note),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            if (call.state != LiveCallState.RINGING && (bridged || snap.status?.caps?.hold == true)) {
                var muted by remember { mutableStateOf(false) }
                var speaker by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (bridged) {
                        ToggleAction(if (muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, stringResource(R.string.mute), muted) { muted = !muted; repo.calls.setMuted(muted) }
                        ToggleAction(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.speaker), speaker) { speaker = !speaker; repo.calls.setSpeaker(call.callId, speaker) }
                    }
                    if (snap.status?.caps?.hold == true) {
                        val held = call.state == LiveCallState.HOLDING
                        ToggleAction(if (held) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, stringResource(if (held) R.string.resume else R.string.hold), held) {
                            resultText(ctx, repo.model.callAction(call.callId, if (held) CallActions.UNHOLD else CallActions.HOLD))
                        }
                    }
                }
            }
            if (!control) Text(stringResource(R.string.incall_no_control), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (call.state == LiveCallState.RINGING) {
                    RoundAction(Icons.Outlined.CallEnd, stringResource(R.string.decline), MaterialTheme.colorScheme.error, enabled = control) {
                        resultText(ctx, repo.end(call.callId, ringing = true))
                    }
                    RoundAction(Icons.Outlined.Call, stringResource(R.string.answer), MaterialTheme.colorScheme.primary, enabled = control) {
                        resultText(ctx, repo.answer(call.callId))
                    }
                } else {
                    RoundAction(Icons.Outlined.CallEnd, stringResource(R.string.end_call), MaterialTheme.colorScheme.error, enabled = control) {
                        resultText(ctx, repo.end(call.callId, ringing = false))
                    }
                }
            }
            if (snap.demo && call.state == LiveCallState.RINGING) Text(stringResource(R.string.demo_call_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.FilledTonalIconToggleButton(checked = on, onCheckedChange = { onClick() }, modifier = Modifier.size(64.dp)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(72.dp).aspectRatio(1f),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) { Icon(icon, contentDescription = label) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
