package studio.virgule.pol.android.gateway.telecom

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.PolTheme
import studio.virgule.pol.core.proto.Validate

/**
 * The gateway's dialer, required by Android for the default phone app (it handles ACTION_DIAL and tel: links).
 * Kept deliberately simple: the gateway usually sits in a bag, and calls are normally made from paired devices.
 */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val initial = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent { PolTheme { Dialer(Validate.normalizeNumber(initial) ?: "") { finish() } } }
    }
}

@Composable
private fun Dialer(initial: String, done: () -> Unit) {
    val ctx = LocalContext.current
    var number by remember { mutableStateOf(initial) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            Text(Format.ltr(number.ifEmpty { " " }), fontSize = 34.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Keypad { d -> if (number.length < 32) number += d }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(56.dp))
                FilledIconButton(onClick = {
                    val n = Validate.dialNumber(number) ?: Validate.ussdCode(number) ?: return@FilledIconButton
                    if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", n, null), Bundle()) }
                        done()
                    }
                }, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.Outlined.Call, contentDescription = stringResource(R.string.call))
                }
                IconButton(onClick = { number = number.dropLast(1) }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.delete_digit))
                }
            }
        }
    }
}

@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            for (row in listOf("123", "456", "789", "*0#")) Row {
                for (d in row) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.padding(6.dp).size(72.dp).semantics { contentDescription = d.toString() }) {
                        Column(Modifier.fillMaxSize().clickable { onKey(d) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(d.toString(), fontSize = 28.sp)
                        }
                    }
                }
            }
        }
    }
}

/** The gateway phone's own in-call screen (shown from the call notification and when a call rings here). */
class PhoneCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        setContent { PolTheme { InCall { finish() } } }
    }
}

@Composable
private fun InCall(close: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = CallRegistry.Listener { version++ }
        CallRegistry.addListener(l)
        onDispose { CallRegistry.removeListener(l) }
    }
    androidx.compose.runtime.key(version) { InCallContent(close) }
}

@Composable
private fun InCallContent(close: () -> Unit) {
    val calls = CallRegistry.calls()
    if (calls.isEmpty()) { close(); return }
    val (call, id) = calls.firstOrNull { CallRegistry.stateOf(it.first) == Call.STATE_RINGING } ?: calls.first()
    val state = CallRegistry.stateOf(call)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(80.dp))
            Text(Format.ltr(call.details.handle?.schemeSpecificPart ?: stringResource(R.string.unknown_number)), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(when (state) {
                Call.STATE_RINGING -> R.string.incall_ringing
                Call.STATE_HOLDING -> R.string.incall_holding
                Call.STATE_ACTIVE -> R.string.incall_in_progress
                else -> R.string.incall_dialing
            }), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 48.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (state == Call.STATE_RINGING) {
                    Round(Icons.Outlined.CallEnd, stringResource(R.string.decline), MaterialTheme.colorScheme.error) { CallRegistry.perform(id, "reject") }
                    Round(Icons.Outlined.Call, stringResource(R.string.answer), MaterialTheme.colorScheme.primary) { CallRegistry.perform(id, "answer") }
                } else {
                    if (state == Call.STATE_HOLDING) Round(Icons.Outlined.PlayArrow, stringResource(R.string.resume), MaterialTheme.colorScheme.secondary) { CallRegistry.perform(id, "unhold") }
                    else Round(Icons.Outlined.Pause, stringResource(R.string.hold), MaterialTheme.colorScheme.secondary) { CallRegistry.perform(id, "hold") }
                    Round(Icons.Outlined.CallEnd, stringResource(R.string.end_call), MaterialTheme.colorScheme.error) { CallRegistry.perform(id, "end") }
                }
            }
        }
    }
}

@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(72.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
    }
}
