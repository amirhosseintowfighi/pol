package studio.virgule.pol.android.client.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ClientRepository
import studio.virgule.pol.android.common.Format
import studio.virgule.pol.android.ui.Pill
import studio.virgule.pol.android.ui.SectionCard
import studio.virgule.pol.android.ui.SectionTitle
import studio.virgule.pol.android.ui.SignalBars
import studio.virgule.pol.core.billing.OfferTerms
import studio.virgule.pol.core.billing.Plan
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.LinkState
import java.time.Instant
import java.time.ZoneId

@Composable
fun DeviceScreen(repo: ClientRepository, snap: ClientSnapshot, onUnpaired: () -> Unit) {
    val ctx = LocalContext.current
    var notify by remember { mutableStateOf(repo.notificationsEnabled) }
    var previews by remember { mutableStateOf(repo.showPreviews) }
    var confirmForget by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        repo.notificationsEnabled = ok; notify = ok
    }
    val st = snap.status
    val cardColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

    LazyColumn {
        item { SectionTitle(stringResource(R.string.gateway_title)) }
        item {
            SectionCard {
                ListItem(
                    headlineContent = { Text(st?.name ?: repo.gateway?.name ?: stringResource(R.string.demo_gateway)) },
                    supportingContent = {
                        Text(when (snap.link) {
                            LinkState.CONNECTED -> stringResource(R.string.link_connected) + (snap.pingMs?.let { " · " + Format.digits("$it ms") } ?: "")
                            LinkState.BLUETOOTH_OFF -> stringResource(R.string.banner_bt_off)
                            LinkState.RECONNECTING -> stringResource(R.string.banner_reconnecting)
                            else -> stringResource(R.string.banner_searching)
                        })
                    },
                    trailingContent = {
                        st?.battery?.let { b -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.BatteryStd, null); Text(Format.digits("$b%") + if (st.charging) " ⚡" else "")
                        } }
                    },
                    colors = cardColors,
                )
                for (sim in st?.sims.orEmpty()) {
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.SimCard, null) },
                        headlineContent = { Text(sim.label.ifBlank { "SIM ${sim.slot + 1}" }) },
                        supportingContent = { Text(listOfNotNull(sim.carrier.ifBlank { null }, sim.network, if (sim.roaming) stringResource(R.string.roaming) else null).joinToString(" · ")) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (sim.subId == st?.defaultVoiceSub) { Pill(stringResource(R.string.default_line), strong = true); Spacer(Modifier.width(8.dp)) }
                                SignalBars(sim.signal, stringResource(R.string.signal_x, sim.signal ?: 0))
                            }
                        },
                        colors = cardColors,
                    )
                }
                if (st != null && st.sims.isEmpty()) ListItem(headlineContent = { Text(stringResource(R.string.no_sim_info)) }, colors = cardColors)
            }
        }

        item { SectionTitle(stringResource(R.string.features_title)) }
        item {
            SectionCard {
                val caps = st?.caps
                fun row(label: Int, on: Boolean?) = @Composable {
                    ListItem(headlineContent = { Text(stringResource(label)) },
                        trailingContent = { Text(stringResource(when (on) { true -> R.string.available; false -> R.string.off_on_gateway; null -> R.string.unknown })) },
                        colors = cardColors)
                }
                row(R.string.f_send_sms, caps?.smsSend)()
                row(R.string.f_read_sms, caps?.smsRead)()
                row(R.string.f_place_calls, caps?.callPlace)()
                row(R.string.f_answer_calls, caps?.callControl)()
                row(R.string.f_recents, caps?.callLog)()
                row(R.string.f_contacts, caps?.contacts)()
                row(R.string.f_ussd, caps?.ussd)()
                ListItem(
                    leadingContent = { Icon(Icons.Outlined.Block, null) },
                    headlineContent = { Text(stringResource(R.string.unsupported_head)) },
                    supportingContent = { Text(stringResource(R.string.unsupported_body)) },
                    colors = cardColors,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.calls_here_title)) }
        item { CallsHereCard(repo, snap) }

        item { SectionTitle(stringResource(R.string.notifications_title)) }
        item {
            SectionCard {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notify_toggle)) },
                    supportingContent = { Text(stringResource(R.string.notify_body)) },
                    trailingContent = {
                        Switch(checked = notify, onCheckedChange = { on ->
                            if (!on) { repo.notificationsEnabled = false; notify = false }
                            else if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { repo.notificationsEnabled = true; notify = true }
                            else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        })
                    },
                    colors = cardColors,
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.previews_toggle)) },
                    supportingContent = { Text(stringResource(R.string.previews_body)) },
                    trailingContent = { Switch(checked = previews, enabled = notify, onCheckedChange = { repo.showPreviews = it; previews = it }) },
                    colors = cardColors,
                )
            }
        }

        if (snap.demo) {
            item { SectionTitle(stringResource(R.string.demo_title)) }
            item {
                SectionCard {
                    Text(stringResource(R.string.demo_body), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        FilledTonalButton(onClick = repo::simulateIncomingCall) { Text(stringResource(R.string.demo_ring)) }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = repo::simulateIncomingSms) { Text(stringResource(R.string.demo_text)) }
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(onClick = { repo.exitDemo(); onUnpaired() }) { Text(stringResource(R.string.demo_exit)) }
                    }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.subscription_title)) }
        item { SubscriptionCard() }

        item { SectionTitle(stringResource(R.string.privacy_title)) }
        item {
            SectionCard {
                ListItem(leadingContent = { Icon(Icons.Outlined.Lock, null) }, headlineContent = { Text(stringResource(R.string.privacy_head)) },
                    supportingContent = { Text(stringResource(R.string.cl_privacy_body)) }, colors = cardColors)
                repo.gateway?.let { g ->
                    ListItem(headlineContent = { Text(stringResource(R.string.paired_with, g.name)) },
                        supportingContent = { Text(stringResource(R.string.fingerprint_label, Format.ltr(g.fingerprint)) + "\n" + Format.date(g.pairedAtMs)) }, colors = cardColors)
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (repo.gateway != null) { OutlinedButton(onClick = { confirmForget = true }) { Text(stringResource(R.string.unpair)) }; Spacer(Modifier.width(8.dp)) }
                    FilledTonalButton(onClick = { confirmErase = true }) { Text(stringResource(R.string.erase_all)) }
                }
            }
            SectionTitle(stringResource(R.string.about_title))
            studio.virgule.pol.android.ui.AboutCard(remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text(stringResource(R.string.unpair_title)) }, text = { Text(stringResource(R.string.unpair_body)) },
        confirmButton = { TextButton(onClick = { confirmForget = false; repo.forgetGateway(); onUnpaired() }) { Text(stringResource(R.string.unpair)) } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text(stringResource(R.string.erase_title)) }, text = { Text(stringResource(R.string.erase_body)) },
        confirmButton = { TextButton(onClick = { confirmErase = false; repo.eraseAll(); onUnpaired() }) { Text(stringResource(R.string.erase)) } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Plan terms, always visible before any commitment. No payment provider is configured in this build, so both
 * payment methods are shown as unavailable and nothing is charged or unlocked (see docs/BILLING.md).
 */
@Composable
private fun SubscriptionCard() {
    val terms = remember { OfferTerms.startingAt(Instant.now(), ZoneId.systemDefault()) }
    SectionCard {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.CreditCard, null) },
            headlineContent = { Text(stringResource(R.string.plan_name)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.plan_terms, Format.number(Plan.PRICE_TOMANS)))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.plan_dates, Format.date(terms.trialStart.toEpochMilli()), Format.date(terms.trialEnd.toEpochMilli()),
                        Format.number(Plan.PRICE_TOMANS)))
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.plan_cancel))
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.billing_unavailable)) },
            supportingContent = { Text(stringResource(R.string.billing_unavailable_body)) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.pay_gateway)) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.pay_card)) }
        }
    }
}


/** "Take calls on this phone": the only place RECORD_AUDIO is requested. */
@Composable
private fun CallsHereCard(repo: ClientRepository, snap: ClientSnapshot) {
    var on by remember { mutableStateOf(repo.callsOnThisPhone) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok; if (ok) { repo.callsOnThisPhone = true; on = true }
    }
    SectionCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.calls_here_toggle)) },
            supportingContent = {
                Text(stringResource(when {
                    denied -> R.string.calls_here_mic_denied
                    snap.status?.audio == "ready" -> if (on) R.string.calls_here_ready else R.string.calls_here_available
                    snap.status?.audio == "android_version" -> R.string.calls_here_android
                    snap.status?.audio == "not_interceptable" -> R.string.calls_here_not_supported
                    snap.status?.audio == "needs_setup" -> R.string.calls_here_setup
                    else -> R.string.calls_here_off
                }))
            },
            trailingContent = {
                Switch(checked = on, onCheckedChange = { want ->
                    if (!want) { repo.callsOnThisPhone = false; on = false } else launcher.launch(Manifest.permission.RECORD_AUDIO)
                })
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
