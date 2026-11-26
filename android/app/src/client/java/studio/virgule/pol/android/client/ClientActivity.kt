package studio.virgule.pol.android.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.virgule.pol.android.R
import studio.virgule.pol.android.client.ui.CallsScreen
import studio.virgule.pol.android.client.ui.ContactsScreen
import studio.virgule.pol.android.client.ui.DeviceScreen
import studio.virgule.pol.android.client.ui.InCallScreen
import studio.virgule.pol.android.client.ui.MessagesScreen
import studio.virgule.pol.android.client.ui.OnboardingScreen
import studio.virgule.pol.android.client.ui.ThreadScreen
import studio.virgule.pol.android.client.ui.describe
import studio.virgule.pol.android.ui.PolTheme
import studio.virgule.pol.core.client.LinkState

class ClientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Debug builds only: open one screen with demo data for the automated screenshot workflow.
        val screenshot = if (studio.virgule.pol.android.BuildConfig.DEBUG) intent?.getStringExtra("polScreenshot") else null
        setContent { PolTheme { ClientRoot(screenshot) } }
    }

    override fun onStart() { super.onStart(); AppVisibility.foreground = true }
    override fun onStop() { AppVisibility.foreground = false; super.onStop() }
}

/** Where the user is. A thread is addressed by its id, or by a number for a new conversation. */
sealed interface Detail {
    data class Thread(val thread: Long, val address: String) : Detail
}

@Composable
private fun ClientRoot(screenshot: String? = null) {
    val ctx = LocalContext.current
    val repo = remember { ClientRepository.get(ctx) }
    val snap by repo.state.collectAsState()
    var onboarding by rememberSaveable { mutableStateOf(repo.gateway == null && !repo.demoMode) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(screenshot) {
        when (screenshot) {
            null -> {}
            "pair" -> repo.debugPairingPreview()
            else -> {
                repo.enterDemo(); onboarding = false
                tab = when (screenshot) { "contacts" -> 1; "messages", "thread" -> 2; "gateway" -> 3; else -> 0 }
                if (screenshot == "thread") detail = Detail.Thread(1, "+15555550101")
                if (screenshot == "incall") { kotlinx.coroutines.delay(600); repo.simulateIncomingCall() }
            }
        }
    }
    LaunchedEffect(snap.link, repo.gateway) {
        if (repo.gateway != null && !snap.demo && repo.hasBluetoothPermissions()) ClientService.start(ctx)
    }
    LaunchedEffect(snap.lastError) {
        val e = snap.lastError ?: return@LaunchedEffect
        snackbar.showSnackbar(describe(ctx, e))
        repo.model.clearError()
    }

    if (onboarding || (snap.link == LinkState.NOT_PAIRED && !snap.demo)) {
        OnboardingScreen(repo, onDone = { onboarding = false })
        return
    }

    // An active or ringing call takes over the screen, like the system dialer.
    if (snap.liveCalls.isNotEmpty()) { InCallScreen(repo, snap); return }

    val d = detail
    if (d is Detail.Thread) {
        BackHandler { detail = null }
        ThreadScreen(repo, snap, d.thread, d.address, onBack = { detail = null }, snackbar = snackbar)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(R.string.tab_calls)) },
                    icon = { BadgedBox(badge = { if (snap.missedCalls > 0) Badge { Text(snap.missedCalls.coerceAtMost(99).toString()) } }) { Icon(Icons.Outlined.Call, null) } })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(R.string.tab_contacts)) }, icon = { Icon(Icons.Outlined.People, null) })
                val unread = snap.conversations.sumOf { it.unread }
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, label = { Text(stringResource(R.string.tab_messages)) },
                    icon = { BadgedBox(badge = { if (unread > 0) Badge { Text(unread.coerceAtMost(99).toString()) } }) { Icon(Icons.AutoMirrored.Outlined.Message, null) } })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, label = { Text(stringResource(R.string.tab_gateway)) }, icon = { Icon(Icons.Outlined.PhoneAndroid, null) })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            LinkBanner(snap.link, snap.demo)
            when (tab) {
                0 -> CallsScreen(repo, snap, snackbar, openThread = { t, a -> detail = Detail.Thread(t, a) })
                1 -> ContactsScreen(repo, snap, snackbar, openThread = { t, a -> detail = Detail.Thread(t, a) })
                2 -> MessagesScreen(repo, snap, openThread = { t, a -> detail = Detail.Thread(t, a) })
                else -> DeviceScreen(repo, snap, onUnpaired = { onboarding = true })
            }
        }
    }
}

/** Calm, one-line connection banner; hidden while connected to a real gateway. */
@Composable
fun LinkBanner(link: LinkState, demo: Boolean) {
    val text = when {
        demo -> stringResource(R.string.banner_demo)
        link == LinkState.CONNECTED -> return
        link == LinkState.BLUETOOTH_OFF -> stringResource(R.string.banner_bt_off)
        link == LinkState.CONNECTING -> stringResource(R.string.banner_connecting)
        link == LinkState.RECONNECTING -> stringResource(R.string.banner_reconnecting)
        else -> stringResource(R.string.banner_searching)
    }
    Surface(color = if (demo) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}
