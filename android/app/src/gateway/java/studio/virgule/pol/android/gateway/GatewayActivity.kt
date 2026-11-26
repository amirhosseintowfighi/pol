package studio.virgule.pol.android.gateway

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.common.KeystoreIdentity
import studio.virgule.pol.android.common.SecureStore
import studio.virgule.pol.android.ui.SasCodeView
import studio.virgule.pol.android.ui.SectionCard
import studio.virgule.pol.android.ui.SectionTitle
import studio.virgule.pol.android.ui.PolTheme
import studio.virgule.pol.core.link.TrustedPeer
import kotlinx.coroutines.delay

class GatewayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: show the pairing prompt with sample values for the screenshot workflow.
        if (studio.virgule.pol.android.BuildConfig.DEBUG && intent?.getStringExtra("polScreenshot") == "pair") {
            GatewayState.mutable.value = GatewayState.ui.value.copy(pairingRequest = PairingRequest("974705", "iPhone", "2F1C-77A0-9E41"))
        }
        setContent { PolTheme { GatewayScreen() } }
    }

    override fun onResume() {
        super.onResume()
        GatewayService.current?.refresh()
    }
}

/** A feature the owner can turn on, and the runtime permissions it needs. */
private data class Feature(val key: String, val title: Int, val body: Int, val permissions: List<String>)

private val linkPermissions: List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
}

private val features = listOf(
    Feature("messages", R.string.gw_f_messages, R.string.gw_f_messages_body, listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)),
    Feature("calls", R.string.gw_f_calls, R.string.gw_f_calls_body,
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)),
    Feature("recents", R.string.gw_f_recents, R.string.gw_f_recents_body, listOf(Manifest.permission.READ_CALL_LOG)),
    Feature("contacts", R.string.gw_f_contacts, R.string.gw_f_contacts_body, listOf(Manifest.permission.READ_CONTACTS)),
    Feature("ussd", R.string.gw_f_ussd, R.string.gw_f_ussd_body, listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayScreen() {
    val ctx = LocalContext.current
    val ui by GatewayState.ui.collectAsState()
    val settings = remember { GatewaySettings(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var pendingFeature by remember { mutableStateOf<Feature?>(null) }
    var deniedFeature by remember { mutableStateOf<Feature?>(null) }
    var confirmRevoke by remember { mutableStateOf<TrustedPeer?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun isOn(f: Feature) = when (f.key) {
        "messages" -> settings.messages; "calls" -> settings.calls; "recents" -> settings.recents
        "contacts" -> settings.shareContacts; else -> settings.carrierCodes
    }
    fun setOn(f: Feature, on: Boolean) {
        when (f.key) {
            "messages" -> settings.messages = on; "calls" -> settings.calls = on; "recents" -> settings.recents = on
            "contacts" -> settings.shareContacts = on; else -> settings.carrierCodes = on
        }
        GatewayService.current?.onSettingsChanged()
        refresh++
    }

    val featureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val f = pendingFeature ?: return@rememberLauncherForActivityResult
        pendingFeature = null
        if (result.values.all { it } && f.permissions.all(::granted)) setOn(f, true) else deniedFeature = f
    }

    val startLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        // Notification permission is optional (the service still runs; its notification is just hidden).
        if (linkPermissions.all(::granted)) { settings.enabled = true; GatewayService.start(ctx); refresh++ }
        else deniedFeature = Feature("link", R.string.gw_link, R.string.gw_link_denied, linkPermissions)
    }

    fun turnOn() {
        val ask = linkPermissions + (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        if (ask.isEmpty() || ask.all(::granted)) { settings.enabled = true; GatewayService.start(ctx); refresh++ }
        else startLauncher.launch(ask.toTypedArray())
    }

    LaunchedEffect(ui.pairingResult) {
        val r = ui.pairingResult ?: return@LaunchedEffect
        snackbar.showSnackbar(if (r.startsWith("paired:")) ctx.getString(R.string.gw_paired_ok, r.removePrefix("paired:")) else ctx.getString(R.string.gw_paired_failed))
    }
    LaunchedEffect(Unit) { if (settings.enabled && GatewayService.current == null && linkPermissions.all(::granted)) GatewayService.start(ctx) }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.gw_title)) }, scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        key(refresh) { LazyColumn(contentPadding = pad) {
            item {
                SectionCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.gw_switch)) },
                        supportingContent = {
                            Text(when {
                                !settings.enabled -> stringResource(R.string.gw_off_body)
                                !ui.bluetoothOn -> stringResource(R.string.gw_bluetooth_off)
                                ui.advertisingError != null -> stringResource(R.string.gw_advertising_failed)
                                ui.connected.isEmpty() -> stringResource(R.string.gw_waiting)
                                else -> stringResource(R.string.gw_connected_to, ui.connected.joinToString())
                            })
                        },
                        leadingContent = { Icon(if (ui.bluetoothOn) Icons.Outlined.Bluetooth else Icons.Outlined.BluetoothDisabled, null) },
                        trailingContent = {
                            Switch(checked = settings.enabled, onCheckedChange = { on ->
                                if (on) turnOn() else { settings.enabled = false; GatewayService.stop(ctx); refresh++ }
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.gw_boot)) },
                        supportingContent = { Text(stringResource(R.string.gw_boot_body)) },
                        trailingContent = { Switch(checked = settings.startOnBoot, onCheckedChange = { settings.startOnBoot = it; refresh++ }) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.gw_pair_title)) }
            item {
                SectionCard {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(ui.pairingOpenUntil) { while (ui.pairingOpenUntil > 0) { now = System.currentTimeMillis(); delay(1000) } }
                        if (ui.pairingOpenUntil > now) {
                            val left = (ui.pairingOpenUntil - now) / 1000
                            Text(stringResource(R.string.gw_pair_open, Format.duration(left)), style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.gw_pair_open_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { GatewayService.current?.closePairing() }) { Text(stringResource(R.string.gw_pair_stop)) }
                        } else {
                            Text(stringResource(R.string.gw_pair_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            Button(enabled = ui.running, onClick = { GatewayService.current?.openPairing() }) { Text(stringResource(R.string.gw_pair_start)) }
                            if (!ui.running) Text(stringResource(R.string.gw_pair_needs_on), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.gw_paired_title)) }
            if (ui.paired.isEmpty()) item { SectionCard { ListItem(headlineContent = { Text(stringResource(R.string.gw_paired_none)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) } }
            items(ui.paired, key = { it.idHex }) { peer ->
                SectionCard {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.PhoneIphone, null) },
                        headlineContent = { Text(peer.name) },
                        supportingContent = { Text(stringResource(R.string.gw_paired_meta, Format.date(peer.pairedAtMs), Format.ltr(peer.fingerprint))) },
                        trailingContent = {
                            IconButton(onClick = { confirmRevoke = peer }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.gw_remove_device, peer.name))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.gw_features_title)) }
            item {
                SectionCard {
                    for (f in features) {
                        val on = isOn(f) && f.permissions.all(::granted)
                        ListItem(
                            headlineContent = { Text(stringResource(f.title)) },
                            supportingContent = { Text(stringResource(f.body)) },
                            trailingContent = {
                                Switch(checked = on, onCheckedChange = { want ->
                                    if (!want) setOn(f, false)
                                    else if (f.permissions.all(::granted)) setOn(f, true)
                                    else { pendingFeature = f; featureLauncher.launch(f.permissions.toTypedArray()) }
                                })
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                }
            }

            item { FullControlCard(settings.calls, onChanged = { GatewayService.current?.refresh(); refresh++ }) }
            item { SectionTitle(stringResource(R.string.gw_audio_title)) }
            item { CallAudioCard(ui, settings, onChanged = { GatewayService.current?.onSettingsChanged(); refresh++ }) }

            item { SectionTitle(stringResource(R.string.gw_unsupported_title)) }
            item {
                SectionCard {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Block, null) },
                        headlineContent = { Text(stringResource(R.string.gw_unsupported_head)) },
                        supportingContent = { Text(stringResource(R.string.gw_unsupported_body)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.privacy_title)) }
            item {
                SectionCard {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Lock, null) },
                        headlineContent = { Text(stringResource(R.string.privacy_head)) },
                        supportingContent = { Text(stringResource(R.string.gw_privacy_body, Format.ltr(ui.identityFingerprint))) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        FilledTonalButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.gw_reset)) }
                    }
                }
                SectionTitle(stringResource(R.string.about_title))
                studio.virgule.pol.android.ui.AboutCard(remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() })
                Spacer(Modifier.height(32.dp))
            }
        } }
    }

    ui.pairingRequest?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.gw_compare_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.gw_compare_body, req.clientName))
                    Spacer(Modifier.height(16.dp))
                    SasCodeView(req.sas)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.fingerprint_label, Format.ltr(req.fingerprint)), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { GatewayService.current?.confirmPairing() }) { Text(stringResource(R.string.codes_match)) } },
            dismissButton = { TextButton(onClick = { GatewayService.current?.declinePairing() }) { Text(stringResource(R.string.codes_differ)) } },
        )
    }

    deniedFeature?.let { f ->
        AlertDialog(
            onDismissRequest = { deniedFeature = null },
            title = { Text(stringResource(R.string.permission_denied_title)) },
            text = { Text(stringResource(R.string.permission_denied_body, stringResource(f.title))) },
            confirmButton = {
                TextButton(onClick = {
                    deniedFeature = null
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = { TextButton(onClick = { deniedFeature = null }) { Text(stringResource(R.string.not_now)) } },
        )
    }

    confirmRevoke?.let { peer ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text(stringResource(R.string.gw_remove_title, peer.name)) },
            text = { Text(stringResource(R.string.gw_remove_body)) },
            confirmButton = { TextButton(onClick = { GatewayService.current?.revoke(peer.id) ?: PairedDevices(SecureStore(ctx)).remove(peer.id); confirmRevoke = null; refresh++ }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.gw_reset_title)) },
            text = { Text(stringResource(R.string.gw_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    GatewayService.stop(ctx)
                    PairedDevices(SecureStore(ctx)).clear()
                    SecureStore(ctx).wipe()
                    KeystoreIdentity.delete()
                    settings.clear()
                    GatewayState.mutable.value = GatewayUi()
                    refresh++
                }) { Text(stringResource(R.string.erase)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}


/**
 * Call audio on the client: needs Android 13+, the Calls feature, and Shizuku (started with Wireless debugging)
 * to run Pol's small audio helper as the shell user. Each step is shown with what to do next.
 */
@Composable
private fun CallAudioCard(ui: GatewayUi, settings: GatewaySettings, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    SectionCard {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.RecordVoiceOver, null) },
            headlineContent = { Text(stringResource(R.string.gw_audio_switch)) },
            supportingContent = { Text(stringResource(if (settings.calls) R.string.gw_audio_body else R.string.gw_audio_needs_calls)) },
            trailingContent = {
                Switch(checked = settings.callAudio && settings.calls, enabled = settings.calls,
                    onCheckedChange = { settings.callAudio = it; onChanged() })
            },
            colors = colors,
        )
        if (settings.callAudio && settings.calls) {
            val (text, action) = when (ui.audioSetup) {
                "ANDROID_VERSION" -> R.string.gw_audio_android to null
                "SHIZUKU_MISSING" -> R.string.gw_audio_install to R.string.gw_audio_get_shizuku
                "SHIZUKU_STOPPED" -> R.string.gw_audio_start_shizuku to R.string.gw_audio_open_shizuku
                "PERMISSION_NEEDED" -> R.string.gw_audio_permission to R.string.gw_audio_allow
                "NOT_INTERCEPTABLE" -> R.string.gw_audio_not_supported to null
                "READY" -> (if (ui.audioActive) R.string.gw_audio_active else R.string.gw_audio_ready) to null
                else -> R.string.gw_audio_connecting to null
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = {
                        when (action) {
                            R.string.gw_audio_allow -> GatewayService.current?.requestAudioPermission()
                            R.string.gw_audio_open_shizuku -> ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { ctx.startActivity(it) }
                            else -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
                        }
                    }) { Text(stringResource(action)) }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.gw_audio_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


/**
 * Full call control: Pol becomes this phone's default phone app (Android's role request dialog), so paired
 * devices get exact call states, hold / resume / swap and real keypad tones. Off by default; reversible in
 * Android's Settings › Default apps.
 */
@Composable
private fun FullControlCard(callsOn: Boolean, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
    var isDefault by remember { mutableStateOf(studio.virgule.pol.android.gateway.telecom.CallRegistry.isDefaultDialer(tm, ctx.packageName)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefault = studio.virgule.pol.android.gateway.telecom.CallRegistry.isDefaultDialer(tm, ctx.packageName); onChanged()
    }
    SectionCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.gw_full_title)) },
            supportingContent = { Text(stringResource(if (isDefault) R.string.gw_full_on else R.string.gw_full_body)) },
            trailingContent = {
                Switch(checked = isDefault, enabled = callsOn, onCheckedChange = { want ->
                    if (want) {
                        val intent = if (Build.VERSION.SDK_INT >= 29) {
                            ctx.getSystemService(android.app.role.RoleManager::class.java).createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER)
                        } else Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                            .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
                        runCatching { launcher.launch(intent) }
                    } else {
                        // Android lets the user, not apps, give the role back: open Default apps.
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                    }
                })
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
