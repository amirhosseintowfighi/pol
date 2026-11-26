package studio.virgule.pol.android.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhonelinkRing
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.client.PairingUi
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.SasCodeView
import studio.virgule.pol.core.link.CloseReason

@Composable
fun OnboardingScreen(repo: ClientRepository, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val pairing by repo.pairing.collectAsState()
    var denied by remember { mutableStateOf(false) }
    val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) { denied = false; repo.startPairing() } else denied = true
    }
    fun pair() {
        if (perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) repo.startPairing() else launcher.launch(perms)
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(32.dp))
            when (val p = pairing) {
                PairingUi.Idle -> {
                    Icon(Icons.Outlined.PhonelinkRing, null, Modifier.padding(8.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Steps()
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::pair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pair_start)) }
                    OutlinedButton(onClick = { repo.enterDemo(); onDone() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_demo)) }
                    if (denied) Text(stringResource(R.string.bt_denied), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(16.dp))
                    PrivacyNote()
                }
                is PairingUi.Searching -> {
                    Icon(Icons.AutoMirrored.Outlined.BluetoothSearching, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.pair_searching), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(if (p.found.size > 1) R.string.pair_several else R.string.pair_searching_body), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp)); CircularProgressIndicator()
                    TextButton(onClick = repo::cancelPairing) { Text(stringResource(R.string.cancel)) }
                }
                PairingUi.Connecting -> {
                    Text(stringResource(R.string.pair_connecting), style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(24.dp)); CircularProgressIndicator()
                    TextButton(onClick = repo::cancelPairing) { Text(stringResource(R.string.cancel)) }
                }
                is PairingUi.Compare -> {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.compare_body, p.gatewayName), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    SasCodeView(p.sas)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.fingerprint_label, Format.ltr(p.fingerprint)), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(24.dp))
                    if (p.waitingForGateway) {
                        CircularProgressIndicator(); Text(stringResource(R.string.compare_waiting), Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                    } else {
                        Button(onClick = repo::confirmSas, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.codes_match)) }
                        OutlinedButton(onClick = repo::declineSas, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.codes_differ)) }
                    }
                }
                is PairingUi.Done -> {
                    Text(stringResource(R.string.pair_done, p.gatewayName), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { repo.cancelPairing(); onDone() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_)) }
                }
                is PairingUi.Failed -> {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.pair_failed), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(when (p.reason) {
                        CloseReason.PAIRING_CLOSED -> R.string.pair_fail_closed
                        CloseReason.TOO_MANY_ATTEMPTS -> R.string.pair_fail_attempts
                        CloseReason.DECLINED -> R.string.pair_fail_declined
                        CloseReason.TIMEOUT -> R.string.pair_fail_timeout
                        CloseReason.BUSY -> R.string.pair_fail_busy
                        CloseReason.BAD_VERSION -> R.string.pair_fail_version
                        else -> R.string.pair_fail_generic
                    }), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = ::pair, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.try_again)) }
                    TextButton(onClick = repo::cancelPairing) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun Steps() {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.setup_needs), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            for (s in listOf(R.string.setup_1, R.string.setup_2, R.string.setup_3, R.string.setup_4)) Text("• " + stringResource(s), Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
fun PrivacyNote() {
    Text(stringResource(R.string.privacy_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
